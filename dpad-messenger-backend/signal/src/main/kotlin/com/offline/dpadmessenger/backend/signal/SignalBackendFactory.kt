package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.core.BackendConfig
import com.offline.dpadmessenger.backend.core.BackendFactory
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires [SignalMessageRepository] for [BackendConfig.SignalDirect].
 *
 * Returns null when no Signal account is linked yet — the app should
 * surface the link flow (see `app/SignalLinkScreen`).
 */
class SignalBackendFactory : BackendFactory {
    override suspend fun create(context: Context, config: BackendConfig): MessageRepository? {
        if (config !is BackendConfig.SignalDirect) return null
        val account = SignalAccountStore(context).load() ?: return null
        // The sender talks to the same Signal API the link flow used —
        // building a fresh OkHttp here keeps things simple; cert/key
        // caching lives inside SignalSender itself.
        val api = SignalApi(SignalTrust.buildOkHttp(context))
        val sender = SignalSender(context, account, api)
        val repo = SignalMessageRepository(account, sender)
        // Bring up the inbound chat WebSocket so the repo can receive.
        // Re-request contact sync on EVERY socket open (initial + reconnects):
        // if the primary wasn't reachable / didn't respond the first time
        // (offline, backgrounded, rate-limited), a later reconnect retries
        // automatically. The PUT itself is cheap; the primary dedupes if
        // it's already in flight.
        val socket = SignalChatWebSocket(context, account, repo)
        socket.onSocketConnected = {
            backgroundScope.launch {
                Log.d("SignalBackend", "requesting contact sync from primary")
                runCatching { sender.sendContactSyncRequest() }
                    .onFailure { Log.w("SignalBackend", "contact sync request failed", it) }
            }
        }
        socket.connect()
        return repo
    }

    companion object {
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
