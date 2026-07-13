package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends outbound messages on the user's behalf.
 *
 * High-level flow per send:
 *  1. Ensure we have a valid [SenderCertificate] cached (refresh from
 *     `GET /v1/certificate/delivery` if absent or near-expiry).
 *  2. For each of the recipient's devices: if there's no Signal session
 *     yet, fetch a prekey bundle via `GET /v2/keys/<aci>/[deviceId or asterisk]` and consume
 *     it with [SessionBuilder] to bootstrap one.
 *  3. Build a Content -> DataMessage proto with the body + timestamp.
 *  4. Pad to a 159-byte boundary (matches mautrix-signal /
 *     Signal-Android), then [SealedSessionCipher.encrypt] for each
 *     destination device.
 *  5. `PUT /v1/messages/<aci>` with one OutgoingMessage per device.
 *
 * Sealed sender (UNIDENTIFIED_SENDER, type 6) is preferred over plain
 * authenticated send so the server doesn't see who's sending — same
 * privacy posture as the official client. The HTTP auth itself still
 * uses our login/password; the "unidentified" part is that the encrypted
 * envelope wraps the SenderCertificate, not the auth header.
 */

/**
 * Thrown when the Signal server rejects our device credentials with HTTP 401.
 * In practice this means the linked device was removed from the primary phone
 * (Settings → Linked devices), so every authenticated call will keep failing
 * until the user re-links. The repository watches for this to flip into its
 * "re-link" state instead of silently failing sends.
 */
class SignalAuthException(message: String) : RuntimeException(message)

