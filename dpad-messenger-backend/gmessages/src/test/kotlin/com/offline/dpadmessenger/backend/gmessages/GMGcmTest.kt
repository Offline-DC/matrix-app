package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Round-trips the chunked AES-256-GCM media format ([GMGcm.decrypt]) against
 * an independent encrypter built to the same spec (mautrix aesgcm.go), across
 * multiple chunk boundaries.
 */
class GMGcmTest {

    /** Encrypt with the wire format GMGcm.decrypt expects, using a small
     *  chunk size so the test data spans several chunks. */
    private fun encrypt(key: ByteArray, data: ByteArray, log2Chunk: Int): ByteArray {
        val chunkSize = 1 shl log2Chunk
        val plainPerChunk = chunkSize - 28 // 12 nonce + 16 tag
        val out = java.io.ByteArrayOutputStream()
        out.write(0)
        out.write(log2Chunk)
        val rng = SecureRandom()
        var index = 0
        var i = 0
        while (i < data.size) {
            val isLast = i + plainPerChunk >= data.size
            val end = if (isLast) data.size else i + plainPerChunk
            val chunk = data.copyOfRange(i, end)
            val nonce = ByteArray(12).also(rng::nextBytes)
            val aad = byteArrayOf(
                if (isLast) 1 else 0,
                (index ushr 24).toByte(), (index ushr 16).toByte(),
                (index ushr 8).toByte(), index.toByte(),
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val ctTag = cipher.doFinal(chunk)
            out.write(nonce); out.write(ctTag)
            i = end
            index++
        }
        return out.toByteArray()
    }

    @Test
    fun encryptThenDecryptRoundTrips() {
        val key = ByteArray(32) { (it * 3 + 7).toByte() }
        // Includes sizes that span the production 32768-byte chunk boundary.
        for (size in intArrayOf(0, 1, 100, 32740, 32741, 70000)) {
            val plain = ByteArray(size) { (it * 11).toByte() }
            val enc = GMGcm.encrypt(key, plain)
            assertArrayEquals("size=$size", plain, GMGcm.decrypt(key, enc))
        }
    }

    @Test
    fun decryptsMultiChunkMedia() {
        val key = ByteArray(32) { (it * 5 + 1).toByte() }
        for (size in intArrayOf(0, 1, 35, 36, 37, 100, 1000)) {
            val plain = ByteArray(size) { (it * 7).toByte() }
            // log2Chunk = 6 → 64-byte chunks (36 plaintext bytes each).
            val enc = encrypt(key, plain, log2Chunk = 6)
            assertArrayEquals("size=$size", plain, GMGcm.decrypt(key, enc))
        }
    }
}
