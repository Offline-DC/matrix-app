package com.offline.dpadmessenger.backend.smarttxt

import java.io.ByteArrayOutputStream

/**
 * Repackages the Apple **CAF** files that iMessage voice memos arrive in into a
 * container Android's `MediaPlayer` can open — WITHOUT re-encoding (a container
 * swap only). Android has no CAF support, so without this a received voice memo
 * can't play.
 *
 *  - **Opus** CAF  → **Ogg/Opus** (`.opus`). Playable on Android 10+ (API 29+).
 *    iMessage voice memos are Opus, 24 kHz mono.
 *  - **AAC** CAF   → **ADTS** (`.aac`). Playable on all supported API levels.
 *
 * Returns null (caller saves the raw bytes) when the input isn't CAF or is a codec
 * we can't repackage (e.g. ALAC).
 *
 * CAF layout (multi-byte fields BIG-endian): 8-byte header ("caff" + version +
 * flags), then chunks of [4-byte type][Int64 size][payload]. We read `desc`
 * (sampleRate, formatID, channels, framesPerPacket), `pakt` (per-packet byte sizes
 * as base-128 varints + priming), and `data` (UInt32 editCount then raw packets).
 */
object CafAudio {

    data class Converted(val bytes: ByteArray, val ext: String)

    private val AAC_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350,
    )

    fun isCaf(b: ByteArray): Boolean =
        b.size >= 8 &&
            b[0] == 'c'.code.toByte() && b[1] == 'a'.code.toByte() &&
            b[2] == 'f'.code.toByte() && b[3] == 'f'.code.toByte()

    /** Repackaged audio + file extension, or null if not CAF / unsupported codec. */
    fun convert(caf: ByteArray): Converted? =
        if (!isCaf(caf)) null else runCatching { parse(caf) }.getOrNull()

    // ---- CAF parsing --------------------------------------------------------

    private class Caf {
        var sampleRate = 0
        var formatId = ""
        var channels = 0
        var framesPerPacket = 0
        var primingFrames = 0
        var packetSizes: IntArray? = null
        var dataStart = -1
        var dataLen = -1
    }

    private fun parse(caf: ByteArray): Converted? {
        val c = Caf()
        var pos = 8 // skip file header
        while (pos + 12 <= caf.size) {
            val type = String(caf, pos, 4, Charsets.US_ASCII)
            val size = readI64(caf, pos + 4)
            val body = pos + 12
            when (type) {
                "desc" -> {
                    if (body + 32 > caf.size) return null
                    c.sampleRate = Double.fromBits(readI64(caf, body)).toInt()
                    c.formatId = String(caf, body + 8, 4, Charsets.US_ASCII)
                    c.framesPerPacket = readU32(caf, body + 20)
                    c.channels = readU32(caf, body + 24)
                }
                "pakt" -> {
                    if (body + 24 > caf.size) return null
                    val numPackets = readI64(caf, body).toInt()
                    c.primingFrames = readU32(caf, body + 16)
                    val end = if (size < 0) caf.size else (body + size.toInt()).coerceAtMost(caf.size)
                    c.packetSizes = readPacketTable(caf, body + 24, end, numPackets)
                }
                "data" -> {
                    c.dataStart = body + 4 // skip editCount
                    c.dataLen = if (size < 0) caf.size - c.dataStart else (size.toInt() - 4)
                }
            }
            if (size < 0) break
            pos = body + size.toInt()
        }

        val sizes = c.packetSizes ?: return null
        if (c.dataStart < 0 || c.channels !in 1..8) return null

        return when (c.formatId.trim()) {
            "opus" -> opusToOgg(caf, c, sizes)?.let { Converted(it, "opus") }
            "aac" -> aacToAdts(caf, c, sizes)?.let { Converted(it, "aac") }
            else -> null
        }
    }

    /** CAF packet-table entries: base-128 varints, high bit = "another byte follows". */
    private fun readPacketTable(b: ByteArray, start: Int, end: Int, count: Int): IntArray? {
        if (count <= 0) return null
        val sizes = IntArray(count)
        var i = 0
        var pos = start
        while (i < count && pos < end) {
            var value = 0
            while (pos < end) {
                val byte = b[pos].toInt() and 0xFF
                pos++
                value = (value shl 7) or (byte and 0x7F)
                if (byte and 0x80 == 0) break
            }
            sizes[i++] = value
        }
        return if (i == count) sizes else null
    }

    // ---- Opus → Ogg/Opus ----------------------------------------------------

    private fun opusToOgg(caf: ByteArray, c: Caf, sizes: IntArray): ByteArray? {
        // Opus granule positions are ALWAYS in 48 kHz samples, regardless of the CAF's
        // stated rate. Derive each packet's exact 48 kHz sample count from its TOC byte
        // (opusPacketSamples48k) — NOT from framesPerPacket, whose units differ between
        // encoders (Apple's CAF vs ffmpeg's). Priming is already in 48 kHz samples.
        val preSkip = c.primingFrames.coerceIn(0, 0xFFFF)

        val opusHead = ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray(Charsets.US_ASCII))
            write(1)                       // version
            write(c.channels)              // channel count
            writeU16(this, preSkip)        // pre-skip (48 kHz samples to discard)
            writeU32(this, 48000)          // original input sample rate (informational)
            writeU16(this, 0)              // output gain
            write(0)                       // channel mapping family 0 (mono/stereo)
        }.toByteArray()

        val opusTags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray(Charsets.US_ASCII))
            val vendor = "smarttxt".toByteArray(Charsets.US_ASCII)
            writeU32(this, vendor.size); write(vendor)
            writeU32(this, 0)              // 0 user comments
        }.toByteArray()

        val serial = 0x53545854 // "STXT"
        val out = ByteArrayOutputStream()
        out.write(oggPage(serial, 0, 0x02, 0, opusHead, intArrayOf(opusHead.size)))   // BOS
        out.write(oggPage(serial, 1, 0x00, 0, opusTags, intArrayOf(opusTags.size)))

        var idx = 0
        var cursor = c.dataStart
        val end = (c.dataStart + c.dataLen).coerceAtMost(caf.size)
        var completed = 0L
        var seq = 2
        while (idx < sizes.size) {
            val pageBody = ByteArrayOutputStream()
            val laces = ArrayList<Int>()
            var segCount = 0
            while (idx < sizes.size) {
                val sz = sizes[idx]
                val segs = sz / 255 + 1
                if (segCount + segs > 255) break            // Ogg page cap = 255 segments
                if (sz <= 0 || cursor + sz > end) { idx = sizes.size; break }
                pageBody.write(caf, cursor, sz)
                laces.add(sz)
                segCount += segs
                completed += opusPacketSamples48k(caf, cursor, sz)
                cursor += sz
                idx++
            }
            if (laces.isEmpty()) break
            val last = idx >= sizes.size
            out.write(oggPage(serial, seq++, if (last) 0x04 else 0x00, completed, pageBody.toByteArray(), laces.toIntArray()))
        }
        val bytes = out.toByteArray()
        return bytes.takeIf { it.size > opusHead.size + opusTags.size }
    }

    /** Exact 48 kHz sample count for one Opus packet, from its TOC byte(s). Robust to
     *  encoder differences in how the CAF states framesPerPacket. */
    private fun opusPacketSamples48k(b: ByteArray, off: Int, size: Int): Long {
        if (size < 1) return 960L
        val toc = b[off].toInt() and 0xFF
        val config = toc ushr 3
        val perFrame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 0x3] // SILK 10/20/40/60ms
            config < 16 -> intArrayOf(480, 960)[config and 0x1]             // Hybrid 10/20ms
            else -> intArrayOf(120, 240, 480, 960)[config and 0x3]          // CELT 2.5/5/10/20ms
        }
        val frames = when (toc and 0x3) {
            0 -> 1
            1, 2 -> 2
            else -> if (size >= 2) (b[off + 1].toInt() and 0x3F) else 1     // code 3: M frames
        }
        return perFrame.toLong() * frames.coerceAtLeast(1)
    }

    /** Build one Ogg page (header + lacing + body) with a correct Ogg CRC. */
    private fun oggPage(serial: Int, seq: Int, headerType: Int, granule: Long, body: ByteArray, packetSizes: IntArray): ByteArray {
        val segTable = ArrayList<Int>()
        for (sz in packetSizes) {
            var n = sz
            while (n >= 255) { segTable.add(255); n -= 255 }
            segTable.add(n)
        }
        val page = ByteArray(27 + segTable.size + body.size)
        page[0] = 'O'.code.toByte(); page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte(); page[3] = 'S'.code.toByte()
        page[4] = 0                              // stream version
        page[5] = headerType.toByte()
        writeI64LE(page, 6, granule)
        writeI32LE(page, 14, serial)
        writeI32LE(page, 18, seq)
        // 22..25 CRC left zero for the checksum computation
        page[26] = segTable.size.toByte()
        for (i in segTable.indices) page[27 + i] = segTable[i].toByte()
        System.arraycopy(body, 0, page, 27 + segTable.size, body.size)
        writeI32LE(page, 22, oggCrc(page))
        return page
    }

    // Ogg CRC-32: poly 0x04C11DB7, init 0, no reflection, no final xor.
    private val OGG_CRC = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            t[i] = r
        }
    }
    private fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor OGG_CRC[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    // ---- AAC → ADTS ---------------------------------------------------------

    private fun aacToAdts(caf: ByteArray, c: Caf, sizes: IntArray): ByteArray? {
        val freqIdx = AAC_SAMPLE_RATES.indexOf(c.sampleRate)
        if (freqIdx < 0 || c.channels !in 1..7) return null
        val out = ByteArrayOutputStream(c.dataLen.coerceAtLeast(0) + sizes.size * 7)
        var p = c.dataStart
        val end = (c.dataStart + c.dataLen).coerceAtMost(caf.size)
        for (sz in sizes) {
            if (sz <= 0 || p + sz > end) break
            val frameLen = sz + 7
            out.write(0xFF); out.write(0xF1)
            out.write((1 shl 6) or (freqIdx shl 2) or (c.channels shr 2)) // profile AAC-LC
            out.write(((c.channels and 0x3) shl 6) or (frameLen shr 11))
            out.write((frameLen shr 3) and 0xFF)
            out.write(((frameLen and 0x7) shl 5) or 0x1F)
            out.write(0xFC)
            out.write(caf, p, sz)
            p += sz
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    // ---- byte helpers -------------------------------------------------------

    private fun readI64(b: ByteArray, off: Int): Long {
        var v = 0L; for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF); return v
    }
    private fun readU32(b: ByteArray, off: Int): Int {
        var v = 0; for (i in 0 until 4) v = (v shl 8) or (b[off + i].toInt() and 0xFF); return v
    }
    private fun writeI32LE(b: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
    private fun writeI64LE(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
    private fun writeU16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }
    private fun writeU32(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v ushr 8) and 0xFF); o.write((v ushr 16) and 0xFF); o.write((v ushr 24) and 0xFF)
    }
}