class SignalSender(
    context: Context,
    private val account: SignalAccount,
    private val api: SignalApi,
    /** GroupsV2 helper, for the SenderKey multi-recipient large-group path.
     *  Null → group sends always use per-member fan-out. */
    private val groups: SignalGroups? = null,
    private val protocolStore: AndroidSignalProtocolStore = AndroidSignalProtocolStore(context, account),
) {
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

    // SenderKey state (in-memory, per process). distributionId per group +
    // the set of members we've already shipped our SKDM to this session.
    private val groupDistributionIds = ConcurrentHashMap<String, UUID>()
    private val skdmDelivered = ConcurrentHashMap<String, MutableSet<String>>()

    // Cache the sender certificate across sends. Signal's cert lasts ~24h;
    // we conservatively refresh after 12h. Concurrent sends serialize on
    // [certMutex] so we don't fetch the same cert twice in parallel.
    private val certMutex = Mutex()
    private var cachedCert: SenderCertificate? = null
    private var cachedCertFetchedAt: Long = 0L

    // Cache the prekey bundle for OUR own ACI — every send-transcript and
    // every contact-sync request needs the list of our sibling devices, but
    // `GET /v2/keys/<own-aci>/*` is aggressively rate-limited (one 429 from
    // it blocks all keys traffic for ~10 minutes including normal outbound
    // sends). The bundle is stable across a session — when devices change,
    // we'll get a 410 on the actual `PUT /v1/messages` and bust the cache.
    private val ownBundleMutex = Mutex()
    private var cachedOwnBundle: PreKeyBundleResponse? = null
    private var cachedOwnBundleAt: Long = 0L

    // Same idea for recipient bundles: every outbound message used to hit
    // `GET /v2/keys/<recipient>/*` even when we already had a session.
    // Three messages to a contact in quick succession was enough to blow
    // the rate budget. Keyed by serviceId; small bounded LRU is overkill
    // here since a typical user only chats with a few peers per session.
    private val recipientBundleMutex = Mutex()
    private val cachedRecipientBundles = mutableMapOf<String, CachedBundle>()

    private data class CachedBundle(val bundle: PreKeyBundleResponse, val fetchedAt: Long)

    /**
     * Quote metadata for an outbound reply. [targetTimestamp] is the
     * sender-chosen timestamp of the message being replied to, [authorAci]
     * its author's service id, and [text] a short preview the recipient
     * renders in the quote chip.
     */
    data class QuoteInfo(
        val targetTimestamp: Long,
        val authorAci: String,
        val text: String,
    )

    /** A conversation's disappearing-messages timer (seconds) + its version. */
    data class ExpireTimer(val seconds: Int, val version: Int)

    /**
     * Looks up a conversation's current disappearing-messages timer by room id,
     * set by the repository after construction. Every outbound DataMessage
     * echoes this timer so we don't accidentally turn disappearing messages OFF
     * for the conversation (Signal treats a missing/zero timer as "off").
     */
    var conversationTimerLookup: ((roomId: String) -> ExpireTimer?)? = null

    /** Apply the conversation's timer to a DataMessage being built. */
    private fun SignalServiceProtos.DataMessage.Builder.applyExpire(
        roomId: String?,
    ): SignalServiceProtos.DataMessage.Builder {
        val t = roomId?.let { conversationTimerLookup?.invoke(it) }
        if (t != null && (t.seconds > 0 || t.version > 0)) {
            setExpireTimer(t.seconds)  // may be 0 = explicitly off at this version
            if (t.version > 0) setExpireTimerVersion(t.version)
        }
        return this
    }

    private fun groupRoomId(target: GroupTarget): String? =
        groups?.roomIdForMasterKey(target.masterKey)

    /** True when the DM target is our own account — "Note to Self". */
    private fun isSelfSend(recipientServiceId: String): Boolean {
        val id = recipientServiceId.removePrefix("PNI:").lowercase()
        return id == account.aci.lowercase() ||
            id == account.pni?.removePrefix("PNI:")?.lowercase()
    }

    /**
     * Deliver a DM DataMessage + mirror it to our own linked devices.
     *
     * Note to Self is special-cased the way Signal-Android's MessageSender
     * does it: there is NO DataMessage delivery to ourselves at all (the
     * server rejects a payload addressed to the sending device, so a normal
     * send can only fail). Instead the SyncMessage.Sent transcript IS the
     * delivery — sibling devices thread it into their own Note to Self. For
     * self-sends transcript failure is therefore a real send failure and
     * propagates; for normal sends it's log-and-swallow (the recipient
     * already got the message).
     */
    private suspend fun deliverDmWithSync(
        recipientServiceId: String,
        content: SignalServiceProtos.Content,
        dataMessage: SignalServiceProtos.DataMessage,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        if (isSelfSend(recipientServiceId)) {
            Log.d(TAG, "note-to-self: skipping DataMessage, sync transcript only @ $timestamp")
            sendSentTranscript(account.aci, dataMessage, timestamp, cert)
            return
        }
        encryptAndSendContent(recipientServiceId, content, timestamp, cert)
        runCatching { sendSentTranscript(recipientServiceId, dataMessage, timestamp, cert) }
            .onFailure { Log.w(TAG, "sent-transcript sync failed (recipient still got the message)", it) }
    }

    /**
     * Send `body` to a single direct recipient identified by ACI/PNI.
     * Optionally carries a [quote] so the recipient renders it as a reply.
     * Returns the sender-chosen timestamp on success; throws on any
     * non-2xx HTTP response or crypto error.
     */
    suspend fun sendDirectMessage(
        recipientServiceId: String,
        body: String,
        quote: QuoteInfo? = null,
    ): Long {
        val cert = getOrRefreshSenderCertificate()

        // Build the Content -> DataMessage proto. timestamp is the
        // sender-side message id; Signal echoes it back on the server-side
        // delivery receipt so we can ack the bubble we just sent.
        val timestamp = System.currentTimeMillis()
        val dataMessageBuilder = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(body)
            .setTimestamp(timestamp)
        if (quote != null) {
            dataMessageBuilder.setQuote(
                SignalServiceProtos.DataMessage.Quote.newBuilder()
                    .setId(quote.targetTimestamp)
                    .setAuthorAci(quote.authorAci)
                    .setText(quote.text)
                    .setType(SignalServiceProtos.DataMessage.Quote.Type.NORMAL)
                    .build()
            )
        }
        val dataMessage = dataMessageBuilder
            .applyExpire("sig:dm:$recipientServiceId")
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()

        deliverDmWithSync(recipientServiceId, content, dataMessage, timestamp, cert)
        Log.d(TAG, "sent to $recipientServiceId @ $timestamp")
        return timestamp
    }

    /**
     * Add or remove an emoji reaction on a message. `targetAuthorAci` +
     * `targetSentTimestamp` identify the message being reacted to (Signal
     * has no message ids — it keys off author + sent timestamp). Mirrors to
     * our own devices like a normal send so the primary sees the reaction.
     */
    suspend fun sendReaction(
        recipientServiceId: String,
        emoji: String,
        remove: Boolean,
        targetAuthorAci: String,
        targetSentTimestamp: Long,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val timestamp = System.currentTimeMillis()
        val reaction = SignalServiceProtos.DataMessage.Reaction.newBuilder()
            .setEmoji(emoji)
            .setRemove(remove)
            .setTargetAuthorAci(targetAuthorAci)
            .setTargetSentTimestamp(targetSentTimestamp)
            .build()
        val dataMessage = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(timestamp)
            .setReaction(reaction)
            .applyExpire("sig:dm:$recipientServiceId")
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
        deliverDmWithSync(recipientServiceId, content, dataMessage, timestamp, cert)
        Log.d(TAG, "reaction '$emoji' (remove=$remove) → $recipientServiceId for $targetAuthorAci@$targetSentTimestamp")
        return timestamp
    }

    /**
     * "Delete for everyone" — asks the recipient (and our own devices) to
     * redact the message identified by [targetSentTimestamp]. Signal renders
     * a "This message was deleted" tombstone in its place.
     */
    suspend fun sendRemoteDelete(
        recipientServiceId: String,
        targetSentTimestamp: Long,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val timestamp = System.currentTimeMillis()
        val delete = SignalServiceProtos.DataMessage.Delete.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .build()
        val dataMessage = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(timestamp)
            .setDelete(delete)
            .applyExpire("sig:dm:$recipientServiceId")
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
        deliverDmWithSync(recipientServiceId, content, dataMessage, timestamp, cert)
        Log.d(TAG, "remote-delete → $recipientServiceId for @$targetSentTimestamp")
        return timestamp
    }

    /**
     * Edit a previously-sent message. Signal models this as a top-level
     * `Content.editMessage` carrying the new DataMessage and the original
     * `targetSentTimestamp`. Synced to our own devices via a Sent transcript
     * that itself carries the EditMessage.
     */
    suspend fun sendEdit(
        recipientServiceId: String,
        targetSentTimestamp: Long,
        newBody: String,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val timestamp = System.currentTimeMillis()
        val innerDataMessage = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(newBody)
            .setTimestamp(timestamp)
            .applyExpire("sig:dm:$recipientServiceId")
            .build()
        val edit = SignalServiceProtos.EditMessage.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .setDataMessage(innerDataMessage)
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setEditMessage(edit)
            .build()
        if (isSelfSend(recipientServiceId)) {
            // Note to Self: the edit transcript IS the delivery (see
            // deliverDmWithSync) — no DataMessage to ourselves.
            Log.d(TAG, "note-to-self edit: sync transcript only @ $timestamp")
            sendEditTranscript(account.aci, edit, timestamp, cert)
        } else {
            encryptAndSendContent(recipientServiceId, content, timestamp, cert)
            runCatching { sendEditTranscript(recipientServiceId, edit, timestamp, cert) }
                .onFailure { Log.w(TAG, "edit sent-transcript sync failed", it) }
        }
        Log.d(TAG, "edit → $recipientServiceId for @$targetSentTimestamp")
        return timestamp
    }

    /**
     * Send a media attachment (already uploaded to the CDN by
     * [SignalAttachments.upload]) to a direct recipient, optionally with a
     * caption [body]. Builds an AttachmentPointer from the upload result.
     */
    suspend fun sendAttachment(
        recipientServiceId: String,
        uploaded: SignalAttachments.Uploaded,
        body: String? = null,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val timestamp = System.currentTimeMillis()
        val pointerBuilder = SignalServiceProtos.AttachmentPointer.newBuilder()
            .setCdnKey(uploaded.cdnKey)
            .setCdnNumber(uploaded.cdnNumber)
            .setContentType(uploaded.contentType)
            .setKey(com.google.protobuf.ByteString.copyFrom(uploaded.key))
            .setDigest(com.google.protobuf.ByteString.copyFrom(uploaded.digest))
            .setSize(uploaded.size)
        uploaded.fileName?.let { pointerBuilder.setFileName(it) }
        val dataMessageBuilder = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(timestamp)
            .addAttachments(pointerBuilder.build())
        if (!body.isNullOrEmpty()) dataMessageBuilder.setBody(body)
        val dataMessage = dataMessageBuilder
            .applyExpire("sig:dm:$recipientServiceId")
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
        deliverDmWithSync(recipientServiceId, content, dataMessage, timestamp, cert)
        Log.d(TAG, "sent attachment (${uploaded.size}b, ${uploaded.contentType}) → $recipientServiceId @ $timestamp")
        return timestamp
    }

    // ------------------------------------------------------------------
    // group sends (GroupsV2)
    // ------------------------------------------------------------------

    /**
     * Everything needed to fan a message out to a group: the 32-byte
     * [masterKey] + current [revision] (echoed in each message's groupV2
     * context so recipients attribute it to the group) and the resolved
     * [memberAcis] to deliver to.
     */
    data class GroupTarget(
        val masterKey: ByteArray,
        val revision: Int,
        val memberAcis: List<String>,
    )

    suspend fun sendGroupText(
        target: GroupTarget,
        body: String,
        quote: QuoteInfo? = null,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val ts = System.currentTimeMillis()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(body)
            .setTimestamp(ts)
            .setGroupV2(groupContext(target))
        if (quote != null) {
            dm.setQuote(
                SignalServiceProtos.DataMessage.Quote.newBuilder()
                    .setId(quote.targetTimestamp)
                    .setAuthorAci(quote.authorAci)
                    .setText(quote.text)
                    .setType(SignalServiceProtos.DataMessage.Quote.Type.NORMAL)
                    .build()
            )
        }
        return sendGroupDataMessage(target, dm.applyExpire(groupRoomId(target)).build(), ts, cert)
    }

    suspend fun sendGroupReaction(
        target: GroupTarget,
        emoji: String,
        remove: Boolean,
        targetAuthorAci: String,
        targetSentTimestamp: Long,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val ts = System.currentTimeMillis()
        val reaction = SignalServiceProtos.DataMessage.Reaction.newBuilder()
            .setEmoji(emoji)
            .setRemove(remove)
            .setTargetAuthorAci(targetAuthorAci)
            .setTargetSentTimestamp(targetSentTimestamp)
            .build()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(ts)
            .setGroupV2(groupContext(target))
            .setReaction(reaction)
            .applyExpire(groupRoomId(target))
            .build()
        return sendGroupDataMessage(target, dm, ts, cert)
    }

    suspend fun sendGroupRemoteDelete(target: GroupTarget, targetSentTimestamp: Long): Long {
        val cert = getOrRefreshSenderCertificate()
        val ts = System.currentTimeMillis()
        val delete = SignalServiceProtos.DataMessage.Delete.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .build()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(ts)
            .setGroupV2(groupContext(target))
            .setDelete(delete)
            .applyExpire(groupRoomId(target))
            .build()
        return sendGroupDataMessage(target, dm, ts, cert)
    }

    suspend fun sendGroupEdit(target: GroupTarget, targetSentTimestamp: Long, newBody: String): Long {
        val cert = getOrRefreshSenderCertificate()
        val ts = System.currentTimeMillis()
        val inner = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(newBody)
            .setTimestamp(ts)
            .setGroupV2(groupContext(target))
            .applyExpire(groupRoomId(target))
            .build()
        val edit = SignalServiceProtos.EditMessage.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .setDataMessage(inner)
            .build()
        val content = SignalServiceProtos.Content.newBuilder().setEditMessage(edit).build()
        deliverGroupContent(target, content, ts, cert)
        runCatching { sendGroupSentTranscript(message = null, edit = edit, timestamp = ts, cert = cert) }
            .onFailure { Log.w(TAG, "group edit transcript failed", it) }
        Log.d(TAG, "group edit for @$targetSentTimestamp → ${memberCount(target)} member(s)")
        return ts
    }

    suspend fun sendGroupAttachment(
        target: GroupTarget,
        uploaded: SignalAttachments.Uploaded,
        body: String? = null,
    ): Long {
        val cert = getOrRefreshSenderCertificate()
        val ts = System.currentTimeMillis()
        val pointerBuilder = SignalServiceProtos.AttachmentPointer.newBuilder()
            .setCdnKey(uploaded.cdnKey)
            .setCdnNumber(uploaded.cdnNumber)
            .setContentType(uploaded.contentType)
            .setKey(com.google.protobuf.ByteString.copyFrom(uploaded.key))
            .setDigest(com.google.protobuf.ByteString.copyFrom(uploaded.digest))
            .setSize(uploaded.size)
        uploaded.fileName?.let { pointerBuilder.setFileName(it) }
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(ts)
            .setGroupV2(groupContext(target))
            .addAttachments(pointerBuilder.build())
        if (!body.isNullOrEmpty()) dm.setBody(body)
        return sendGroupDataMessage(target, dm.applyExpire(groupRoomId(target)).build(), ts, cert)
    }

    /** Build the GroupContextV2 echoed in every group message. */
    private fun groupContext(target: GroupTarget): SignalServiceProtos.GroupContextV2 =
        SignalServiceProtos.GroupContextV2.newBuilder()
            .setMasterKey(com.google.protobuf.ByteString.copyFrom(target.masterKey))
            .setRevision(target.revision)
            .build()

    private fun memberCount(target: GroupTarget): Int =
        target.memberAcis.count { it != account.aci }

    /** Wrap a group DataMessage in Content, fan it out, and transcript it. */
    private suspend fun sendGroupDataMessage(
        target: GroupTarget,
        dataMessage: SignalServiceProtos.DataMessage,
        timestamp: Long,
        cert: SenderCertificate,
    ): Long {
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
        deliverGroupContent(target, content, timestamp, cert)
        runCatching { sendGroupSentTranscript(message = dataMessage, edit = null, timestamp = timestamp, cert = cert) }
            .onFailure { Log.w(TAG, "group sent-transcript failed", it) }
        Log.d(TAG, "group send @ $timestamp → ${memberCount(target)} member(s)")
        return timestamp
    }

    /**
     * Deliver group [content] using the cheapest path that works: for groups
     * past [SENDERKEY_THRESHOLD] members, attempt the SenderKey
     * multi-recipient send (one PUT for the whole group); on any failure — or
     * for small groups where it isn't worth the SKDM setup — fall back to
     * per-member [fanOutGroup]. Either way every member is reached.
     */
    private suspend fun deliverGroupContent(
        target: GroupTarget,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val viaSenderKey = groups != null &&
            memberCount(target) > SENDERKEY_THRESHOLD &&
            runCatching { sendViaSenderKey(target, content, timestamp, cert) }
                .onFailure { Log.w(TAG, "SenderKey multi-recipient send failed; falling back to fan-out", it) }
                .getOrDefault(false)
        if (!viaSenderKey) {
            fanOutGroup(target, content, timestamp, cert)
        }
    }

    /**
     * The large-group fast path. Encrypt [content] ONCE with a SenderKey
     * ([GroupCipher]) and deliver it to every member device in a single
     * `multi_recipient` PUT, authorized by a zkgroup group-send token.
     *
     * Steps: resolve member devices + sessions → ensure each member has our
     * SenderKeyDistributionMessage → GroupCipher-encrypt → wrap as
     * [UnidentifiedSenderMessageContent] with the group id →
     * [SealedSessionCipher.multiRecipientEncrypt] → PUT. Returns true on a 2xx;
     * false (or throws, caught by the caller) triggers the fan-out fallback.
     *
     * ⚠️ Uses several version-sensitive libsignal APIs (GroupCipher,
     * multiRecipientEncrypt, UnidentifiedSenderMessageContent) plus zkgroup
     * endorsements — verify on-device against libsignal-android 0.86.5.
     */
    private suspend fun sendViaSenderKey(
        target: GroupTarget,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        cert: SenderCertificate,
    ): Boolean {
        val groups = groups ?: return false
        val groupIdBytes = groups.groupIdBytesForMasterKey(target.masterKey) ?: return false
        val groupIdB64 = Base64.encodeToString(groupIdBytes, Base64.NO_WRAP)
        val recipients = target.memberAcis.filter { it != account.aci }.distinct()
        if (recipients.isEmpty()) return false

        // Resolve every member device + ensure a session, collecting addresses.
        val addresses = mutableListOf<SignalProtocolAddress>()
        for (aci in recipients) {
            val bundle = getRecipientBundleCached(aci)
            val identityKey = IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP), 0)
            bundle.devices.forEach { dev ->
                bootstrapSessionIfNeeded(aci, dev, identityKey)
                addresses += SignalProtocolAddress(aci, dev.deviceId)
            }
        }
        if (addresses.isEmpty()) return false

        // SenderKey: stable distributionId per group, create our SKDM, and make
        // sure every member has it (so their GroupCipher can decrypt).
        val selfAddress = SignalProtocolAddress(account.aci, account.deviceId)
        val distributionId = groupDistributionIds.getOrPut(groupIdB64) { UUID.randomUUID() }
        val skdm = GroupSessionBuilder(protocolStore).create(selfAddress, distributionId)
        distributeSenderKey(groupIdB64, target.revision, skdm, recipients, timestamp, cert)

        // Encrypt the padded Content once under the SenderKey.
        val padded = padPlaintext(content.toByteArray())
        val groupCipher = GroupCipher(protocolStore, selfAddress)
        val ciphertext = groupCipher.encrypt(distributionId, padded)

        // Wrap + multi-recipient seal.
        val usmc = UnidentifiedSenderMessageContent(
            ciphertext,
            cert,
            CONTENT_HINT_RESENDABLE,
            Optional.of(groupIdBytes),
        )
        val sealed = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            account.phoneNumber,
            account.deviceId,
        )
        val blob = sealed.multiRecipientEncrypt(addresses, usmc)

        // Authorize with a group-send token over the recipient set.
        val token = groups.buildGroupSendToken(target.masterKey, recipients) ?: return false
        val tokenB64 = Base64.encodeToString(token, Base64.NO_WRAP)

        val result = api.sendMultiRecipient(blob, timestamp, tokenB64)
        if (!result.isSuccess) {
            Log.w(TAG, "multi_recipient HTTP ${result.httpStatus}: ${result.rawBody.take(160)}")
            return false
        }
        Log.d(TAG, "SenderKey multi-recipient send → ${addresses.size} device(s) in 1 PUT")
        return true
    }

    /**
     * Ship our [skdm] to each member that hasn't received it for this group +
     * revision yet (sealed-sender 1:1). Tracked in-memory so subsequent group
     * sends in the same session skip straight to the single multi-recipient PUT.
     * Membership changes bump the revision, which resets the delivered set.
     */
    private suspend fun distributeSenderKey(
        groupIdB64: String,
        revision: Int,
        skdm: org.signal.libsignal.protocol.message.SenderKeyDistributionMessage,
        recipients: List<String>,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val key = "$groupIdB64@$revision"
        val delivered = skdmDelivered.getOrPut(key) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        val skdmContent = SignalServiceProtos.Content.newBuilder()
            .setSenderKeyDistributionMessage(com.google.protobuf.ByteString.copyFrom(skdm.serialize()))
            .build()
        for (aci in recipients) {
            if (aci in delivered) continue
            runCatching { encryptAndSendContent(aci, skdmContent, timestamp, cert) }
                .onSuccess { delivered.add(aci) }
                .onFailure { Log.w(TAG, "SKDM delivery to $aci failed", it) }
        }
    }

    /**
     * Deliver [content] to every group member (except ourselves) as an
     * individual sealed-sender message. This is the per-recipient fallback to
     * SenderKey multi-recipient fan-out — slower on big groups but far simpler,
     * and every Signal client accepts it (the group attribution comes from the
     * message's groupV2 context, not the transport). One member failing
     * doesn't abort the rest.
     */
    private suspend fun fanOutGroup(
        target: GroupTarget,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val recipients = target.memberAcis.filter { it != account.aci }.distinct()
        if (recipients.isEmpty()) {
            Log.w(TAG, "group fan-out: no other members resolved")
            return
        }
        var delivered = 0
        var lastError: Throwable? = null
        for (memberAci in recipients) {
            runCatching { encryptAndSendContent(memberAci, content, timestamp, cert) }
                .onSuccess { delivered++ }
                .onFailure { Log.w(TAG, "group send to $memberAci failed", it); lastError = it }
        }
        Log.d(TAG, "group fan-out delivered to $delivered/${recipients.size}")
        // Reaching at least one member counts as sent (partial delivery is
        // normal). Reaching NOBODY is a real failure the UI must show — rethrow
        // so sendMessage marks the bubble failed. Prefer the auth exception (a
        // 401 = unlinked device) so the repository can prompt a re-link.
        if (delivered == 0) {
            val authErr = lastError as? SignalAuthException
            throw authErr
                ?: lastError
                ?: RuntimeException("group send: 0/${recipients.size} members reached")
        }
    }

    /**
     * Group sent-transcript to our own devices. Unlike the DM transcript it
     * omits `destinationServiceId` — recipients identify the group from the
     * carried message's groupV2 context.
     */
    private suspend fun sendGroupSentTranscript(
        message: SignalServiceProtos.DataMessage?,
        edit: SignalServiceProtos.EditMessage?,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val sentBuilder = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setTimestamp(timestamp)
        if (message != null) sentBuilder.setMessage(message)
        if (edit != null) sentBuilder.setEditMessage(edit)
        val content = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(
                SignalServiceProtos.SyncMessage.newBuilder()
                    .setSent(sentBuilder.build())
                    .build()
            )
            .build()
        sendSyncToOwnDevices(content, timestamp, cert)
    }

    /**
     * Encrypt [content] once per recipient device (sealed sender) and PUT it
     * to `/v1/messages/<recipientServiceId>`. Shared by every outbound path
     * (text, reaction, delete, edit, receipt). Throws on any non-2xx.
     *
     * Recipient prekey bundle is served from a short-lived cache: Signal's
     * the `/v2/keys` endpoint is rate-limited account-wide, so fetching on every send
     * blows the budget when the user fires a few messages in close
     * succession. The bundle is stable across a session unless the recipient
     * adds a device or rotates keys.
     */
    private suspend fun encryptAndSendContent(
        recipientServiceId: String,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val bundle = getRecipientBundleCached(recipientServiceId)
        // No devices means there's nothing to deliver to — sending an empty
        // message list still returns 2xx from the server, which would surface
        // as a misleading "sent" check on a message nobody received. Fail loud
        // instead so the bubble shows as failed. (Seen with PNI-only recipients
        // whose prekeys aren't fetchable by phone-number identity.)
        if (bundle.devices.isEmpty()) {
            throw RuntimeException("no prekey devices for $recipientServiceId — message not delivered")
        }
        val identityKey = IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP), 0)
        bundle.devices.forEach { dev ->
            bootstrapSessionIfNeeded(recipientServiceId, dev, identityKey)
        }
        val padded = padPlaintext(content.toByteArray())
        val cipher = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            /* localE164 = */ account.phoneNumber,
            account.deviceId,
        )
        val outgoing = bundle.devices.map { dev ->
            val destAddress = SignalProtocolAddress(recipientServiceId, dev.deviceId)
            val encrypted = cipher.encrypt(destAddress, cert, padded)
            OutgoingMessage(
                type = ENVELOPE_TYPE_UNIDENTIFIED_SENDER,
                destinationDeviceId = dev.deviceId,
                destinationRegistrationId = dev.registrationId,
                content = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        }
        Log.d(TAG, "sending to $recipientServiceId — ${outgoing.size} device message(s)")
        val result = api.sendMessage(
            login = login,
            password = password,
            recipientServiceId = recipientServiceId,
            body = SendMessageRequest(messages = outgoing, timestamp = timestamp),
        )
        if (!result.isSuccess) {
            // A 401 means our device credentials are no longer valid — almost
            // always because the device was unlinked from the primary phone.
            // Surface it as a distinct type so the repository can prompt a
            // re-link instead of just silently failing the send.
            if (result.httpStatus == 401) {
                throw SignalAuthException("send failed HTTP 401: ${result.rawBody}")
            }
            throw RuntimeException("send failed HTTP ${result.httpStatus}: ${result.rawBody}")
        }
    }

    /**
     * Send a `SyncMessage.Sent` transcript to our own ACI. The Signal
     * server fans it out to every OTHER device on our account (it
     * automatically excludes the originating device by registrationId),
     * which is how the primary phone learns about messages sent from this
     * linked device. Without this, sent messages would only ever appear
     * on this device + the recipient's; the primary's chat would diverge.
     *
     * Wire shape: Content{ syncMessage: SyncMessage{ sent: Sent{
     *   destinationServiceId, timestamp, message: DataMessage,
     *   unidentifiedStatus: [per-device delivery flags]
     * } } }
     */
    /** ACI/PNI service-id string → its binary form (16 bytes for ACI, 1-byte
     *  prefix + 16 for PNI), as Signal's transcripts and envelopes expect. */
    private fun serviceIdBinary(serviceId: String): com.google.protobuf.ByteString =
        com.google.protobuf.ByteString.copyFrom(
            org.signal.libsignal.protocol.ServiceId.parseFromString(serviceId).toServiceIdBinary(),
        )

    private suspend fun sendSentTranscript(
        recipientServiceId: String,
        dataMessage: SignalServiceProtos.DataMessage,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        // Fetch our OWN devices so we know who to encrypt for. Cached for
        // a few minutes via getOwnBundleCached — the underlying endpoint is
        // rate-limited and shared with sendContactSyncRequest, so without
        // the cache a "send + auto-fired contact sync" sequence trips 429.
        val ourBundle = getOwnBundleCached()
        val ourIdentityKey = IdentityKey(Base64.decode(ourBundle.identityKey, Base64.NO_WRAP), 0)
        val otherDevices = ourBundle.devices.filter { it.deviceId != account.deviceId }
        if (otherDevices.isEmpty()) {
            Log.d(TAG, "no other linked devices — skipping sent-transcript")
            return
        }
        // Ensure sessions exist for each sibling device.
        otherDevices.forEach { bootstrapSessionIfNeeded(account.aci, it, ourIdentityKey) }

        // Address the transcript by BINARY service id AND include an
        // unidentifiedStatus entry — exactly like Signal-Android's
        // SignalServiceMessageSender. Modern Signal receivers (iOS/Desktop) read
        // `destinationServiceIdBinary`, not the legacy string field, to attribute
        // a synced sent message to a conversation. With only the string set a
        // sibling device receives the transcript (its delivery receipt fires) but
        // can't thread it, so the message never appears — the "sent from linked
        // device didn't sync to my other device" bug.
        val recipientBinary = serviceIdBinary(recipientServiceId)
        val sent = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setDestinationServiceId(recipientServiceId)
            .setDestinationServiceIdBinary(recipientBinary)
            .setTimestamp(timestamp)
            .setMessage(dataMessage)
            .addUnidentifiedStatus(
                SignalServiceProtos.SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder()
                    .setDestinationServiceIdBinary(recipientBinary)
                    .setUnidentified(true),
            )
            .build()
        val sync = SignalServiceProtos.SyncMessage.newBuilder()
            .setSent(sent)
            .build()
        val syncContent = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(sync)
            .build()
        val padded = padPlaintext(syncContent.toByteArray())

        val cipher = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            account.phoneNumber,
            account.deviceId,
        )
        val outgoing = otherDevices.map { dev ->
            val addr = SignalProtocolAddress(account.aci, dev.deviceId)
            val encrypted = cipher.encrypt(addr, cert, padded)
            OutgoingMessage(
                type = ENVELOPE_TYPE_UNIDENTIFIED_SENDER,
                destinationDeviceId = dev.deviceId,
                destinationRegistrationId = dev.registrationId,
                content = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        }
        val result = api.sendMessage(
            login = login,
            password = password,
            recipientServiceId = account.aci,
            body = SendMessageRequest(messages = outgoing, timestamp = timestamp),
        )
        if (!result.isSuccess) {
            throw RuntimeException("sent-transcript HTTP ${result.httpStatus}: ${result.rawBody}")
        }
        Log.d(
            TAG,
            "sent-transcript delivered to ${outgoing.size} sibling device(s) " +
                "recipient=$recipientServiceId ts=$timestamp",
        )
    }

    /**
     * Sync "I read these conversations" to our OWN other devices only — a
     * `SyncMessage.Read` posted to our own ACI. The server fans it out to sibling
     * devices (excluding this one), so the primary phone etc. clear the chat's
     * notification + unread. This is NOT a peer read receipt: we never send a
     * `ReceiptMessage(READ)`, so the sender is never told we read their message.
     *
     * @param reads (senderServiceId, messageTimestamp) pairs — one per conversation
     *   read; a sibling matches each to a chat exactly as it matches inbound messages.
     */
    suspend fun sendReadSync(reads: List<Pair<String, Long>>) {
        if (reads.isEmpty()) return
        val cert = getOrRefreshSenderCertificate()
        val ourBundle = getOwnBundleCached()
        val ourIdentityKey = IdentityKey(Base64.decode(ourBundle.identityKey, Base64.NO_WRAP), 0)
        val otherDevices = ourBundle.devices.filter { it.deviceId != account.deviceId }
        if (otherDevices.isEmpty()) {
            Log.d(TAG, "read-sync: no other linked devices — skipping")
            return
        }
        otherDevices.forEach { bootstrapSessionIfNeeded(account.aci, it, ourIdentityKey) }

        val syncBuilder = SignalServiceProtos.SyncMessage.newBuilder()
        for ((serviceId, ts) in reads) {
            syncBuilder.addRead(
                SignalServiceProtos.SyncMessage.Read.newBuilder()
                    .setSenderAci(serviceId)
                    .setSenderAciBinary(serviceIdBinary(serviceId))
                    .setTimestamp(ts),
            )
        }
        val syncContent = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(syncBuilder.build())
            .build()
        val padded = padPlaintext(syncContent.toByteArray())
        val timestamp = System.currentTimeMillis()

        val cipher = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            account.phoneNumber,
            account.deviceId,
        )
        val outgoing = otherDevices.map { dev ->
            val addr = SignalProtocolAddress(account.aci, dev.deviceId)
            val encrypted = cipher.encrypt(addr, cert, padded)
            OutgoingMessage(
                type = ENVELOPE_TYPE_UNIDENTIFIED_SENDER,
                destinationDeviceId = dev.deviceId,
                destinationRegistrationId = dev.registrationId,
                content = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        }
        val result = api.sendMessage(
            login = login,
            password = password,
            recipientServiceId = account.aci,
            body = SendMessageRequest(messages = outgoing, timestamp = timestamp),
        )
        if (!result.isSuccess) {
            Log.w(TAG, "read-sync HTTP ${result.httpStatus}: ${result.rawBody}")
            return
        }
        Log.d(TAG, "read-sync delivered to ${outgoing.size} sibling device(s) for ${reads.size} convo(s)")
    }

    /**
     * Ask our primary device to push us a fresh contact-sync attachment.
     * Sent as a `SyncMessage.Request{type: CONTACTS}` to our own ACI; the
     * primary phone responds asynchronously with a `SyncMessage.Contacts`
     * inbound envelope, which [SignalContactSyncHandler] processes.
     *
     * Debounced via [CONTACT_SYNC_MIN_INTERVAL_MS]. Without the debounce,
     * reconnect storms (e.g. WebSocket flap during testing) hammer the
     * `GET /v2/keys/[own-aci]/[asterisk]` endpoint that this method calls
     * to find sibling devices, which Signal rate-limits aggressively —
     * 429s lock us out of all keys traffic for ~10 minutes including
     * outbound message sending. One contact sync every few minutes is
     * plenty; if the primary didn't respond to the first request, it's
     * not going to magically start responding to ten more.
     */
    suspend fun sendContactSyncRequest() {
        val now = System.currentTimeMillis()
        val lastAttempt = lastContactSyncRequestAt
        if (lastAttempt != 0L && (now - lastAttempt) < CONTACT_SYNC_MIN_INTERVAL_MS) {
            val ageSec = (now - lastAttempt) / 1000
            Log.d(TAG, "contact-sync request: skipping (last attempt ${ageSec}s ago, min interval ${CONTACT_SYNC_MIN_INTERVAL_MS / 1000}s)")
            return
        }
        lastContactSyncRequestAt = now
        sendContactSyncRequestInternal()
    }

    @Volatile private var lastContactSyncRequestAt: Long = 0L

    private suspend fun sendContactSyncRequestInternal() {
        val cert = getOrRefreshSenderCertificate()
        val ourBundle = getOwnBundleCached()
        val ourIdentityKey = IdentityKey(Base64.decode(ourBundle.identityKey, Base64.NO_WRAP), 0)
        val otherDevices = ourBundle.devices.filter { it.deviceId != account.deviceId }
        if (otherDevices.isEmpty()) {
            Log.d(TAG, "no other linked devices — skipping contact-sync request")
            return
        }
        otherDevices.forEach { bootstrapSessionIfNeeded(account.aci, it, ourIdentityKey) }

        val request = SignalServiceProtos.SyncMessage.Request.newBuilder()
            .setType(SignalServiceProtos.SyncMessage.Request.Type.CONTACTS)
            .build()
        val sync = SignalServiceProtos.SyncMessage.newBuilder()
            .setRequest(request)
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(sync)
            .build()
        val padded = padPlaintext(content.toByteArray())

        val timestamp = System.currentTimeMillis()
        val cipher = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            account.phoneNumber,
            account.deviceId,
        )
        val outgoing = otherDevices.map { dev ->
            val addr = SignalProtocolAddress(account.aci, dev.deviceId)
            val encrypted = cipher.encrypt(addr, cert, padded)
            OutgoingMessage(
                type = ENVELOPE_TYPE_UNIDENTIFIED_SENDER,
                destinationDeviceId = dev.deviceId,
                destinationRegistrationId = dev.registrationId,
                content = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        }
        val result = api.sendMessage(
            login = login,
            password = password,
            recipientServiceId = account.aci,
            body = SendMessageRequest(messages = outgoing, timestamp = timestamp),
        )
        if (!result.isSuccess) {
            Log.w(TAG, "contact-sync request HTTP ${result.httpStatus}: ${result.rawBody}")
            return
        }
        Log.d(TAG, "contact-sync request sent to ${outgoing.size} sibling device(s)")
    }

    /**
     * Sync an edit to our own other devices. Same shape as
     * [sendSentTranscript] but the Sent transcript carries an `editMessage`
     * instead of a plain `message`, so the primary applies the edit too.
     */
    private suspend fun sendEditTranscript(
        recipientServiceId: String,
        edit: SignalServiceProtos.EditMessage,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val recipientBinary = serviceIdBinary(recipientServiceId)
        val sent = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setDestinationServiceId(recipientServiceId)
            .setDestinationServiceIdBinary(recipientBinary)
            .setTimestamp(timestamp)
            .setEditMessage(edit)
            .addUnidentifiedStatus(
                SignalServiceProtos.SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder()
                    .setDestinationServiceIdBinary(recipientBinary)
                    .setUnidentified(true),
            )
            .build()
        val sync = SignalServiceProtos.SyncMessage.newBuilder()
            .setSent(sent)
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(sync)
            .build()
        sendSyncToOwnDevices(content, timestamp, cert)
    }

    /**
     * Encrypt [content] for each of our OTHER linked devices (sealed sender)
     * and PUT it to our own ACI. The server fans it out to every device
     * except the originator. Shared by the edit-transcript path; the older
     * `sendSentTranscript` / `sendContactSyncRequestInternal` predate this
     * helper and inline the same loop.
     */
    private suspend fun sendSyncToOwnDevices(
        content: SignalServiceProtos.Content,
        timestamp: Long,
        cert: SenderCertificate,
    ) {
        val ourBundle = getOwnBundleCached()
        val ourIdentityKey = IdentityKey(Base64.decode(ourBundle.identityKey, Base64.NO_WRAP), 0)
        val otherDevices = ourBundle.devices.filter { it.deviceId != account.deviceId }
        if (otherDevices.isEmpty()) {
            Log.d(TAG, "no other linked devices — skipping own-device sync")
            return
        }
        otherDevices.forEach { bootstrapSessionIfNeeded(account.aci, it, ourIdentityKey) }
        val padded = padPlaintext(content.toByteArray())
        val cipher = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            account.phoneNumber,
            account.deviceId,
        )
        val outgoing = otherDevices.map { dev ->
            val addr = SignalProtocolAddress(account.aci, dev.deviceId)
            val encrypted = cipher.encrypt(addr, cert, padded)
            OutgoingMessage(
                type = ENVELOPE_TYPE_UNIDENTIFIED_SENDER,
                destinationDeviceId = dev.deviceId,
                destinationRegistrationId = dev.registrationId,
                content = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
        }
        val result = api.sendMessage(
            login = login,
            password = password,
            recipientServiceId = account.aci,
            body = SendMessageRequest(messages = outgoing, timestamp = timestamp),
        )
        if (!result.isSuccess) {
            throw RuntimeException("own-device sync HTTP ${result.httpStatus}: ${result.rawBody}")
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Cached lookup of our own ACI's prekey bundle (for finding sibling
     * devices). Refreshes after [OWN_BUNDLE_TTL_MS]. Callers should also
     * invalidate via [invalidateOwnBundle] when a `PUT /v1/messages` to
     * our own ACI returns 409/410 (stale device list).
     */
    private suspend fun getOwnBundleCached(): PreKeyBundleResponse = ownBundleMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedOwnBundle
        if (cached != null && (now - cachedOwnBundleAt) < OWN_BUNDLE_TTL_MS) {
            return@withLock cached
        }
        val fresh = api.fetchPreKeys(login, password, account.aci)
        cachedOwnBundle = fresh
        cachedOwnBundleAt = now
        Log.d(TAG, "refreshed own-ACI prekey bundle (${fresh.devices.size} device(s))")
        fresh
    }

    @Suppress("unused")
    private fun invalidateOwnBundle() {
        cachedOwnBundle = null
        cachedOwnBundleAt = 0L
    }

    /** Cached recipient-bundle lookup. Mirrors [getOwnBundleCached]. */
    private suspend fun getRecipientBundleCached(recipientServiceId: String): PreKeyBundleResponse =
        recipientBundleMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cachedRecipientBundles[recipientServiceId]
            if (cached != null && (now - cached.fetchedAt) < RECIPIENT_BUNDLE_TTL_MS) {
                return@withLock cached.bundle
            }
            val fresh = api.fetchPreKeys(login, password, recipientServiceId)
            cachedRecipientBundles[recipientServiceId] = CachedBundle(fresh, now)
            Log.d(TAG, "refreshed prekey bundle for $recipientServiceId (${fresh.devices.size} device(s))")
            fresh
        }

    @Suppress("unused")
    private fun invalidateRecipientBundle(recipientServiceId: String) {
        cachedRecipientBundles.remove(recipientServiceId)
    }

    private suspend fun getOrRefreshSenderCertificate(): SenderCertificate = certMutex.withLock {
        val now = System.currentTimeMillis()
        val stale = cachedCert == null || (now - cachedCertFetchedAt) > CERT_TTL_MS
        if (stale) {
            val resp = api.fetchSenderCertificate(login, password)
            val bytes = Base64.decode(resp.certificate, Base64.NO_WRAP)
            cachedCert = SenderCertificate(bytes)
            cachedCertFetchedAt = now
            Log.d(TAG, "refreshed sender certificate (${bytes.size} bytes)")
        }
        cachedCert!!
    }

    /**
     * If we don't already have a session for (recipientServiceId, deviceId),
     * consume the prekey bundle from the server to create one. libsignal
     * verifies the signed prekey signature against the identity key for
     * us — we just need to assemble a [PreKeyBundle] from the JSON shape.
     */
    private fun bootstrapSessionIfNeeded(
        recipientServiceId: String,
        dev: DevicePreKeyBundle,
        identityKey: IdentityKey,
    ) {
        val address = SignalProtocolAddress(recipientServiceId, dev.deviceId)
        if (protocolStore.containsSession(address)) {
            return  // existing session — nothing to do
        }

        val signedPreKeyId = dev.signedPreKey.keyId
        val signedPreKeyPub = ECPublicKey(Base64.decode(dev.signedPreKey.publicKey, Base64.NO_WRAP))
        val signedPreKeySig = Base64.decode(dev.signedPreKey.signature, Base64.NO_WRAP)

        // libsignal 0.8x removed the ECC-only PreKeyBundle constructor;
        // the Kyber arguments are now mandatory. Every modern Signal
        // client uploads a pqPreKey at link time, so a recipient missing
        // one means either: very old client, or a server response shape
        // we don't recognise yet. Bail loudly rather than silently
        // bootstrap a non-PQXDH session that won't decrypt later.
        val pqPreKey = dev.pqPreKey
            ?: throw IllegalStateException(
                "recipient ${address.name}.${address.deviceId} has no pqPreKey; " +
                    "cannot establish session under libsignal 0.8x",
            )
        val kyberPreKeyId = pqPreKey.keyId
        val kyberPub = KEMPublicKey(Base64.decode(pqPreKey.publicKey, Base64.NO_WRAP))
        val kyberSig = Base64.decode(pqPreKey.signature, Base64.NO_WRAP)

        // libsignal 0.8x: `preKeyPublic` is nullable but `preKeyId` is a
        // plain Int. The companion object documents the sentinel:
        //   PreKeyBundle.NULL_PRE_KEY_ID = -1
        // (treated as `Option<u32>::None` by the bridging layer.) Use it
        // when the server returns `preKey: null` because the recipient
        // device has burnt through its uploaded one-time prekey pool.
        val oneTimeId: Int = dev.preKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID
        val oneTimePub: ECPublicKey? = dev.preKey?.let {
            ECPublicKey(Base64.decode(it.publicKey, Base64.NO_WRAP))
        }

        val bundle = PreKeyBundle(
            dev.registrationId,
            dev.deviceId,
            oneTimeId,
            oneTimePub,
            signedPreKeyId,
            signedPreKeyPub,
            signedPreKeySig,
            identityKey,
            kyberPreKeyId,
            kyberPub,
            kyberSig,
        )
        SessionBuilder(protocolStore, address).process(bundle)
        Log.d(TAG, "bootstrapped session with $recipientServiceId.${dev.deviceId}")
    }

    /**
     * Signal pads the plaintext before encryption so the on-the-wire
     * envelope length doesn't leak the message length. Format:
     *
     *   plaintext || 0x80 || 0x00 * (padToBoundary - len(plaintext) - 1)
     *
     * Boundary picked to match Signal-Android: round up the plaintext+1
     * length to the next multiple of 159 (the magic number is from
     * Signal-Android's `PushTransportDetails.getPaddedMessageLength`).
     */
    private fun padPlaintext(plaintext: ByteArray): ByteArray {
        val withTerminator = plaintext.size + 1
        val paddedLen = ((withTerminator + PADDING_GRANULE - 1) / PADDING_GRANULE) * PADDING_GRANULE
        val out = ByteArray(paddedLen)
        System.arraycopy(plaintext, 0, out, 0, plaintext.size)
        out[plaintext.size] = 0x80.toByte()
        return out
    }

    companion object {
        private const val TAG = "SignalSender"
        private const val CERT_TTL_MS = 12L * 60 * 60 * 1000  // 12 hours
        private const val CONTACT_SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000  // 5 minutes
        private const val OWN_BUNDLE_TTL_MS = 5L * 60 * 1000             // 5 minutes
        private const val RECIPIENT_BUNDLE_TTL_MS = 5L * 60 * 1000       // 5 minutes
        private const val ENVELOPE_TYPE_UNIDENTIFIED_SENDER = 6
        private const val PADDING_GRANULE = 159
        /** Above this many other members, prefer the SenderKey multi-recipient
         *  send (one PUT) over per-member fan-out (N PUTs). */
        private const val SENDERKEY_THRESHOLD = 5
        /** libsignal ContentHint: 1 = RESENDABLE (sender can re-send on
         *  decrypt failure) — what Signal uses for group data messages. */
        private const val CONTENT_HINT_RESENDABLE = 1
        @Suppress("unused")
        private const val CIPHERTEXT_TYPE_PREKEY = CiphertextMessage.PREKEY_TYPE
        @Suppress("unused")
        private const val CIPHERTEXT_TYPE_WHISPER = CiphertextMessage.WHISPER_TYPE
    }
}
