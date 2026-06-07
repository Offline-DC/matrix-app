package com.offline.dpadmessenger.backend.signal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-scoped holder for the single in-flight [SignalProvisioningClient].
 *
 * Mirrors `gmessages/GoogleMessagesPairing`: linking spans leaving the app (the
 * user walks to their primary phone, opens Signal, scans the QR, comes back),
 * so the provisioning WebSocket + ephemeral keys must survive Activity
 * recreation and backgrounding. Owning the client at process scope (not in the
 * Compose tree) keeps the QR and its listening socket alive across those
 * lifecycle events.
 */
object SignalPairing {

    @Volatile
    private var client: SignalProvisioningClient? = null

    @Volatile
    private var startJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Get the live provisioning client, creating + starting it on first call.
     *  [SignalProvisioningClient.start] is suspending, so it's kicked off on a
     *  background scope; observe [SignalProvisioningClient.state] for progress. */
    @Synchronized
    fun getOrStart(context: Context): SignalProvisioningClient {
        client?.let { return it }
        val created = SignalProvisioningClient(context.applicationContext)
        client = created
        startJob = scope.launch { runCatching { created.start() } }
        return created
    }

    /** Tear down the current client and forget it, so the next [getOrStart]
     *  begins a fresh link attempt (used after success or to retry). */
    @Synchronized
    fun reset() {
        startJob?.cancel()
        startJob = null
        runCatching { client?.cancel() }
        client = null
    }
}
