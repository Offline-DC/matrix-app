package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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
        val groups = SignalGroups(account, api)
        val sender = SignalSender(appContext, account, api, groups)
        val attachments = SignalAttachments(appContext, account, api)
        val store = SignalMessageStore(appContext)
        val profiles = SignalProfiles(account, api)
        val notifier = SignalNotifier(appContext)
        val discovery = SignalContactDiscovery(account, api)
        val repo = SignalMessageRepository(
            account, sender, attachments, groups, store, profiles, notifier, appContext,
            discovery = discovery,
        )
        // Let the sender echo each conversation's disappearing-messages timer.
        sender.conversationTimerLookup = repo::expireTimerFor
        val sock = SignalChatWebSocket(appContext, account, repo)
        // NOTE: we deliberately do NOT request contact sync here. Modern Signal
        // primaries don't answer SyncMessage.Request{CONTACTS}, so the request
        // only hammered the rate-limited /v2/keys/<own-aci> endpoint (HTTP 429),
        // burning budget and occasionally blocking legitimate own-key fetches.
        // Contact names now come from profile fetch (SignalProfiles) instead.
        sock.connect()
        socket = sock
        instance = repo
        return repo
    }

    /** Live "device was unlinked" signal (HTTP 401 on send), or null when not
     *  linked. The Signal app gate observes this to show the re-link prompt. */
    fun authExpiredFlow(): StateFlow<Boolean>? =
        (instance as? SignalMessageRepository)?.authExpired

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
