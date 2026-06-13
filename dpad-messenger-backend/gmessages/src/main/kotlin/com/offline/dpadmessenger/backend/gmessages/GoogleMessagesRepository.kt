package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Factory + process-scoped holder for the [MessageRepository] backing the
 * in-app Google Messages experience (RCS/SMS via the user's primary Android
 * phone, paired through Google's "Messages for web" relay protocol).
 *
 * The real [GoogleMessagesMessageRepository] (and the live
 * [GoogleMessagesSessionClient] inside it) is a SINGLETON: the messenger
 * Activity and the app-startup hook share one long-poll — a per-caller
 * instance would open duplicate connections and double-notify. The session
 * keeps running when the Activity goes away because the launcher process is
 * long-lived, so incoming texts still notify when the messenger UI is closed.
 *
 * If there's no usable pairing (shouldn't happen — the UI gates on
 * [GoogleMessagesAccountStore.isPaired] before calling here), [create]
 * falls back to the in-memory mock so the chat UI renders instead of
 * crashing. The mock is never cached.
 */
object GoogleMessagesRepository {

    @Volatile
    private var instance: MessageRepository? = null

    /** The shared repository, or null when not paired. Starts the session
     *  (long-poll + notifications) on first call after pairing. */
    @Synchronized
    fun createIfPaired(context: Context): MessageRepository? {
        instance?.let { return it }
        val appContext = context.applicationContext
        val store = GoogleMessagesAccountStore(appContext)
        val account = store.load() ?: return null
        val session = GoogleMessagesSessionClient(store, account)
        return GoogleMessagesMessageRepository(session, appContext).also { instance = it }
    }

    fun create(context: Context): MessageRepository {
        val repo = createIfPaired(context)
        if (repo == null) {
            Log.w(TAG, "create() called without a usable pairing — using mock repo")
            return InMemoryMessageRepository.fake()
        }
        return repo
    }

    /** Drop the cached session (e.g. after unpairing) so the next [create]
     *  builds a fresh one. */
    @Synchronized
    fun reset() {
        instance = null
    }

    /**
     * Tear down the live session and forget it. [clearMessages] = true (a manual
     * logout) wipes the cached message history; false (a re-link after the token
     * expired) preserves it so re-pairing restores past conversations.
     */
    @Synchronized
    fun shutdown(clearMessages: Boolean = true) {
        (instance as? GoogleMessagesMessageRepository)?.shutdown(clearCache = clearMessages)
        instance = null
    }

    /** Re-link WITHOUT re-pairing: refresh the token from stored cookies and
     *  resume, keeping the pairing + messages. Returns false if the cookies are
     *  dead (caller should fall back to a full re-pair). */
    suspend fun reauth(): Boolean =
        (instance as? GoogleMessagesMessageRepository)?.reauth() ?: false

    /** Tell the live session the messenger UI went off-screen (Activity
     *  onStop), so notifications resume for the thread that was open. */
    fun notifyUiHidden() {
        (instance as? GoogleMessagesMessageRepository)?.clearActiveRoom()
    }

    /** Observe whether the phone link has expired (session token rejected and
     *  not refreshable). The messenger UI watches this to show a reconnect
     *  prompt. Null when there's no live session (mock / not paired). */
    fun authExpiredFlow(): StateFlow<Boolean>? =
        (instance as? GoogleMessagesMessageRepository)?.authExpired

    /** Why the link expired (cookie invalid vs. token dead), for the reconnect
     *  screen's explanation. Null when there's no live session / not expired. */
    fun authExpiredReasonFlow(): StateFlow<AuthFailureReason?>? =
        (instance as? GoogleMessagesMessageRepository)?.authExpiredReason

    /** Auto-delete-old-messages setting (Settings toggle). Defaults to ON. */
    fun isAutoDeleteEnabled(): Boolean =
        (instance as? GoogleMessagesMessageRepository)?.autoDeleteOldMessages ?: true

    fun setAutoDeleteEnabled(enabled: Boolean) {
        (instance as? GoogleMessagesMessageRepository)?.autoDeleteOldMessages = enabled
    }

    /** Send-read-receipts setting (Settings toggle). Defaults to OFF. */
    fun isReadReceiptsEnabled(): Boolean =
        (instance as? GoogleMessagesMessageRepository)?.sendReadReceipts ?: false

    fun setReadReceiptsEnabled(enabled: Boolean) {
        (instance as? GoogleMessagesMessageRepository)?.sendReadReceipts = enabled
    }

    private const val TAG = "GMRepo"
}
