package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import android.util.Log
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord
import org.whispersystems.signalservice.internal.storage.protos.ReadOperation
import org.whispersystems.signalservice.internal.storage.protos.StorageItems
import org.whispersystems.signalservice.internal.storage.protos.StorageManifest
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Storage Service contact sync — the modern replacement for the old
 * `SyncMessage.Contacts` flow (which primaries no longer answer). Fetches the
 * account's encrypted recipient list and feeds each contact's ACI + E.164 +
 * saved name into [SignalMessageRepository.updateContact], which:
 *   - unifies split PNI/ACI threads (updateContact canonicalizes + merges), and
 *   - shows the name YOU saved for the contact (systemGivenName/systemFamilyName)
 *     instead of their profile name or "Unknown".
 *
 * Crypto lives in [SignalStorageCrypto] (verified end-to-end against Signal's
 * protobufs). Read-only: we fetch + decrypt the manifest and items, and never
 * write anything back.
 */
class SignalStorageService(
    private val account: SignalAccount,
    private val api: SignalApi,
    private val repository: SignalMessageRepository,
) {
    private val login = "${account.aci}.${account.deviceId}"

    suspend fun sync() = withContext(Dispatchers.IO) {
        if (account.accountEntropyPool.isBlank()) {
            Log.d(TAG, "no accountEntropyPool — this device linked before AEP capture; re-link to enable")
            return@withContext
        }
        val masterKey = runCatching { SignalStorageCrypto.masterKeyFromAep(account.accountEntropyPool) }
            .getOrElse { Log.w(TAG, "master key derivation failed", it); return@withContext }
        val storageKey = SignalStorageCrypto.storageKey(masterKey)

        val auth = api.getStorageAuth(login, account.password)
            ?: run { Log.w(TAG, "storage auth failed"); return@withContext }
        val authHeader = "Basic " + Base64.encodeToString(
            "${auth.username}:${auth.password}".toByteArray(), Base64.NO_WRAP,
        )

        val manifestBytes = api.getStorageManifest(authHeader)
            ?: run { Log.d(TAG, "no storage manifest (404/err) — nothing to sync"); return@withContext }

        val manifest = StorageManifest.parseFrom(manifestBytes)
        val manifestRecord = ManifestRecord.parseFrom(
            SignalStorageCrypto.decrypt(
                SignalStorageCrypto.manifestKey(storageKey, manifest.version),
                manifest.value.toByteArray(),
            ),
        )
        // Modern accounts carry a per-manifest recordIkm; older ones derive item
        // keys straight from the storage key. Support both.
        val recordIkm: ByteArray? = manifestRecord.recordIkm.toByteArray().takeIf { it.isNotEmpty() }

        val contactIds = manifestRecord.identifiersList
            .filter { it.type == ManifestRecord.Identifier.Type.CONTACT }
            .map { it.raw.toByteArray() }
        Log.d(TAG, "storage manifest v${manifest.version}: ${contactIds.size} contacts, ikm=${recordIkm != null}")

        var itemsSeen = 0
        var applied = 0
        var decryptFail = 0
        var noContact = 0
        var skipped = 0
        var loggedSkipSample = false
        contactIds.chunked(READ_BATCH).forEach { batch ->
            val op = ReadOperation.newBuilder().apply {
                batch.forEach { addReadKey(ByteString.copyFrom(it)) }
            }.build()
            val itemsBytes = api.readStorageItems(authHeader, op.toByteArray()) ?: return@forEach
            val items = StorageItems.parseFrom(itemsBytes).itemsList
            itemsSeen += items.size
            items.forEach { item ->
                val rawId = item.key.toByteArray()
                val itemKey = if (recordIkm != null) {
                    SignalStorageCrypto.itemKeyFromIkm(recordIkm, rawId)
                } else {
                    SignalStorageCrypto.itemKeyLegacy(storageKey, rawId)
                }
                val record = runCatching {
                    StorageRecord.parseFrom(SignalStorageCrypto.decrypt(itemKey, item.value.toByteArray()))
                }.getOrNull()
                when {
                    record == null -> decryptFail++
                    !record.hasContact() -> noContact++
                    applyContact(record.contact) -> applied++
                    else -> {
                        skipped++
                        if (!loggedSkipSample) {
                            // Field PRESENCE only (no values) — to see why a
                            // contact yields nothing to display.
                            val c = record.contact
                            Log.d(
                                TAG,
                                "skip sample: aciBin=${c.aciBinary.size()} aciStr=${c.aci.isNotBlank()} " +
                                    "pniBin=${c.pniBinary.size()} pniStr=${c.pni.isNotBlank()} " +
                                    "e164=${c.e164.isNotBlank()} sysName=${c.systemGivenName.isNotBlank() || c.systemFamilyName.isNotBlank()} " +
                                    "profName=${c.givenName.isNotBlank() || c.familyName.isNotBlank()}",
                            )
                            loggedSkipSample = true
                        }
                    }
                }
            }
        }
        Log.d(
            TAG,
            "storage sync: manifestContacts=${contactIds.size} itemsReturned=$itemsSeen " +
                "applied=$applied decryptFail=$decryptFail noContact=$noContact skippedNoNameOrAci=$skipped",
        )
        // Dump how every DM thread is keyed AFTER the sync merges — so a
        // submitted rolling log shows whether a contact is unified or still
        // split, and by which ids. (SigRepo:D is allow-listed in the tail.)
        repository.dumpThreadKeys()
    }

    /** Map a ContactRecord onto the repository. Returns true if applied. */
    private fun applyContact(c: ContactRecord): Boolean {
        // Extract BOTH ids the primary knows for this contact. Key by ACI when
        // present; otherwise by PNI (the common case for phone contacts the
        // primary hasn't tied to an ACI yet). Same "PNI:<uuid>" convention the
        // rest of the app uses.
        val aci = when {
            c.aciBinary.size() == 16 -> uuidString(c.aciBinary.toByteArray())
            c.aci.isNotBlank() -> c.aci
            else -> null
        }
        val pni = when {
            c.pniBinary.size() == 16 -> "PNI:" + uuidString(c.pniBinary.toByteArray())
            c.pni.isNotBlank() -> if (c.pni.startsWith("PNI:")) c.pni else "PNI:${c.pni}"
            else -> null
        }
        val serviceId = aci ?: pni ?: return false   // nothing to key the recipient on
        // If the primary has resolved BOTH ids, teach the repo the pairing so a
        // PNI-addressed thread folds into the canonical ACI and later
        // ACI-addressed sent transcripts don't spawn a second "Unknown" chat.
        if (aci != null && pni != null) repository.learnIdentityLink(aci, pni)
        val e164 = c.e164.takeIf { it.isNotBlank() }
        // Name precedence mirrors Signal: your saved system-contact name, then
        // the contact's own profile name, then the phone number.
        val system = listOf(c.systemGivenName, c.systemFamilyName).filter { it.isNotBlank() }.joinToString(" ")
        val profile = listOf(c.givenName, c.familyName).filter { it.isNotBlank() }.joinToString(" ")
        val name = system.ifBlank { profile }.ifBlank { e164 ?: return false }
        repository.updateContact(serviceId = serviceId, name = name, e164 = e164)
        return true
    }

    private fun uuidString(bytes: ByteArray): String {
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long).toString()
    }

    companion object {
        private const val TAG = "SignalStorage"
        /** Record ids per /v1/storage/read call. */
        private const val READ_BATCH = 512
    }
}
