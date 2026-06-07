package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Factory + process-scoped holder for the [MessageRepository] backing the
 * in-app Signal experience. Mirrors `gmessages/GoogleMessagesRepository`.
 *
 * The real [SignalMessageRepository] and its inbound [SignalChatWebSocket] are
 * a SINGLETON: the messenger Activity and the app-startup hook share one
 * socket — a per-caller instance would open duplicate connections. The socket
 * keeps running when the Activity goes away because the host process is
 * long-lived, so incoming messages still arrive with the messenger UI closed.
 *
 * If there's no linked account (the UI gates on [SignalAccountStore.isPaired]
 * before calling here), [create] falls back to the in-memory mock so the chat
 * UI renders instead of crashing. The mock is never cached.
 */
object SignalRepository {

    @Volatile
    private var instance: MessageRepository? = null

    @Volatile
    private var socket: SignalChatWebSocket? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The shared repository, or null when not linked. Brings up the inbound
     *  chat socket (+ contact-sync request on each open) on first call. */
    @Synchronized
    fun createIfPaired(context: Context): MessageRepository? {
        instance?.let { return it }
        val appContext = context.applicationContext
        val account = SignalAccountStore(appContext).load() ?: return null
        val api = SignalApi(SignalTrust.buildOkHttp(appContext))
        val sender = SignalSender(appContext, account, api)
        val repo = SignalMessageRepository(account, sender)
        val sock = SignalChatWebSocket(appContext, account, repo)
        // Re-request contact sync on every socket open (initial + reconnects),
        // matching SignalBackendFactory.
        sock.onSocketConnected = {
            scope.launch {
                runCatching { sender.sendContactSyncRequest() }
                    .onFailure { Log.w(TAG, "contact sync request failed", it) }
            }
        }
        sock.connect()
        socket = sock
        instance = repo
        return repo
    }

    fun create(context: Context): MessageRepository {
        val repo = createIfPaired(context)
        if (repo == null) {
            Log.w(TAG, "create() called without a linked Signal account — using mock repo")
            return InMemoryMessageRepository.fake()
        }
        return repo
    }

    /** Drop the cached session (e.g. after unlinking) so the next [create]
     *  builds a fresh one. */
    @Synchronized
    fun reset() {
        instance = null
    }

    /** Full logout teardown: close the socket and forget the repo. */
    @Synchronized
    fun shutdown() {
        runCatching { socket?.disconnect() }
        socket = null
        instance = null
    }

    private const val TAG = "SignalRepo"
}
