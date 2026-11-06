package com.offline.dpadmessenger.ui.util

import android.util.Log
import java.nio.ByteBuffer

/**
 * JNI bridge to `libhitvideo.so`: FFmpeg's HEVC decoder (and nothing else) built
 * for the HIT, plus a tiny C front end. Source and build notes: `native/hitvideo/`
 * in this library module. Only [HitVideoConvert] uses it, and only on the HIT.
 *
 * The libraries are armeabi-v7a only, like every native lib the launcher ships.
 */
internal object HitVideoNative {
    private const val TAG = "HitVideo"

    /** True once the native libraries loaded. Loaded lazily, never on a TCL. */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("hitvideo") }
            .onFailure { Log.e(TAG, "libhitvideo not loadable", it) }
            .isSuccess
    }

    // Indexes into [info]'s array (mirror of hv_core.h).
    const val INFO_WIDTH = 0
    const val INFO_HEIGHT = 1
    const val INFO_BIT_DEPTH = 2
    const val INFO_FPS_NUM = 3
    const val INFO_FPS_DEN = 4
    const val INFO_DURATION_MS = 5
    const val INFO_ROTATION = 6

    /** 0 on failure, with the reason in errOut[0]. */
    @JvmStatic external fun open(path: String, threads: Int, fast: Boolean, errOut: Array<String?>): Long
    @JvmStatic external fun info(handle: Long): IntArray
    @JvmStatic external fun setOutput(handle: Long, width: Int, height: Int): Boolean
    /** Next frame as tightly packed I420 in [dst] (direct buffer). pts in µs, -1 = end, -2 = error. */
    @JvmStatic external fun nextFrame(handle: Long, dst: ByteBuffer): Long
    @JvmStatic external fun close(handle: Long)
}
