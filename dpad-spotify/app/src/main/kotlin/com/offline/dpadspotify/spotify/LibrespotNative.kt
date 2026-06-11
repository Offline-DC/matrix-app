package com.offline.dpadspotify.spotify

/**
 * JNI surface of the Rust librespot wrapper (rust/src/lib.rs, built to
 * libdpadspotify.so by cargo-ndk). Pull model: Rust never calls back into the
 * JVM — Kotlin owns an event-pump thread ([pollEvent]) and an audio-pump
 * thread ([readPcm]) instead.
 *
 * Method names and signatures here are part of the JNI contract — renaming
 * anything requires the matching change in lib.rs.
 */
object LibrespotNative {
    init {
        System.loadLibrary("dpadspotify")
    }

    /** Boot the native core: restores stored credentials or starts zeroconf. Idempotent. */
    external fun start(filesDir: String, cacheDir: String, deviceName: String)

    /**
     * Blocking pop of the next event as a JSON string, or null after
     * [timeoutMs]. Events: {"type":"state",...}, {"type":"track",...},
     * "playing", "paused", "loading", "endOfTrack", "stopped", "unavailable".
     */
    external fun pollEvent(timeoutMs: Int): String?

    /**
     * Blocking read of decoded PCM (44.1kHz, stereo, s16le — librespot's
     * fixed output format) into [buffer]. Returns bytes written, 0 on timeout.
     */
    external fun readPcm(buffer: ByteArray, timeoutMs: Int): Int

    external fun playUri(uri: String)
    external fun play()
    external fun pause()
    external fun seekMs(positionMs: Int)

    /** Instant-cut of buffered audio; call before loading a different track. */
    external fun clearPcm()

    /** Current playback position, extrapolated between position events. */
    external fun positionMs(): Int

    /** Blocking; mints a Web API bearer token via the session. Null if not logged in / failed. */
    external fun getToken(): String?

    /** Wipes stored credentials and drops back to zeroconf advertising. */
    external fun logout()
}
