package com.offline.dpadmessenger.backend.signal

import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Decodes Signal's `ContactDetails` stream format.
 *
 * The (decrypted) contact-sync blob is a concatenation of length-prefixed
 * [SignalServiceProtos.ContactDetails] proto entries:
 *
 * ```
 *   <varint-len-1><ContactDetails-1>[<avatar-bytes-1>]
 *   <varint-len-2><ContactDetails-2>[<avatar-bytes-2>]
 *   ...
 * ```
 *
 * The length prefix is a Protocol-Buffers base-128 varint giving the size
 * of the ContactDetails proto only — NOT including the avatar bytes that
 * follow. If `contactDetails.avatar.length > 0`, the next exactly that
 * many bytes are the raw avatar payload (un-encrypted; the whole blob is
 * already MAC'd + encrypted at the outer attachment layer). We skip avatar
 * bytes entirely for now since the device's screen can't reasonably
 * render contact thumbnails.
 *
 * Reference: Signal-Android `org.whispersystems.signalservice.api.messages
 * .multidevice.DeviceContactsInputStream::read`.
 */
internal object SignalContactDetailsStream {

    /** Pull every ContactDetails out of [blob], skipping avatar bytes. */
    fun parseAll(blob: ByteArray): List<SignalServiceProtos.ContactDetails> {
        val out = mutableListOf<SignalServiceProtos.ContactDetails>()
        val stream = ByteArrayInputStream(blob)
        while (true) {
            val len = readVarint(stream) ?: break
            if (len <= 0) break
            val bodyBytes = ByteArray(len)
            val read = stream.read(bodyBytes)
            if (read != len) break  // truncated — bail rather than parse garbage
            val details = try {
                SignalServiceProtos.ContactDetails.parseFrom(bodyBytes)
            } catch (t: Throwable) {
                // Single corrupt entry shouldn't kill the whole sync.
                continue
            }
            out += details
            // Skip the avatar bytes that follow this entry. The size lives
            // INSIDE the ContactDetails (`avatar.length`), not in the outer
            // varint prefix — so we only consume them if present.
            val avatarLen = if (details.hasAvatar() && details.avatar.hasLength()) {
                details.avatar.length
            } else 0
            if (avatarLen > 0) {
                val skipped = stream.skipFully(avatarLen.toLong())
                if (skipped != avatarLen.toLong()) break  // truncated
            }
        }
        return out
    }

    /**
     * Read a single protobuf base-128 varint from [s]. Returns null at
     * EOF before any byte is consumed (the legitimate stream-end signal).
     * Throws on a malformed varint (>10 continuation bytes).
     */
    private fun readVarint(s: InputStream): Int? {
        var result = 0
        var shift = 0
        var bytesRead = 0
        while (true) {
            val b = s.read()
            if (b < 0) return if (bytesRead == 0) null else result  // EOF
            bytesRead++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) error("varint too long")  // protobuf caps at 10 bytes
        }
    }

    /**
     * [InputStream.skip] can return early; this loops until we've actually
     * skipped [n] bytes or hit EOF, returning the count actually skipped.
     */
    private fun InputStream.skipFully(n: Long): Long {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // Some streams return 0 even when not at EOF; fall back to read.
                val one = read()
                if (one < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        return n - remaining
    }
}
