package com.offline.dpadmessenger.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * HEIC (iPhone photos) on a phone with no HEIF image decoder.
 *
 * Android 8.1 (the HIT) can't open HEIC at all: ImageDecoder is API 28 and
 * BitmapFactory never learnt the format, so every picture an iPhone sends
 * downloaded fine and then showed "Can't preview this photo on this device"
 * (tester logs, 2026-10-30). The TCL (newer Android) opens them natively.
 *
 * A HEIC is HEVC *video* frames in an ISO-BMFF wrapper, and this phone does
 * have HEVC video decoders (OMX.MTK.VIDEO.DECODER.HEVC and Google's software
 * one), so: parse the wrapper, pull out the coded frames, hand them to
 * MediaCodec as a short all-keyframe video, and turn the YUV it gives back
 * into a Bitmap.
 *
 * Which item: iPhone HEICs store the full picture as a GRID of tiles (48 tiles
 * of 512x512 for a 12 MP shot) and usually also a ready-made THUMBNAIL item (a
 * single ~320 px frame). 320 px is already as much as the 240x320 panel can
 * show, so the thumbnail is used whenever it is big enough for what the caller
 * asked for. Otherwise (no thumbnail, or a tiny one) the grid is decoded tile
 * by tile and stitched straight into a bitmap already scaled down to the
 * requested size, so the 12 MP picture never exists in memory (48 MB would
 * not fit on this phone). A single-frame primary item is decoded directly.
 *
 * Used only as a fallback after the platform decoders have failed, so the TCL
 * and every JPEG/PNG are untouched. Call off the main thread.
 */
object HeicDecode {
    private const val TAG = "HeicDecode"
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024
    private const val DECODE_TIMEOUT_US = 20_000L
    private const val DECODE_DEADLINE_MS = 8_000L
    private const val PER_FRAME_MS = 250L
    /** Grid decode with no size asked for: never build more than this. */
    private const val GRID_DEFAULT_EDGE = 1024

    /** Cheap sniff: an ISO-BMFF 'ftyp' box with a HEIF brand. */
    fun isHeif(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        val head = ByteArray(32)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 16 || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return false
        val brands = (8 until minOf(n, 32) - 3 step 4).map { String(head, it, 4, Charsets.ISO_8859_1) }
        return brands.any { it in HEIF_BRANDS }
    }

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heim", "heis")

