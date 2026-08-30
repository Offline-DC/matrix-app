package com.offline.dpadmessenger.backend.signal

import android.util.Log
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.UUID

/**
 * Pipeline that turns an inbound `SyncMessage.Contacts` envelope into
 * concrete display-name updates on [SignalMessageRepository].
 *
 * Flow per call:
 *  1. Pull the AttachmentPointer out of the SyncMessage.
 *  2. Download the encrypted blob from `cdnN.signal.org`.
 *  3. AES-CBC + HMAC-SHA256 decrypt with the 64-byte key the primary
 *     embedded inside the AttachmentPointer.
 *  4. Iterate the resulting ContactDetails stream (varint-length-prefixed).
 *  5. For each contact: resolve a stable serviceId (prefer `aci`, fall
 *     back to ACI parsed from `aciBinary`, finally to the phone number)
 *     and push (name, e164) into [SignalMessageRepository.updateContact].
 *
 * Errors are caught + logged per-stage; a partial sync still updates the
 * contacts that did parse cleanly rather than aborting the batch.
 */
class SignalContactSyncHandler(
    private val account: SignalAccount,
    private val api: SignalApi,
    private val repository: SignalMessageRepository,
) {
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

    suspend fun handle(sync: SignalServiceProtos.SyncMessage.Contacts) {
        val pointer = sync.blob
        if (!sync.hasBlob() || !pointer.hasCdnKey() || !pointer.hasKey()) {
            Log.w(TAG, "contact sync missing blob/cdnKey/key — skipping")
            return
        }
        val cdnNumber = if (pointer.hasCdnNumber()) pointer.cdnNumber else 0
        val cdnKey = pointer.cdnKey
        val key = pointer.key.toByteArray()
        val digest = if (pointer.hasDigest()) pointer.digest.toByteArray() else null

        Log.d(TAG, "contact sync: cdn=$cdnNumber key=${cdnKey.take(12)}… size=${if (pointer.hasSize()) pointer.size else -1}")

        val encrypted = try {
            api.downloadAttachment(login, password, cdnNumber, cdnKey)
        } catch (t: Throwable) {
            Log.w(TAG, "contact sync: download failed", t)
            return
        }

        val plaintext = try {
            SignalAttachmentCrypto.decrypt(encrypted, key, digest)
        } catch (t: Throwable) {
            Log.w(TAG, "contact sync: decrypt failed", t)
            return
        }

        val entries = SignalContactDetailsStream.parseAll(plaintext)
        Log.d(TAG, "contact sync: parsed ${entries.size} contacts (${plaintext.size}b plaintext)")

        var applied = 0
        for (cd in entries) {
            val serviceId = resolveServiceId(cd) ?: continue
            val name = preferredName(cd) ?: continue
            val phone = if (cd.hasNumber() && cd.number.isNotBlank()) cd.number else null
            repository.updateContact(
                serviceId = serviceId,
                name = name,
                e164 = phone,
                source = NameSource.CONTACT_SYNC,
            )
            applied++
        }
        Log.d(TAG, "contact sync: applied $applied name updates")
        // Legacy path — modern primaries don't answer SyncMessage.Request
        // {CONTACTS}, so this fires rarely or never. One line when it does,
        // because a name arriving from here rather than Storage Service
        // completely changes where to look.
        SigNames.log("legacy contact sync: parsed ${entries.size} applied $applied")
    }

    /** Prefer the textual ACI; fall back to parsing the binary ACI; last resort phone number. */
    private fun resolveServiceId(cd: SignalServiceProtos.ContactDetails): String? {
        if (cd.hasAci() && cd.aci.isNotBlank()) return cd.aci
        if (cd.hasAciBinary() && cd.aciBinary.size() == 16) {
            return try {
                val bytes = cd.aciBinary.toByteArray()
                val bb = java.nio.ByteBuffer.wrap(bytes)
                UUID(bb.long, bb.long).toString()
            } catch (_: Throwable) {
                null
            }
        }
        return null  // we key rooms by ACI; without one we can't address the room
    }

    /** ContactDetails.name is empty on entries the primary hasn't named yet. */
    private fun preferredName(cd: SignalServiceProtos.ContactDetails): String? {
        if (cd.hasName() && cd.name.isNotBlank()) return cd.name
        if (cd.hasNumber() && cd.number.isNotBlank()) return cd.number
        return null
    }

    companion object {
        private const val TAG = "SignalContactSync"
    }
}
