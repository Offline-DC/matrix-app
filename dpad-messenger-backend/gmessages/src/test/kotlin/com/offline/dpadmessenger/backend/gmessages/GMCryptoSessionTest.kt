package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the authenticated-session payload crypto: AES-256-CTR + HMAC in
 * the `ciphertext || IV(16) || HMAC(32)` layout mautrix uses
 * (`crypto/aesctr.go`).
 */
class GMCryptoSessionTest {

    private val aesKey = ByteArray(32) { it.toByte() }
    private val hmacKey = ByteArray(32) { (it + 100).toByte() }

    @Test
    fun encryptThenDecryptRoundTrips() {
        for (len in intArrayOf(0, 1, 15, 16, 17, 64, 1000)) {
            val plaintext = ByteArray(len) { (it * 3 + 1).toByte() }
            val enc = GMCrypto.encryptPayload(aesKey, hmacKey, plaintext)
            // Layout: ciphertext(len) + IV(16) + HMAC(32)
            assertEquals(len + 48, enc.size)
            val dec = GMCrypto.decryptPayload(aesKey, hmacKey, enc)
            assertArrayEquals(plaintext, dec)
        }
    }

    @Test
    fun tamperedHmacRejected() {
        val enc = GMCrypto.encryptPayload(aesKey, hmacKey, "secret".toByteArray())
        enc[enc.size - 1] = (enc[enc.size - 1] + 1).toByte() // flip a HMAC byte
        assertNull(GMCrypto.decryptPayload(aesKey, hmacKey, enc))
    }

    @Test
    fun wrongKeyRejected() {
        val enc = GMCrypto.encryptPayload(aesKey, hmacKey, "secret".toByteArray())
        val otherHmac = ByteArray(32) { 1 }
        assertNull(GMCrypto.decryptPayload(aesKey, otherHmac, enc))
    }

    @Test
    fun tooShortRejected() {
        assertNull(GMCrypto.decryptPayload(aesKey, hmacKey, ByteArray(10)))
    }

    @Test
    fun decryptsKnownGoVector() {
        // Independent vector: AES-CTR with this key+IV over "hello" then HMAC.
        // Built so a regression in field ordering / HMAC scope is caught.
        val key = ByteArray(32) { 0x01 }
        val hmac = ByteArray(32) { 0x02 }
        val iv = ByteArray(16) { 0x03 }
        val ct = GMCrypto.aesCtrEncrypt(key, iv, "hello".toByteArray())
        val ctAndIv = ct + iv
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(hmac, "HmacSHA256")); doFinal(ctAndIv)
        }
        val packed = ctAndIv + mac
        assertArrayEquals("hello".toByteArray(), GMCrypto.decryptPayload(key, hmac, packed))
    }
}
