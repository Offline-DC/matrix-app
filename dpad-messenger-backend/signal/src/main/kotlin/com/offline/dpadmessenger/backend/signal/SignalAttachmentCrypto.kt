package com.offline.dpadmessenger.backend.signal

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto layer for Signal's attachment format.
 *
 * Wire layout:
 * ```
 *   iv(16) || ciphertext || hmac-sha256(32)
 * ```
 * Key material is 64 bytes:
 *  - bytes [0..32)   = AES-256 key for CBC encryption
 *  - bytes [32..64)  = HMAC-SHA256 key authenticating (iv || ciphertext)
 *
 * The HMAC covers BOTH the IV and the ciphertext. PKCS#5 padding is
 * stripped by the AES decrypt step. The optional [digest] from the
 * AttachmentPointer is a SHA-256 over (iv || ciphertext || mac); we
 * verify it when supplied to catch CDN tampering.
 */
object SignalAttachmentCrypto {

    /**
     * Decrypt an attachment payload using a 64-byte key from an
     * [SignalServiceProtos.AttachmentPointer]. Throws on MAC failure or
     * unexpected layout.
     */
    fun decrypt(
        encrypted: ByteArray,
        key: ByteArray,
        digest: ByteArray? = null,
    ): ByteArray {
        require(key.size == 64) { "attachment key must be 64 bytes (got ${key.size})" }
        require(encrypted.size > IV_LEN + MAC_LEN) {
            "encrypted blob too small (${encrypted.size} bytes)"
        }

        val aesKey = key.copyOfRange(0, 32)
        val macKey = key.copyOfRange(32, 64)

        val macStart = encrypted.size - MAC_LEN
        val ivAndCipher = encrypted.copyOfRange(0, macStart)
        val theirMac = encrypted.copyOfRange(macStart, encrypted.size)

        val ourMac = hmac(macKey, ivAndCipher)
        check(MessageDigest.isEqual(ourMac, theirMac)) { "attachment MAC verification failed" }

        if (digest != null && digest.isNotEmpty()) {
            val ourDigest = MessageDigest.getInstance("SHA-256").digest(encrypted)
            check(MessageDigest.isEqual(ourDigest, digest)) {
                "attachment digest verification failed"
            }
        }

        val iv = ivAndCipher.copyOfRange(0, IV_LEN)
        val ciphertext = ivAndCipher.copyOfRange(IV_LEN, ivAndCipher.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Result of [encrypt]: the on-the-wire blob (`iv || ciphertext || mac`)
     * plus its SHA-256 [digest] (over the whole blob), which the recipient's
     * AttachmentPointer.digest field carries so they can verify the download.
     */
    class Encrypted(val data: ByteArray, val digest: ByteArray)

    /**
     * Encrypt [plaintext] for upload to Signal's CDN, the inverse of
     * [decrypt]. Generates a random 16-byte IV, AES-256-CBC encrypts under
     * `key[0..32)`, HMAC-SHA256s `(iv || ciphertext)` under `key[32..64)`,
     * and returns `iv || ciphertext || mac` together with its SHA-256 digest.
     *
     * [key] must be the same 64-byte layout [decrypt] expects; callers
     * generate it fresh per attachment (it travels to the recipient inside
     * the encrypted DataMessage, never over the CDN).
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray): Encrypted {
        require(key.size == 64) { "attachment key must be 64 bytes (got ${key.size})" }
        val aesKey = key.copyOfRange(0, 32)
        val macKey = key.copyOfRange(32, 64)

        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val ivAndCipher = iv + ciphertext
        val mac = hmac(macKey, ivAndCipher)
        val blob = ivAndCipher + mac
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return Encrypted(blob, digest)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private const val IV_LEN = 16
    private const val MAC_LEN = 32
}
