package com.offline.dpadmessenger.backend.core

import android.content.Context
import com.offline.dpadmessenger.data.MessageRepository

/**
 * Single entry point the demo app calls to obtain a [MessageRepository]
 * for the configured backend.
 *
 * Why a runtime factory rather than DI: it lets the same APK ship multiple
 * backend implementations (mock for dev, Matrix for prod, mock-Matrix for
 * tests) and switch via a settings toggle without recompiling. The actual
 * implementations live in their own modules; this is the only place that
 * has to know they all exist.
 */
interface BackendFactory {
    /**
     * Build a repository for [config]. May return null if the requested
     * backend is unavailable in this build (e.g. Conduit not bundled).
     */
    suspend fun create(context: Context, config: BackendConfig): MessageRepository?
}
