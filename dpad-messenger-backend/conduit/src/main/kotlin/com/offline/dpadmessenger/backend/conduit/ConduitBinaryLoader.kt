package com.offline.dpadmessenger.backend.conduit

import java.io.File

/**
 * **STUB.** JNI bridge into the embedded Conduit binary. See
 * `docs/CONDUIT_EMBEDDING.md` for the cross-compile recipe.
 *
 * Three native functions to implement on the Rust side:
 *  - `startServer(port: Int, dataDir: String)` — bring Conduit up
 *  - `stopServer()` — graceful shutdown
 *  - `isRunning(): Boolean`
 *
 * The Rust crate doing this would wrap `conduit::serve()` and expose those
 * via #[no_mangle] extern "C" entry points, compiled to .so for the four
 * Android ABIs and dropped in `src/main/jniLibs/<abi>/libconduit_ffi.so`.
 */
object ConduitBinaryLoader {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        // System.loadLibrary("conduit_ffi") — uncomment once the .so ships
        loaded = true
    }

    fun startServer(port: Int, dataDir: File) {
        ensureLoaded()
        // External fun nativeStartServer(port: Int, dataDir: String) — to be wired
        TODO("Phase 4 — see docs/CONDUIT_EMBEDDING.md")
    }

    fun stopServer() {
        TODO("Phase 4")
    }

    fun isRunning(): Boolean = false
}