    /** The picture, rotated as the file says it should be shown, at most [maxEdge] on its longer side (<=0: as is). Null if it can't be done. */
    fun decode(file: File, maxEdge: Int): Bitmap? {
        if (!isHeif(file)) return null
        if (file.length() > MAX_FILE_BYTES) { Log.w(TAG, "too big: ${file.length()} bytes"); return null }
        return try {
            val bytes = file.readBytes()
            val heif = Heif.parse(bytes) ?: run { Log.w(TAG, "no usable meta in ${file.name}"); return null }
            val plan = heif.plan(maxEdge)
            if (plan.isEmpty()) { Log.w(TAG, "${file.name}: nothing decodable (items ${heif.items.values.map { it.type }})"); return null }
            for (item in plan) {
                val raw = when (item.type) {
                    "hvc1" -> decodeSingle(heif, item)
                    "grid" -> decodeGrid(heif, item, maxEdge)
                    else -> null
                } ?: continue
                val bmp = finish(raw, item.rotation, maxEdge)
                Log.i(TAG, "${file.name}: decoded item ${item.id} (${item.type}) ${bmp.width}x${bmp.height}")
                return bmp
            }
            Log.w(TAG, "${file.name}: every candidate failed (${plan.map { "${it.id}/${it.type}" }})")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "${file.name}: failed: $t")
            null
        }
    }

    private fun finish(raw: Bitmap, rotation: Int, maxEdge: Int): Bitmap {
        var bmp = raw
        if (rotation != 0) {
            // irot is anti-clockwise; Matrix.postRotate is clockwise.
            val m = Matrix().apply { postRotate(((360 - rotation) % 360).toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        if (maxEdge > 0) {
            val longer = maxOf(bmp.width, bmp.height)
            if (longer > maxEdge) {
                val s = maxEdge.toFloat() / longer
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
        }
        return bmp
    }

    // ------------------------------------------------------------- items

    /** One hvc1 item = one frame. */
    private fun decodeSingle(heif: Heif, item: Item): Bitmap? {
        val frame = heif.itemData(item.id) ?: run { Log.w(TAG, "item ${item.id}: no data"); return null }
        var out: Bitmap? = null
        decodeFrames(item.hvcC, listOf(frame), item.width, item.height) { _, image -> out = yuvToBitmap(image, 1); true }
        return out
    }

    /**
     * A grid item: rows x cols tiles (each an hvc1 item, listed by a 'dimg'
     * reference, row-major), output_width x output_height picture (tiles may
     * run past it on the right/bottom; that part is cropped). All tiles are
     * decoded in one codec session and painted, already scaled, into a bitmap
     * sized for [maxEdge].
     */
    private fun decodeGrid(heif: Heif, grid: Item, maxEdge: Int): Bitmap? {
        val gd = heif.itemData(grid.id) ?: run { Log.w(TAG, "grid ${grid.id}: no descriptor"); return null }
        if (gd.size < 8) return null
        val flags = gd[1].toInt() and 0xFF
        val rows = (gd[2].toInt() and 0xFF) + 1
        val cols = (gd[3].toInt() and 0xFF) + 1
        val outW: Int; val outH: Int
        if (flags and 1 != 0) { if (gd.size < 12) return null; outW = u32(gd, 4); outH = u32(gd, 8) } else { outW = u16(gd, 4); outH = u16(gd, 6) }
        if (outW <= 0 || outH <= 0) return null
        val tileIds = heif.dimg[grid.id] ?: run { Log.w(TAG, "grid ${grid.id}: no dimg"); return null }
        if (tileIds.size != rows * cols) { Log.w(TAG, "grid ${grid.id}: ${tileIds.size} tiles for ${rows}x$cols"); return null }
        val tiles = tileIds.map { heif.items[it] ?: return null }
        val first = tiles[0]
        if (tiles.any { it.type != "hvc1" } || first.hvcC.isEmpty()) return null
        val frames = tiles.map { heif.itemData(it.id) ?: run { Log.w(TAG, "tile ${it.id}: no data"); return null } }

        val target = if (maxEdge > 0) maxEdge else GRID_DEFAULT_EDGE
        val scale = minOf(1f, target.toFloat() / maxOf(outW, outH))
        val dstW = (outW * scale).toInt().coerceAtLeast(1)
        val dstH = (outH * scale).toInt().coerceAtLeast(1)
        // Sample every step-th pixel while converting YUV; drawBitmap does the rest.
        val step = (1f / scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var tileW = first.width
        var tileH = first.height
        var painted = 0
        decodeFrames(first.hvcC, frames, tileW, tileH) { index, image ->
            if (tileW <= 0 || tileH <= 0) { tileW = image.cropRect.width(); tileH = image.cropRect.height() }
            val tile = yuvToBitmap(image, step) ?: return@decodeFrames false
            val r = index / cols
            val c = index % cols
            // Edges snapped to whole pixels and shared between neighbours: no hairline seams.
            val dst = Rect((c * tileW * scale).toInt(), (r * tileH * scale).toInt(), ((c + 1) * tileW * scale).toInt(), ((r + 1) * tileH * scale).toInt())
            canvas.drawBitmap(tile, Rect(0, 0, tile.width, tile.height), dst, paint)
            tile.recycle()
            painted++
            true
        }
        if (painted != frames.size) { Log.w(TAG, "grid ${grid.id}: $painted of ${frames.size} tiles"); out.recycle(); return null }
        return out
    }

    // ------------------------------------------------------------- HEVC via MediaCodec

    /**
     * Push [frames] (length-prefixed NAL units, each a complete intra picture
     * sharing [hvcC]) through the phone's HEVC decoder (hardware first, then
     * Google's software one) and hand every output picture to [onFrame] with
     * its index. Stops early if [onFrame] returns false.
     */
    private fun decodeFrames(hvcC: ByteArray, frames: List<ByteArray>, width: Int, height: Int, onFrame: (Int, Image) -> Boolean) {
        val csd = csdFromHvcC(hvcC) ?: run { Log.w(TAG, "bad hvcC (${hvcC.size} bytes)"); return }
        val lengthSize = (hvcC[21].toInt() and 3) + 1
        val annexB = frames.map { toAnnexB(it, lengthSize) }
        val w = if (width > 0) width else 320
        val h = if (height > 0) height else 240
        for (name in hevcDecoders()) {
            val done = runCatching { decodeWith(name, csd, annexB, w, h, onFrame) }
                .onFailure { Log.w(TAG, "$name: $it") }.getOrDefault(false)
            if (done) return
        }
    }

    private fun hevcDecoders(): List<String> {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }
            .map { it.name }
        // Hardware (OMX.MTK...) first, Google's software decoder as the fallback.
        return all.sortedBy { if (it.startsWith("OMX.google.") || it.startsWith("c2.android.")) 1 else 0 }
    }

    /** True when every frame came out (or [onFrame] asked to stop). */
    private fun decodeWith(name: String, csd: ByteArray, frames: List<ByteArray>, w: Int, h: Int, onFrame: (Int, Image) -> Boolean): Boolean {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (frames.maxOfOrNull { it.size } ?: 0) + csd.size + 1024)
        }
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val deadline = System.currentTimeMillis() + DECODE_DEADLINE_MS + PER_FRAME_MS * frames.size
            var fed = 0
            var eosSent = false
            var got = 0
            val info = MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < deadline) {
                if (!eosSent) {
                    val inIdx = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: throw IllegalStateException("no input buffer")
                        if (fed < frames.size) {
                            val frame = frames[fed]
                            buf.clear(); buf.put(frame)
                            // pts carries the index so tiles can be placed whatever order they come out in
                            codec.queueInputBuffer(inIdx, 0, frame.size, fed * 1_000_000L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            fed++
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val index = (info.presentationTimeUs / 1_000_000L).toInt().coerceIn(0, frames.size - 1)
                        val image = codec.getOutputImage(outIdx)
                        val goOn = image?.let { onFrame(index, it) } ?: false
                        image?.close()
                        codec.releaseOutputBuffer(outIdx, false)
                        got++
                        if (!goOn || got >= frames.size) return true
                    } else {
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            Log.w(TAG, "$name: $got of ${frames.size} frames (deadline)")
            return false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** VPS/SPS/PPS out of hvcC, as an Annex-B byte stream (what csd-0 wants). */
    private fun csdFromHvcC(hvcC: ByteArray): ByteArray? {
        if (hvcC.size < 23) return null
        val out = ByteArrayOutputStream()
        var p = 22
        val numArrays = hvcC[p++].toInt() and 0xFF
        repeat(numArrays) {
            if (p + 3 > hvcC.size) return null
            p++ // array_completeness + NAL unit type
            val numNalus = u16(hvcC, p); p += 2
            repeat(numNalus) {
                if (p + 2 > hvcC.size) return null
                val len = u16(hvcC, p); p += 2
                if (p + len > hvcC.size) return null
                out.write(START_CODE); out.write(hvcC, p, len); p += len
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** Length-prefixed NAL units (as stored in the file) -> start-code delimited. */
    private fun toAnnexB(data: ByteArray, lengthSize: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64)
        var p = 0
        while (p + lengthSize <= data.size) {
            var len = 0L
            repeat(lengthSize) { len = (len shl 8) or (data[p + it].toLong() and 0xFF) }
            p += lengthSize
            if (len <= 0 || p + len > data.size) break
            out.write(START_CODE); out.write(data, p, len.toInt()); p += len.toInt()
        }
        return out.toByteArray()
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * YUV_420_888 (any plane layout) -> ARGB bitmap, cropped to the valid
     * region, keeping every [step]-th pixel (1 = full size).
     */
    private fun yuvToBitmap(image: Image, step: Int): Bitmap? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
        val crop = image.cropRect
        val w = crop.width() / step; val h = crop.height() / step
        if (w <= 0 || h <= 0) return null
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            val sy = crop.top + row * step
            val yRow = sy * yP.rowStride
            val cRow = (sy shr 1) * uP.rowStride
            for (col in 0 until w) {
                val sx = crop.left + col * step
                val y = (yB.get(yRow + sx * yP.pixelStride).toInt() and 0xFF) - 16
                val cIdx = cRow + (sx shr 1) * uP.pixelStride
                val u = (uB.get(cIdx).toInt() and 0xFF) - 128
                val v = (vB.get(cIdx).toInt() and 0xFF) - 128
                val y1 = 1192 * maxOf(y, 0)
                val r = ((y1 + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((y1 - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((y1 + 2066 * u) shr 10).coerceIn(0, 255)
                pixels[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------- HEIF (ISO-BMFF) parsing

    private class Item(val id: Int, val type: String) {
        var hvcC: ByteArray = ByteArray(0)
        var width = 0
        var height = 0
        var rotation = 0 // 0, 90, 180, 270 anti-clockwise (irot)
        val longEdge get() = maxOf(width, height)
    }

    private class Extent(val offset: Long, val length: Long)
    private class Loc(val constructionMethod: Int, val baseOffset: Long, val extents: List<Extent>)

    private class Heif(val bytes: ByteArray) {
        val items = LinkedHashMap<Int, Item>()
        var primary = -1
        val thumbOf = ArrayList<Pair<Int, Int>>()   // (thumbnail item, item it is a thumbnail of)
        val dimg = HashMap<Int, List<Int>>()        // derived item (grid) -> its source tiles, in order
        val props = ArrayList<Pair<String, IntRange>>() // ipco: (type, payload range), 1-based index
        val assoc = HashMap<Int, List<Int>>()
        val locs = HashMap<Int, Loc>()
        var idat: IntRange? = null

        private fun decodable(item: Item?): Item? = item?.takeIf {
            locs.containsKey(it.id) && ((it.type == "hvc1" && it.hvcC.isNotEmpty()) || it.type == "grid")
        }

        /**
         * What to try, in order: the primary item's thumbnail when it is big
         * enough for [maxEdge] (3/4 of it is plenty on this panel), else the
         * primary item itself (single frame or grid), with the thumbnail as
         * the last resort.
         */
        fun plan(maxEdge: Int): List<Item> {
            val main = decodable(items[primary])
            val thumb = thumbOf.firstOrNull { it.second == primary }?.first?.let { decodable(items[it]) }?.takeIf { it.type == "hvc1" }
            val thumbEnough = thumb != null && (maxEdge <= 0 || thumb.longEdge == 0 || thumb.longEdge * 4 >= maxEdge * 3)
            return listOfNotNull(if (thumbEnough) thumb else null, main, if (!thumbEnough) thumb else null).distinct()
        }

        fun itemData(id: Int): ByteArray? {
            val loc = locs[id] ?: return null
            val out = ByteArrayOutputStream()
            for (e in loc.extents) {
                val start: Long = when (loc.constructionMethod) {
                    0 -> loc.baseOffset + e.offset
                    1 -> (idat?.first?.toLong() ?: return null) + loc.baseOffset + e.offset
                    else -> return null
                }
                val limit = if (loc.constructionMethod == 1) (idat!!.last + 1).toLong() else bytes.size.toLong()
                val len = if (e.length == 0L) limit - start else e.length
                if (start < 0 || len <= 0 || start + len > limit) return null
                out.write(bytes, start.toInt(), len.toInt())
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }

        companion object {
            fun parse(bytes: ByteArray): Heif? {
                val h = Heif(bytes)
                var found = false
                forEachBox(bytes, 0, bytes.size) { type, payload, _ ->
                    if (type == "meta") { h.parseMeta(payload); found = true }
                }
                if (!found || h.primary < 0) return null
                h.applyProperties()
                return h
            }
        }

        private fun parseMeta(r: IntRange) {
            val start = r.first + 4 // FullBox: version + flags
            forEachBox(bytes, start, r.last + 1) { type, payload, _ ->
                when (type) {
                    "pitm" -> {
                        val v = bytes[payload.first].toInt()
                        primary = if (v == 0) u16(bytes, payload.first + 4) else u32(bytes, payload.first + 4)
                    }
                    "iinf" -> parseIinf(payload)
                    "iref" -> parseIref(payload)
                    "iprp" -> parseIprp(payload)
                    "iloc" -> parseIloc(payload)
                    "idat" -> idat = payload
                }
            }
        }

        private fun parseIinf(r: IntRange) {
            val v = bytes[r.first].toInt()
            var p = r.first + 4
            p += if (v == 0) 2 else 4 // entry_count
            forEachBox(bytes, p, r.last + 1) { type, payload, _ ->
                if (type != "infe") return@forEachBox
                val iv = bytes[payload.first].toInt()
                var q = payload.first + 4
                val id: Int
                if (iv >= 2) {
                    id = if (iv == 2) { val x = u16(bytes, q); q += 2; x } else { val x = u32(bytes, q); q += 4; x }
                    q += 2 // item_protection_index
                    val itemType = String(bytes, q, 4, Charsets.ISO_8859_1)
                    items[id] = Item(id, itemType)
                }
            }
        }

        private fun parseIref(r: IntRange) {
            val v = bytes[r.first].toInt()
            forEachBox(bytes, r.first + 4, r.last + 1) { type, payload, _ ->
                var q = payload.first
                val from: Int; val count: Int
                if (v == 0) { from = u16(bytes, q); q += 2 } else { from = u32(bytes, q); q += 4 }
                count = u16(bytes, q); q += 2
                val to = ArrayList<Int>()
                repeat(count) { if (v == 0) { to += u16(bytes, q); q += 2 } else { to += u32(bytes, q); q += 4 } }
                when (type) {
                    "thmb" -> to.forEach { thumbOf += from to it }
                    "dimg" -> dimg[from] = to
                }
            }
        }

        private fun parseIprp(r: IntRange) {
            forEachBox(bytes, r.first, r.last + 1) { type, payload, _ ->
                when (type) {
                    "ipco" -> forEachBox(bytes, payload.first, payload.last + 1) { pt, pp, _ -> props += pt to pp }
                    "ipma" -> {
                        val v = bytes[payload.first].toInt()
                        val flags = u32(bytes, payload.first) and 0xFFFFFF
                        var q = payload.first + 4
                        val entryCount = u32(bytes, q); q += 4
                        repeat(entryCount) {
                            val id: Int
                            if (v < 1) { id = u16(bytes, q); q += 2 } else { id = u32(bytes, q); q += 4 }
                            val n = bytes[q].toInt() and 0xFF; q++
                            val list = ArrayList<Int>()
                            repeat(n) {
                                if (flags and 1 == 1) { list += u16(bytes, q) and 0x7FFF; q += 2 }
                                else { list += bytes[q].toInt() and 0x7F; q++ }
                            }
                            assoc[id] = list
                        }
                    }
                }
            }
        }

        private fun parseIloc(r: IntRange) {
            val v = bytes[r.first].toInt()
            var q = r.first + 4
            val b1 = bytes[q].toInt() and 0xFF; val b2 = bytes[q + 1].toInt() and 0xFF; q += 2
            val offsetSize = b1 shr 4; val lengthSize = b1 and 0xF
            val baseOffsetSize = b2 shr 4; val indexSize = if (v == 1 || v == 2) b2 and 0xF else 0
            val itemCount: Int
            if (v < 2) { itemCount = u16(bytes, q); q += 2 } else { itemCount = u32(bytes, q); q += 4 }
            repeat(itemCount) {
                val id: Int
                if (v < 2) { id = u16(bytes, q); q += 2 } else { id = u32(bytes, q); q += 4 }
                var cm = 0
                if (v == 1 || v == 2) { cm = u16(bytes, q) and 0xF; q += 2 }
                q += 2 // data_reference_index
                val base = readSized(q, baseOffsetSize); q += baseOffsetSize
                val extentCount = u16(bytes, q); q += 2
                val extents = ArrayList<Extent>()
                repeat(extentCount) {
                    if ((v == 1 || v == 2) && indexSize > 0) q += indexSize
                    val off = readSized(q, offsetSize); q += offsetSize
                    val len = readSized(q, lengthSize); q += lengthSize
                    extents += Extent(off, len)
                }
                locs[id] = Loc(cm, base, extents)
            }
        }

        private fun readSized(p: Int, size: Int): Long = when (size) {
            0 -> 0L
            4 -> u32(bytes, p).toLong() and 0xFFFFFFFFL
            8 -> u64(bytes, p)
            else -> 0L
        }

        private fun applyProperties() {
            for ((id, item) in items) {
                for (idx in assoc[id] ?: emptyList()) {
                    val (type, range) = props.getOrNull(idx - 1) ?: continue
                    when (type) {
                        "hvcC" -> item.hvcC = bytes.copyOfRange(range.first, range.last + 1)
                        "ispe" -> { item.width = u32(bytes, range.first + 4); item.height = u32(bytes, range.first + 8) }
                        "irot" -> item.rotation = (bytes[range.first].toInt() and 3) * 90
                    }
                }
            }
            // A thumbnail without its own irot shows the way the main picture does.
            for ((thumb, of) in thumbOf) {
                val t = items[thumb] ?: continue
                val o = items[of] ?: continue
                if ((assoc[thumb] ?: emptyList()).none { props.getOrNull(it - 1)?.first == "irot" }) t.rotation = o.rotation
            }
        }
    }

    /** Walk the boxes in [from, to); [body] gets (type, payload range, box range). */
    private inline fun forEachBox(bytes: ByteArray, from: Int, to: Int, body: (String, IntRange, IntRange) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            var size = u32(bytes, p).toLong() and 0xFFFFFFFFL
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { if (p + 16 > to) return; size = u64(bytes, p + 8); header = 16 }
            else if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) return
            val end = (p + size).toInt()
            body(type, (p + header) until end, p until end)
            p = end
        }
    }

    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
    private fun u64(b: ByteArray, p: Int): Long = ((u32(b, p).toLong() and 0xFFFFFFFFL) shl 32) or (u32(b, p + 4).toLong() and 0xFFFFFFFFL)
}
