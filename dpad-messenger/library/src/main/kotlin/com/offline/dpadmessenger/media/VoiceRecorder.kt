package com.offline.dpadmessenger.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] for recording a voice memo to an
 * AAC-in-MP4 (`.m4a`) file in the app cache. Used by the composer's record
 * button. Not thread-safe — drive it from one coroutine/UI thread.
 */
class VoiceRecorder(context: Context) {

    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null

    /** The file currently being / last recorded to (null before the first start). */
    var outputFile: File? = null
        private set

    /** Begin recording. Returns true if recording actually started. */
    fun start(): Boolean {
        releaseQuietly()
        val dir = File(appContext.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "memo_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            runCatching { rec.release() }
            recorder = null
            runCatching { file.delete() }
            outputFile = null
            false
        }
    }

    /** Stop and finalize. Returns the recorded file, or null if it failed/was
     *  too short to produce valid output. */
    fun stop(): File? {
        val rec = recorder ?: return null
        recorder = null
        return try {
            rec.stop()
            rec.release()
            outputFile?.takeIf { it.exists() && it.length() > 0 }
        } catch (t: Throwable) {
            // stop() throws if the clip was too short (no frames) — discard it.
            Log.w(TAG, "stop failed (likely too short)", t)
            runCatching { rec.release() }
            outputFile?.let { runCatching { it.delete() } }
            outputFile = null
            null
        }
    }

    /** Abort recording and delete the partial file. */
    fun cancel() {
        releaseQuietly()
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
    }

    private fun releaseQuietly() {
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val MIME_TYPE = "audio/mp4"
    }
}
