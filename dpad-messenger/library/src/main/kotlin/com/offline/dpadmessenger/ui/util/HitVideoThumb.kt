package com.offline.dpadmessenger.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The picture for a video bubble -- the video's first frame -- and its length,
 * for the mockup approved 2026-11-05. Every phone (HIT and TCL).
 *
 * Normally Android's own MediaMetadataRetriever. On the HIT only, an iPhone HDR
 * video (10-bit HEVC), which it cannot decode, takes one frame from the bundled
 * FFmpeg ([HitVideoNative]) or from the converted copy if it already exists.
 * The TCL never touches FFmpeg.
 * The frame is saved next to the video as `<name>.thumb.jpg`, so scrolling past
 * the bubble again never decodes anything twice.
 */
object HitVideoThumb {
    private const val TAG = "HitVideo"
    private const val EDGE = 320

    data class Thumb(val bitmap: Bitmap?, val durationMs: Long)

    private val memory = LruCache<String, Thumb>(24)
    private val lock = Mutex() // one decode at a time; a thread is a dozen bubbles

    fun cached(path: String): Thumb? = memory.get(path)

    suspend fun load(path: String): Thumb = memory.get(path) ?: withContext(Dispatchers.IO) {
        lock.withLock {
            memory.get(path) ?: make(path).also { memory.put(path, it) }
        }
    }

    private fun make(path: String): Thumb {
        val duration = durationOf(path)
        val jpg = File("$path.thumb.jpg")
        if (jpg.length() > 0) {
            BitmapFactory.decodeFile(jpg.path)?.let { return Thumb(it, duration) }
        }
        val converted = File("$path.hit.mp4").takeIf { it.length() > 0 }?.path
        val frame = when {
            !HitVideoConvert.IS_HIT -> retrieverFrame(path)
            converted != null -> retrieverFrame(converted)
            HitVideoConvert.isHighBitDepthHevc(path) -> ffmpegFrame(path)
            else -> retrieverFrame(path)
        }
        if (frame != null) {
            runCatching { jpg.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                .onFailure { Log.w(TAG, "thumb not cached for $path", it) }
        }
        return Thumb(frame, duration)
    }

    private fun durationOf(path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun retrieverFrame(path: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val f = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            downscale(f)
        } catch (t: Throwable) {
            Log.w(TAG, "retriever frame failed for $path", t)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun downscale(b: Bitmap): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= EDGE) return b
        val s = EDGE.toFloat() / long
        val out = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
        if (out !== b) b.recycle()
        return out
    }

    /** First frame through the bundled FFmpeg, as I420 -> ARGB, turned upright. */
    private fun ffmpegFrame(path: String): Bitmap? {
        if (!HitVideoNative.available) return null
        val err = arrayOfNulls<String>(1)
        val h = HitVideoNative.open(path, 2, true, err)
        if (h == 0L) { Log.w(TAG, "thumb open failed: ${err[0]}"); return null }
        try {
            val info = HitVideoNative.info(h)
            val sw = info[HitVideoNative.INFO_WIDTH]
            val sh = info[HitVideoNative.INFO_HEIGHT]
            if (sw <= 0 || sh <= 0) return null
            fun even(v: Int) = maxOf(2, v and 1.inv())
            val (w, hgt) = if (sw >= sh) even(EDGE) to even(EDGE * sh / sw) else even(EDGE * sw / sh) to even(EDGE)
            if (!HitVideoNative.setOutput(h, w, hgt)) return null
            val buf = ByteBuffer.allocateDirect(w * hgt * 3 / 2).order(ByteOrder.nativeOrder())
            if (HitVideoNative.nextFrame(h, buf) < 0) return null
            val bmp = i420ToBitmap(buf, w, hgt)
            val rot = info[HitVideoNative.INFO_ROTATION]
            if (rot == 0) return bmp
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            return Bitmap.createBitmap(bmp, 0, 0, w, hgt, m, true).also { if (it !== bmp) bmp.recycle() }
        } catch (t: Throwable) {
            Log.w(TAG, "thumb decode failed for $path", t)
            return null
        } finally {
            HitVideoNative.close(h)
        }
    }

    /** Limited-range YCbCr (BT.709 matrix) to ARGB. A thumbnail, so no HDR tone mapping. */
    private fun i420ToBitmap(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        val ySize = w * h
        val cw = w / 2
        val px = IntArray(ySize)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val yy = (buf.get(y * w + x).toInt() and 0xff) - 16
                val ci = (y / 2) * cw + x / 2
                val u = (buf.get(ySize + ci).toInt() and 0xff) - 128
                val v = (buf.get(ySize + ySize / 4 + ci).toInt() and 0xff) - 128
                val c = 298 * yy
                val r = (c + 459 * v + 128) shr 8
                val g = (c - 55 * u - 136 * v + 128) shr 8
                val b = (c + 541 * u + 128) shr 8
                px[y * w + x] = (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Debug-hook helper (launcher VideoThumbTest): what each frame source gives for [path]. */
    fun debugInfo(path: String): String {
        val sb = StringBuilder()
        sb.append("highBitDepthHevc=").append(runCatching { HitVideoConvert.isHighBitDepthHevc(path) }.getOrNull())
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val f = runCatching { r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            sb.append(" | retriever: meta ${w}x$h rot $rot, frame ").append(if (f == null) "null" else "${f.width}x${f.height}")
        } catch (t: Throwable) {
            sb.append(" | retriever threw ${t.javaClass.simpleName}")
        } finally {
            runCatching { r.release() }
        }
        if (HitVideoConvert.IS_HIT) {
            val f = runCatching { ffmpegFrame(path) }.getOrNull()
            sb.append(" | ffmpeg frame ").append(if (f == null) "null" else "${f.width}x${f.height}")
        }
        return sb.toString()
    }

    /** "0:19", "1:05", "1:02:03". */
    fun formatDuration(ms: Long): String {
        val s = (ms + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
