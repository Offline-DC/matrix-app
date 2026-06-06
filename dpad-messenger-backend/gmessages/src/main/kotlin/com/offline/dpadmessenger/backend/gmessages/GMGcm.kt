package com.offline.dpadmessenger.backend.gmessages

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked AES-256-GCM used for Google Messages media attachments. Mirrors
 * mautrix `pkg/libgm/crypto/aesgcm.go`.
 *
 * Wire layout of an encrypted attachment:
 *   byte[0] = 0                 (header signature)
 *   byte[1] = log2(chunkSize)   (e.g. 15 → 32768-byte encrypted chunks)
 *   then a sequence of encrypted chunks, each:
 *     nonce(12) || ciphertext || tag(16)
 *   AAD per chunk = [ isLastChunk ? 1 : 0 ][ chunkIndex as big-endian uint32 ].
 *
 * Each chunk holds up to chunkSize bytes of ENCRYPTED data (nonce+ct+tag);
 * the plaintext per chunk is therefore chunkSize − 28 bytes.
 */
internal object GMGcm {

    /** Production encrypted-chunk size: 2^15 bytes (incl. 12-byte nonce +
     *  16-byte tag), matching mautrix `outgoingRawChunkSize`. */
    private const val LOG2_CHUNK = 15

    /** Encrypt for upload — inverse of [decrypt]. */
    fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "media key must be 32 bytes (got ${key.size})" }
        val encChunk = 1 shl LOG2_CHUNK
        val plainPerChunk = encChunk - 28 // 12 nonce + 16 tag
        val out = ByteArrayOutputStream(data.size + 64)
        out.write(0)
        out.write(LOG2_CHUNK)
        if (data.isEmpty()) return out.toByteArray()
        var index = 0
        var i = 0
        while (i < data.size) {
            val isLast = i + plainPerChunk >= data.size
            val end = if (isLast) data.size else i + plainPerChunk
            out.write(encryptChunk(key, data.copyOfRange(i, end), index, isLast))
            i = end
            index++
        }
        return out.toByteArray()
    }

    private fun encryptChunk(key: ByteArray, chunk: ByteArray, index: Int, isLast: Boolean): ByteArray {
        val nonce = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(index, isLast))
        return nonce + cipher.doFinal(chunk)
    }

    private fun aad(index: Int, isLast: Boolean): ByteArray = ByteArray(5).also {
        it[0] = if (isLast) 1 else 0
        it[1] = (index ushr 24).toByte()
        it[2] = (index ushr 16).toByte()
        it[3] = (index ushr 8).toByte()
        it[4] = index.toByte()
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "media key must be 32 bytes (got ${key.size})" }
        require(data.size >= 2 && data[0].toInt() == 0) { "bad media header" }
        // data[1] is a chunk-size shift from the (untrusted) media header. The
        // real protocol uses ~15–20 (32KB–1MB). Bound it so `1 shl shift` can't
        // go negative (shift 31) / overflow or blow up memory on a junk header.
        val shift = data[1].toInt() and 0xFF
        require(shift in 10..24) { "bad media chunk-size shift $shift" }
        val chunkSize = 1 shl shift
        val out = ByteArrayOutputStream(data.size)
        var index = 0
        var pos = 2
        while (pos < data.size) {
            val isLast = pos + chunkSize >= data.size
            val end = if (isLast) data.size else pos + chunkSize
            out.write(decryptChunk(key, data.copyOfRange(pos, end), index, isLast))
            pos = end
            index++
        }
        return out.toByteArray()
    }

    private fun decryptChunk(key: ByteArray, chunk: ByteArray, index: Int, isLast: Boolean): ByteArray {
        require(chunk.size >= 12) { "chunk too short" }
        val nonce = chunk.copyOfRange(0, 12)
        val ctAndTag = chunk.copyOfRange(12, chunk.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(index, isLast))
        return cipher.doFinal(ctAndTag)
    }
}
