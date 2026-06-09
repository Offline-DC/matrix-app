package com.offline.dpadmessenger.backend.signal

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts a Signal profile's encrypted name with the contact's 32-byte
 * profile key (the `profileKey` a sender includes on their messages).
 *
 * Wire layout (Signal's `ProfileCipher`):
 * ```
 *   nonce(12) || ciphertext || gcm-tag(16)
 * ```
 * AES-256-GCM with the profile key as the key. The decrypted plaintext is the
 * padded name: `givenName 0x00 familyName 0x00 …0x00 padding`.
 */
object SignalProfileCipher {

    /** Returns the decrypted "Given Family" display name, or null on failure. */
    fun decryptName(encrypted: ByteArray, profileKey: ByteArray): String? {
        if (profileKey.size != 32) return null
        if (encrypted.size < NONCE_LEN + TAG_LEN) return null
        return try {
            val nonce = encrypted.copyOfRange(0, NONCE_LEN)
            val cipherText = encrypted.copyOfRange(NONCE_LEN, encrypted.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(profileKey, "AES"),
                GCMParameterSpec(TAG_LEN * 8, nonce),
            )
            val plaintext = cipher.doFinal(cipherText)
            parsePaddedName(plaintext)
        } catch (_: Throwable) {
            null
        }
    }

    /** Split `given 0x00 family 0x00 padding…` into a trimmed "Given Family". */
    private fun parsePaddedName(plaintext: ByteArray): String? {
        val firstNull = plaintext.indexOfZero(0).let { if (it < 0) plaintext.size else it }
        val given = String(plaintext.copyOfRange(0, firstNull), Charsets.UTF_8)
        var family = ""
        if (firstNull < plaintext.size) {
            val rest = plaintext.copyOfRange(firstNull + 1, plaintext.size)
            val secondNull = rest.indexOfZero(0).let { if (it < 0) rest.size else it }
            family = String(rest.copyOfRange(0, secondNull), Charsets.UTF_8)
        }
        val name = listOf(given, family).filter { it.isNotBlank() }.joinToString(" ").trim()
        return name.ifBlank { null }
    }

    private fun ByteArray.indexOfZero(from: Int): Int {
        for (i in from until size) if (this[i].toInt() == 0) return i
        return -1
    }

    private const val NONCE_LEN = 12
    private const val TAG_LEN = 16
}
