package com.offline.dpadmessenger.ui.util

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * HIT only: turns a video the phone cannot decode into one it can, once, the first
 * time it is opened.
 *
 * The HIT (MT6739, Android 8.1) has no 10-bit HEVC decoder: the MTK hardware one is
 * 8-bit only and so is Android 8.1's software one. iPhones record HDR video as
 * 10-bit HEVC (HLG), so in smart txt those played as sound over a black picture.
 * Here the video is decoded in software by a bundled FFmpeg ([HitVideoNative]),
 * scaled down to fit the screen (long edge 640 px; the screen is 320), re-encoded
 * to plain H.264 with the phone's own hardware encoder, the audio copied across
 * untouched, and the result cached next to the original as `<name>.hit.mp4`.
 * Later opens play the cached file straight away.
 *
 * Not used on any other phone (TCL included): [playablePath] returns the original
 * path untouched there, without even looking at the file.
 */
object HitVideoConvert {
    private const val TAG = "HitVideo"
    private const val LONG_EDGE = 640
    private const val BIT_RATE = 1_200_000
    private const val TIMEOUT_US = 10_000L

    val IS_HIT: Boolean = listOf(Build.MODEL, Build.DEVICE, Build.MANUFACTURER)
        .any { (it ?: "").uppercase().startsWith("HIT") }

    /** One conversion at a time: it uses every core the phone has. */
    private val lock = Mutex()

