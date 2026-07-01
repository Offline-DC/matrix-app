package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Storage Service key derivation + record cipher, transcribed VERBATIM from
 * Signal-Android and verified end-to-end against Signal's protobufs on a real
 * JVM (encrypt→decrypt→parse round-trips for legacy + recordIkm item keys and
 * the manifest). Sources:
 *   - MasterKey.deriveStorageServiceKey / StorageKey.derive{Manifest,Item}Key
 *     (org.signal.core.models[.storageservice])
 *   - RecordIkm.deriveStorageItemKey (api/storage/RecordIkm.kt)
 *   - SignalStorageCipher (api/storage/SignalStorageCipher.kt)
 *
 * Everything here is plain JCE except the AEP→master-key step, which uses
 * libsignal (the same primitive Signal itself uses).
 */
object SignalStorageCrypto {

    private val RNG = SecureRandom()

    /**
     * Account Entropy Pool → 32-byte master key, via libsignal (Signal derives
     * it exactly this way: `AccountEntropyPool(aep).deriveMasterKey()` →
     * `LibSignalAccountEntropyPool.deriveSvrKey(value.lowercase())`).
     */
    fun masterKeyFromAep(aep: String): ByteArray =
        org.signal.libsignal.messagebackup.AccountEntropyPool.deriveSvrKey(aep.lowercase())

    /** master key → Storage Service key = HMAC-SHA256(masterKey, "Storage Service Encryption"). */
    fun storageKey(masterKey: ByteArray): ByteArray =
        hmac(masterKey, "Storage Service Encryption".toByteArray(Charsets.UTF_8))

    /** storage key → manifest key = HMAC-SHA256(storageKey, "Manifest_<version>"). */
    fun manifestKey(storageKey: ByteArray, version: Long): ByteArray =
        hmac(storageKey, "Manifest_$version".toByteArray(Charsets.UTF_8))

    /** Legacy per-item key = HMAC-SHA256(storageKey, "Item_" + base64WithPadding(rawId)). */
    fun itemKeyLegacy(storageKey: ByteArray, rawId: ByteArray): ByteArray =
        hmac(storageKey, ("Item_" + Base64.encodeToString(rawId, Base64.NO_WRAP)).toByteArray(Charsets.UTF_8))

    /**
     * Modern per-item key (when the manifest carries a `recordIkm`):
     * HKDF-SHA256(ikm=recordIkm, info="20240801_SIGNAL_STORAGE_SERVICE_ITEM_" + rawId, L=32).
     */
    fun itemKeyFromIkm(recordIkm: ByteArray, rawId: ByteArray): ByteArray {
        val info = "20240801_SIGNAL_STORAGE_SERVICE_ITEM_".toByteArray(Charsets.UTF_8) + rawId
        return hkdfSha256(ikm = recordIkm, salt = ByteArray(32), info = info, length = 32)
    }

    /** SignalStorageCipher.encrypt: AES/GCM/NoPadding, random 12-byte IV prepended, 128-bit tag. */
    fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH).also { RNG.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    /** SignalStorageCipher.decrypt: split off the 12-byte IV, AES-GCM the remainder. */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ct = blob.copyOfRange(IV_LENGTH, blob.size)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    // --- primitives ---------------------------------------------------------

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC-5869 HKDF-SHA256 (libsignal's no-salt HKDF ⇒ salt of HashLen zeros). */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    private const val IV_LENGTH = 12
}
