package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import kotlinx.serialization.Serializable
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
// libsignal 0.8x removed the static `Curve` helper class. ECC methods are
// now instance methods on ECKeyPair / ECPublicKey / ECPrivateKey:
//   Curve.generateKeyPair()                  → ECKeyPair.generate()
//   Curve.decodePoint(bytes, 0)              → ECPublicKey(bytes)
//   Curve.decodePrivatePoint(bytes)          → ECPrivateKey(bytes)
//   Curve.calculateSignature(priv, msg)      → priv.calculateSignature(msg)
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import kotlin.random.Random

/**
 * Helpers around libsignal-android key primitives.
 *
 * Each generator returns *both* the persistable [PreKeyRecord] /
 * [SignedPreKeyRecord] / [KyberPreKeyRecord] (with the PRIVATE half —
 * needed by [AndroidSignalProtocolStore] for later decryption) and the
 * server-upload [SignedPreKeyJson] shape (public + signature only).
 *
 * Callers should save the record to the store AND send the JSON to the
 * server in the same atomic step.
 */
object SignalKeys {

    fun deriveIdentityKeyPair(
        publicKeyBytes: ByteArray,
        privateKeyBytes: ByteArray,
    ): IdentityKeyPair {
        val identityPublic = IdentityKey(publicKeyBytes, 0)
        val privateKey = ECPrivateKey(privateKeyBytes)
        return IdentityKeyPair(identityPublic, privateKey)
    }

    /** Curve25519 signed prekey — record + upload JSON. */
    fun generateSignedPreKey(identityKeyPair: IdentityKeyPair, keyId: Int): GeneratedSignedPreKey {
        val keyPair = ECKeyPair.generate()
        val publicSerialized = keyPair.publicKey.serialize()
        val signature = identityKeyPair.privateKey.calculateSignature(publicSerialized)
        val record = SignedPreKeyRecord(keyId, System.currentTimeMillis(), keyPair, signature)
        val json = SignedPreKeyJson(
            keyId = keyId,
            publicKey = base64(publicSerialized),
            signature = base64(signature),
        )
        return GeneratedSignedPreKey(record, json)
    }

    /** Kyber-1024 last-resort prekey — record + upload JSON. */
    fun generateKyberPreKey(identityKeyPair: IdentityKeyPair, keyId: Int): GeneratedKyberPreKey {
        val keyPair: KEMKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val publicSerialized = keyPair.publicKey.serialize()
        val signature = identityKeyPair.privateKey.calculateSignature(publicSerialized)
        val record = KyberPreKeyRecord(keyId, System.currentTimeMillis(), keyPair, signature)
        val json = SignedPreKeyJson(
            keyId = keyId,
            publicKey = base64(publicSerialized),
            signature = base64(signature),
        )
        return GeneratedKyberPreKey(record, json)
    }

    /**
     * Generate a batch of one-time pre-keys. Persist + upload all of them
     * so peers who want to send us their first message have a fresh,
     * one-time key to bind to.
     */
    fun generateOneTimePreKeys(startId: Int, count: Int): List<PreKeyRecord> =
        (0 until count).map { offset ->
            val keyId = ((startId + offset) and 0xFFFFFF).coerceAtLeast(1)
            PreKeyRecord(keyId, ECKeyPair.generate())
        }

    fun generateRegistrationId(): Int = Random.nextInt(1, 16380)
    fun generateKeyId(): Int = Random.nextInt(1, 0xFFFFFF)

    internal fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}

data class GeneratedSignedPreKey(
    val record: SignedPreKeyRecord,
    val json: SignedPreKeyJson,
)

data class GeneratedKyberPreKey(
    val record: KyberPreKeyRecord,
    val json: SignedPreKeyJson,
)

/** Wire format for both signed Curve25519 and Kyber last-resort prekeys. */
@Serializable
data class SignedPreKeyJson(
    val keyId: Int,
    val publicKey: String,
    val signature: String,
)
