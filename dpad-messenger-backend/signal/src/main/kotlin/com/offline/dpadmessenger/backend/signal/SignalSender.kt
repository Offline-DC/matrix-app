package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.UUID

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
class SignalSender(
    context: Context,
    private val account: SignalAccount,
    private val api: SignalApi,
    private val protocolStore: AndroidSignalProtocolStore = AndroidSignalProtocolStore(context, account),
) {
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

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
     * Send `body` to a single direct recipient identified by ACI/PNI.
     * Returns the sender-chosen timestamp on success; throws on any
     * non-2xx HTTP response or crypto error.
     */
    suspend fun sendDirectMessage(recipientServiceId: String, body: String): Long {
        val cert = getOrRefreshSenderCertificate()

        // Recipient prekey bundle, served from a short-lived cache. Signal's
        // `/v2/keys/*` is rate-limited account-wide; fetching it on every
        // send rapidly blows the budget when the user sends a few messages
        // in close succession. The bundle is stable across a session unless
        // the recipient adds a device or rotates keys — in either case the
        // `PUT /v1/messages` below returns 409/410 with the mismatched
        // device IDs, at which point we'd invalidate + refresh (TODO).
        val bundle = getRecipientBundleCached(recipientServiceId)
        val identityKey = IdentityKey(Base64.decode(bundle.identityKey, Base64.NO_WRAP), 0)
        bundle.devices.forEach { dev ->
            bootstrapSessionIfNeeded(recipientServiceId, dev, identityKey)
        }
        val devicesToEncryptFor: List<RecipientDevice> =
            bundle.devices.map { RecipientDevice(it.deviceId, it.registrationId) }

        // Build the Content -> DataMessage proto. timestamp is the
        // sender-side message id; Signal echoes it back on the server-side
        // delivery receipt so we can ack the bubble we just sent.
        val timestamp = System.currentTimeMillis()
        val dataMessage = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(body)
            .setTimestamp(timestamp)
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dataMessage)
            .build()
        val padded = padPlaintext(content.toByteArray())

        // Encrypt once per device, then collect into one PUT payload.
        val cipher = SealedSessionCipher(
            protocolStore,
            UUID.fromString(account.aci),
            /* localE164 = */ account.phoneNumber,
            account.deviceId,
        )
        val outgoing = devicesToEncryptFor.map { dev ->
            val destAddress = SignalProtocolAddress(recipientServiceId, dev.deviceId)
            val encrypted = cipher.encrypt(destAddress, cert, padded)
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
            recipientServiceId = recipientServiceId,
            body = SendMessageRequest(
                messages = outgoing,
                timestamp = timestamp,
            ),
        )
        if (!result.isSuccess) {
            throw RuntimeException("send failed HTTP ${result.httpStatus}: ${result.rawBody}")
        }
        Log.d(TAG, "sent to $recipientServiceId (${outgoing.size} device(s)) @ $timestamp")

        // Mirror the message back to our other linked devices (primary
        // phone, desktop, etc.) via a SyncMessage.Sent transcript. Without
        // this, the message lives only on this device + the recipient's
        // device — the primary's chat history would diverge. Failure here
        // shouldn't fail the user-visible send (the message DID go out to
        // the recipient), so we log + swallow.
        runCatching { sendSentTranscript(recipientServiceId, dataMessage, timestamp, cert) }
            .onFailure { Log.w(TAG, "sent-transcript sync failed (recipient still got the message)", it) }

        return timestamp
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

        val sent = SignalServiceProtos.SyncMessage.Sent.newBuilder()
            .setDestinationServiceId(recipientServiceId)
            .setTimestamp(timestamp)
            .setMessage(dataMessage)
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
        Log.d(TAG, "sent-transcript delivered to ${outgoing.size} sibling device(s)")
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

    /**
     * Minimal view of a recipient device we need at encrypt time. We use
     * this instead of [DevicePreKeyBundle] directly because cached/known-
     * session paths don't carry the full prekey bundle around — only the
     * deviceId is required for sealed-sender encrypt. registrationId is
     * informational for the server's mismatched-devices check; sending 0
     * when we don't know it is safe (server only flags a mismatch if the
     * value is non-zero AND differs from the recipient's current value).
     */
    private data class RecipientDevice(val deviceId: Int, val registrationId: Int)

    companion object {
        private const val TAG = "SignalSender"
        private const val CERT_TTL_MS = 12L * 60 * 60 * 1000  // 12 hours
        private const val CONTACT_SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000  // 5 minutes
        private const val OWN_BUNDLE_TTL_MS = 5L * 60 * 1000             // 5 minutes
        private const val RECIPIENT_BUNDLE_TTL_MS = 5L * 60 * 1000       // 5 minutes
        private const val ENVELOPE_TYPE_UNIDENTIFIED_SENDER = 6
        private const val PADDING_GRANULE = 159
        @Suppress("unused")
        private const val CIPHERTEXT_TYPE_PREKEY = CiphertextMessage.PREKEY_TYPE
        @Suppress("unused")
        private const val CIPHERTEXT_TYPE_WHISPER = CiphertextMessage.WHISPER_TYPE
    }
}
