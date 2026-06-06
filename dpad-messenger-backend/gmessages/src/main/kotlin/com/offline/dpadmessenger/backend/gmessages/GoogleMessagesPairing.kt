package com.offline.dpadmessenger.backend.gmessages

import android.content.Context

/**
 * Process-scoped holder for the single in-flight [GoogleMessagesPairingClient].
 *
 * Why this exists: pairing spans leaving the app (the user walks to their
 * primary phone, scans the QR, comes back) and the phone can take seconds to
 * push the confirmation. If the client were owned by the Compose tree it
 * would be torn down — and its long-poll cancelled, its QR token discarded —
 * every time the Activity is recreated (fold/unfold, rotation) or the user
 * backgrounds the app to scan. That produced a fresh QR with a new token on
 * every return, so the code the user actually scanned no longer matched the
 * listening connection.
 *
 * Keeping one client at process scope means the QR + its token + the
 * resilient long-poll survive recomposition, Activity recreation, and
 * backgrounding. The client runs its own coroutine scope, independent of any
 * UI lifecycle.
 */
object GoogleMessagesPairing {

    @Volatile
    private var client: GoogleMessagesPairingClient? = null

    /** Get the live pairing client, creating + starting it on first call. */
    @Synchronized
    fun getOrStart(context: Context): GoogleMessagesPairingClient {
        val existing = client
        if (existing != null) return existing
        val created = GoogleMessagesPairingClient(context.applicationContext)
        client = created
        created.start()
        return created
    }

    /** Tear down the current client and forget it, so the next [getOrStart]
     *  begins a fresh pairing attempt (used after success or to retry). */
    @Synchronized
    fun reset() {
        client?.cancel()
        client = null
    }
}
