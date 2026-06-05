package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import java.util.UUID

/**
 * Implementation of libsignal-android's [SignalProtocolStore] backed by
 * encrypted SharedPreferences. Persists everything Signal needs to
 * establish + maintain E2E sessions:
 *
 *   - Our own identity (loaded from [SignalAccount])
 *   - Trusted peer identities (cached as we see new senders)
 *   - One-time PreKeyRecord, SignedPreKeyRecord, KyberPreKeyRecord blobs
 *   - SessionRecord per (peer-serviceId, peer-deviceId)
 *
 * The interface comes from libsignal — every method here is required by
 * libsignal's session machinery and must be present even if the body is a
 * no-op (e.g. group `sender keys` would be a separate `SenderKeyStore`
 * impl which we deliberately don't ship in this v1 since we're not doing
 * group decrypt yet).
 */
class AndroidSignalProtocolStore(
    context: Context,
    private val account: SignalAccount,
) : SignalProtocolStore {

    private val prefs = SignalProtocolPrefs(context)

    // Backing field for getIdentityKeyPair(). Named explicitly so it doesn't
    // collide with the override's auto-generated JVM accessor.
    private val cachedIdentityKeyPair: IdentityKeyPair by lazy {
        IdentityKeyPair(Base64.decode(account.identityKeyPairBase64, Base64.NO_WRAP))
    }

    // ----- IdentityKeyStore -------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair = cachedIdentityKeyPair

    override fun getLocalRegistrationId(): Int = account.registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        // libsignal 0.8x: return type changed from Boolean to a 3-state enum
        // (NEW_OR_UNCHANGED / REPLACED_DIFFERENT). Map our previous trust-
        // on-first-use behaviour onto it.
        val key = addressKey(address)
        val previous = prefs.getIdentity(key)
        prefs.putIdentity(key, identityKey.serialize())
        return if (previous != null && !previous.contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        // Trust-on-first-use. A safer client surfaces a verification prompt
        // when a peer's identity changes; we just log the new fingerprint
        // and accept it so message flow isn't blocked.
        val stored = prefs.getIdentity(addressKey(address)) ?: return true
        return stored.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val bytes = prefs.getIdentity(addressKey(address)) ?: return null
        return IdentityKey(bytes, 0)
    }

    // ----- PreKeyStore ------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val bytes = prefs.getPreKey(preKeyId)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No PreKey $preKeyId")
        return PreKeyRecord(bytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        prefs.putPreKey(preKeyId, record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean = prefs.hasPreKey(preKeyId)

    override fun removePreKey(preKeyId: Int) {
        prefs.removePreKey(preKeyId)
    }

    // ----- SignedPreKeyStore ------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val bytes = prefs.getSignedPreKey(signedPreKeyId)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No SignedPreKey $signedPreKeyId")
        return SignedPreKeyRecord(bytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        prefs.allSignedPreKeys().values.map { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        prefs.putSignedPreKey(signedPreKeyId, record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        prefs.hasSignedPreKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.removeSignedPreKey(signedPreKeyId)
    }

    // ----- KyberPreKeyStore (post-quantum) ----------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val bytes = prefs.getKyberPreKey(kyberPreKeyId)
            ?: throw org.signal.libsignal.protocol.InvalidKeyIdException("No KyberPreKey $kyberPreKeyId")
        return KyberPreKeyRecord(bytes)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        prefs.allKyberPreKeys().values.map { KyberPreKeyRecord(it) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        prefs.putKyberPreKey(kyberPreKeyId, record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        prefs.hasKyberPreKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        @Suppress("UNUSED_PARAMETER") senderPreKeyId: Int,
        @Suppress("UNUSED_PARAMETER") senderRatchetKey: ECPublicKey,
    ) {
        // libsignal 0.8x changed the signature to track which sender +
        // ratchet key consumed the Kyber prekey (for SPQR replay-defense).
        // "Last-resort" Kyber prekeys (the kind we upload) are not
        // single-use; they stay valid until rotated. No-op as documented
        // in libsignal source.
    }

    // ----- SessionStore -----------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val bytes = prefs.getSession(addressKey(address))
        return if (bytes != null) SessionRecord(bytes) else SessionRecord()
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.mapNotNull { addr ->
            prefs.getSession(addressKey(addr))?.let { SessionRecord(it) }
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        prefs.deviceIdsFor(name).filter { it != account.deviceId }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        prefs.putSession(addressKey(address), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        prefs.hasSession(addressKey(address))

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.removeSession(addressKey(address))
    }

    override fun deleteAllSessions(name: String) {
        prefs.removeAllSessionsFor(name)
    }

    // ----- SenderKeyStore ---------------------------------------------------
    //
    // Persisted to disk (via SignalProtocolPrefs). Signal's primary device
    // uses SenderKey multi-recipient distribution for sync-message fan-out
    // (contact sync, read receipts, blocked-list updates, etc.) — we receive
    // a SenderKeyDistributionMessage first, then one or more SenderKey-
    // encrypted envelopes that decrypt with the stored key. Keeping these
    // in-memory only meant every app restart lost key state, which is bad:
    // the primary doesn't reliably redistribute on its own, and any further
    // SenderKey messages we received in the meantime would fail with
    // `NoSessionException: missing sender key state for distribution ID`.

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        prefs.putSenderKey(senderKey(sender, distributionId), record.serialize())
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? {
        val bytes = prefs.getSenderKey(senderKey(sender, distributionId)) ?: return null
        return SenderKeyRecord(bytes)
    }

    private fun senderKey(addr: SignalProtocolAddress, dist: UUID): String =
        "${addr.name}.${addr.deviceId}.$dist"

    // ----- helpers ----------------------------------------------------------

    private fun addressKey(address: SignalProtocolAddress): String =
        "${address.name}.${address.deviceId}"
}

/** Convenience accessor for libsignal's [Curve]-decoded identity private key. */
internal fun SignalAccount.identityPrivateKey() = identityKeyPair().privateKey
internal fun SignalAccount.identityKeyPair(): IdentityKeyPair =
    IdentityKeyPair(Base64.decode(identityKeyPairBase64, Base64.NO_WRAP))