    /**
     * The path to give the player: [path] itself unless this is a HIT and the
     * video needs converting, in which case the converted copy (made now if it
     * does not exist yet, reporting 0..1 to [onProgress]). Null if it needed
     * converting and that failed.
     */
    suspend fun playablePath(path: String, onProgress: (Float) -> Unit): String? {
        if (!IS_HIT) return path
        return withContext(Dispatchers.IO) {
            val out = File("$path.hit.mp4")
            if (out.length() > 0) return@withContext out.path
            if (!needsConversion(path)) return@withContext path
            if (!HitVideoNative.available) return@withContext null
            lock.withLock {
                if (out.length() > 0) return@withLock out.path
                val tmp = File("$path.hit.tmp")
                tmp.delete()
                onProgress(0f) // tells the viewer a real conversion is starting
                val ok = try {
                    convert(path, tmp, onProgress)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) { tmp.delete(); throw t }
                    Log.e(TAG, "convert failed for $path", t)
                    false
                }
                if (ok && tmp.length() > 0 && tmp.renameTo(out)) out.path
                else { tmp.delete(); null }
            }
        }
    }

    /** True for HEVC with more than 8 bits per sample (iPhone HDR) -- what the HIT can't decode. */
    fun isHighBitDepthHevc(path: String): Boolean {
        if (!isHevc(path) || !HitVideoNative.available) return false
        val err = arrayOfNulls<String>(1)
        val h = HitVideoNative.open(path, 1, true, err)
        if (h == 0L) return false
        return try { HitVideoNative.info(h)[HitVideoNative.INFO_BIT_DEPTH] > 8 } finally { HitVideoNative.close(h) }
    }

    private fun isHevc(path: String): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC }
        } catch (t: Throwable) {
            false
        } finally {
            ex.release()
        }
    }

    /**
     * HEVC the HIT cannot play: more than 8 bits per sample (iPhone HDR), or larger
     * than the 1080p the MTK decoder handles. Everything else plays as it is.
     */
    private fun needsConversion(path: String): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var hevc: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC) { hevc = f; break }
            }
            hevc ?: return false
            val w = hevc.getInteger(MediaFormat.KEY_WIDTH)
            val h = hevc.getInteger(MediaFormat.KEY_HEIGHT)
            if (w.toLong() * h > 1920L * 1088) {
                Log.i(TAG, "$path: HEVC ${w}x$h is over 1080p -> convert")
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "needsConversion: extractor could not read $path", t)
            return false
        } finally {
            ex.release()
        }
        // HEVC within size: the bit depth decides, and only FFmpeg can tell us.
        if (!HitVideoNative.available) return false
        val err = arrayOfNulls<String>(1)
        val h = HitVideoNative.open(path, 1, true, err)
        if (h == 0L) { Log.w(TAG, "needsConversion: ${err[0]}"); return false }
        return try {
            val depth = HitVideoNative.info(h)[HitVideoNative.INFO_BIT_DEPTH]
            Log.i(TAG, "$path: HEVC $depth-bit")
            depth > 8
        } finally {
            HitVideoNative.close(h)
        }
    }

    private fun align16(v: Int): Int = maxOf(16, ((v + 8) / 16) * 16)

    private suspend fun convert(src: String, dst: File, onProgress: (Float) -> Unit): Boolean {
        val started = System.currentTimeMillis()
        val err = arrayOfNulls<String>(1)
        val h = HitVideoNative.open(src, 4, true, err)
        if (h == 0L) { Log.e(TAG, "open failed: ${err[0]}"); return false }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audioEx: MediaExtractor? = null
        try {
            val info = HitVideoNative.info(h)
            val sw = info[HitVideoNative.INFO_WIDTH]
            val sh = info[HitVideoNative.INFO_HEIGHT]
            if (sw <= 0 || sh <= 0) return false
            val durationUs = info[HitVideoNative.INFO_DURATION_MS].toLong() * 1000
            val fps = if (info[HitVideoNative.INFO_FPS_DEN] > 0)
                (info[HitVideoNative.INFO_FPS_NUM].toFloat() / info[HitVideoNative.INFO_FPS_DEN]).roundToInt().coerceIn(1, 30)
            else 30
            val (ow, oh) = if (sw >= sh) align16(LONG_EDGE) to align16(LONG_EDGE * sh / sw)
                           else align16(LONG_EDGE * sw / sh) to align16(LONG_EDGE)
            if (!HitVideoNative.setOutput(h, ow, oh)) return false
            Log.i(TAG, "convert $src: ${sw}x$sh ${info[HitVideoNative.INFO_BIT_DEPTH]}-bit ${fps}fps " +
                "${durationUs / 1000}ms rot ${info[HitVideoNative.INFO_ROTATION]} -> ${ow}x$oh H.264")

            // ---- encoder
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            val formats = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
            val colorFormat = when {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> { Log.e(TAG, "encoder ${enc.name}: no usable colour format in ${formats.toList()}"); return false }
            }
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            Log.i(TAG, "encoder ${enc.name}, colour format $colorFormat")

            // ---- audio source (copied as is)
            var audioTrackIn = -1
            var audioFormat: MediaFormat? = null
            runCatching {
                val ex = MediaExtractor()
                ex.setDataSource(src)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioTrackIn = i; audioFormat = f; break
                    }
                }
                if (audioTrackIn >= 0) { ex.selectTrack(audioTrackIn); audioEx = ex } else ex.release()
            }.onFailure { Log.w(TAG, "no audio copy", it) }

            val mux = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            mux.setOrientationHint(info[HitVideoNative.INFO_ROTATION])
            var videoTrackOut = -1
            var audioTrackOut = -1
            var muxing = false

            // Audio is copied as is, interleaved with the video as it is written.
            val abuf = ByteBuffer.allocateDirect(256 * 1024)
            val ai = MediaCodec.BufferInfo()
            var audioDone = false
            fun pumpAudio(untilUs: Long) {
                val ex = audioEx ?: return
                if (audioTrackOut < 0 || audioDone) return
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) { audioDone = true; return }
                    if (t > untilUs) return
                    val n = ex.readSampleData(abuf, 0)
                    if (n < 0) { audioDone = true; return }
                    ai.set(0, n, t, if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer!!.writeSampleData(audioTrackOut, abuf, ai)
                    ex.advance()
                }
            }

            val frame = ByteBuffer.allocateDirect(ow * oh * 3 / 2).order(ByteOrder.nativeOrder())
            val bi = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPts = -1L
            var frames = 0
            var lastProgress = -1

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        frame.clear()
                        var pts = HitVideoNative.nextFrame(h, frame)
                        if (pts == -2L) Log.w(TAG, "decode error after $frames frames; ending the video there")
                        if (pts < 0) {
                            enc.queueInputBuffer(inIdx, 0, 0, maxOf(lastPts, 0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (pts <= lastPts) pts = lastPts + 1000
                            lastPts = pts
                            val size = fillInput(enc, inIdx, colorFormat, frame, ow, oh)
                            enc.queueInputBuffer(inIdx, 0, size, pts, 0)
                            frames++
                            if (durationUs > 0) {
                                val p = (pts * 100 / durationUs).toInt().coerceIn(0, 99)
                                if (p != lastProgress) { lastProgress = p; onProgress(p / 100f) }
                            }
                        }
                    }
                }
                val outIdx = enc.dequeueOutputBuffer(bi, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackOut = mux.addTrack(enc.outputFormat)
                        audioFormat?.let { af ->
                            audioTrackOut = runCatching { mux.addTrack(af) }
                                .onFailure { Log.w(TAG, "muxer refused audio ${af.getString(MediaFormat.KEY_MIME)}", it) }
                                .getOrDefault(-1)
                        }
                        mux.start()
                        muxing = true
                    }
                    outIdx >= 0 -> {
                        val buf = enc.getOutputBuffer(outIdx)
                        if (buf != null && bi.size > 0 && muxing && (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(bi.offset)
                            buf.limit(bi.offset + bi.size)
                            mux.writeSampleData(videoTrackOut, buf, bi)
                            pumpAudio(bi.presentationTimeUs)
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            if (!muxing || frames == 0) return false

            coroutineContext.ensureActive()
            pumpAudio(Long.MAX_VALUE)
            mux.stop()
            onProgress(1f)
            Log.i(TAG, "converted $frames frames in ${(System.currentTimeMillis() - started) / 1000.0}s -> ${dst.length()} bytes")
            return true
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { audioEx?.release() }
            HitVideoNative.close(h)
        }
    }

    /** Copy one I420 frame into the encoder's input in whatever layout it wants; returns the byte count. */
    private fun fillInput(enc: MediaCodec, idx: Int, colorFormat: Int, frame: ByteBuffer, w: Int, h: Int): Int {
        val ySize = w * h
        val cSize = ySize / 4
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            val image = enc.getInputImage(idx)
            if (image != null) {
                copyToImage(frame, image, w, h)
                return ySize * 3 / 2
            }
            // No Image view: fall through and assume planar (what Flexible reports as on MTK).
        }
        val buf = enc.getInputBuffer(idx) ?: return 0
        buf.clear()
        frame.position(0).limit(ySize)
        buf.put(frame)
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            // NV12: interleave U and V.
            for (i in 0 until cSize) {
                buf.put(frame.get(ySize + i))
                buf.put(frame.get(ySize + cSize + i))
            }
        } else {
            frame.position(ySize).limit(ySize + 2 * cSize)
            buf.put(frame)
        }
        frame.clear()
        return ySize * 3 / 2
    }

    private fun copyToImage(frame: ByteBuffer, image: Image, w: Int, h: Int) {
        val planes = image.planes
        val offsets = intArrayOf(0, w * h, w * h + w * h / 4)
        for (p in 0 until 3) {
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            val plane = planes[p]
            val dst = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val base = offsets[p]
            if (pixelStride == 1) {
                val row = ByteArray(pw)
                for (y in 0 until ph) {
                    frame.position(base + y * pw)
                    frame.get(row)
                    dst.position(y * rowStride)
                    dst.put(row)
                }
            } else {
                for (y in 0 until ph) {
                    val rowStart = y * rowStride
                    val srcRow = base + y * pw
                    for (x in 0 until pw) dst.put(rowStart + x * pixelStride, frame.get(srcRow + x))
                }
            }
        }
        frame.clear()
    }
}
