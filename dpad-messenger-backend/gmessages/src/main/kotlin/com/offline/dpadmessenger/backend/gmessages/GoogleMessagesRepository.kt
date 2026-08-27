package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
        val account = store.load()
        if (account == null) {
            // Either nothing is stored, or the stored account predates the current
            // schema. Both land the user on the sign-in screen, but they are very
            // different bugs — and indistinguishable in a capture without this.
            Log.w(
                SESSION_TAG,
                "createIfPaired: NOT paired — no loadable account " +
                    "(hasCookies=${store.hasCookies()} gaiaMode=${store.isGaiaMode()} " +
                    "linkAge=${store.daysSinceLink() ?: -1}d). User will see sign-in.",
            )
            return null
        }
        // Marks a fresh process taking over the session. Frequent repeats here mean
        // the launcher is being restarted, which resets the in-memory token expiry.
        Log.i(
            SESSION_TAG,
            "createIfPaired: building session for a fresh process " +
                "(gaiaMode=${store.isGaiaMode()} linkAge=${store.daysSinceLink() ?: -1}d " +
                "tokenTtl=${account.tokenTtl})",
        )
        val session = GoogleMessagesSessionClient(store, account)
        return GoogleMessagesMessageRepository(session, appContext).also { instance = it }
    }

    /** Why the last auth attempt failed, so the re-link handler can tell a dead
     *  cookie from a dead network. Null when there's no live session. */
    fun lastAuthFailureReason(): AuthFailureReason? =
        (instance as? GoogleMessagesMessageRepository)?.lastAuthFailureReason

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

    /**
     * Revoke the pairing with Google, tear the session down, and wipe the
     * stored account — in that order, which is the only order that works: the
     * revoke needs both the live session and the credentials the wipe removes.
     *
     * This owns the ORDER on purpose, rather than leaving three statements at
     * each UI call site. It is also why it runs on [teardownScope] under
     * [NonCancellable]:
     *
     * The callers are Compose `rememberCoroutineScope()` lambdas, so their job
     * dies with the composition — and the composition dies the moment the user
     * presses Back on a screen that is sitting on a network call. Cut partway
     * through, the old code could revoke the pairing and cancel the session but
     * never clear the store (the app comes back believing it is still paired,
     * over a session whose scope is cancelled — sends stick on SENDING forever,
     * nothing arrives, and no reconnect screen ever appears), or clear the store
     * against a composition that has already re-read `isPaired()` as true. That
     * is the same "silently not receiving" failure this whole change exists to
     * remove, reached by a new route.
     *
     * Best-effort about the revoke, never about the teardown: a revoke that
     * fails costs one leftover entry in the phone's device list, and the local
     * teardown must happen regardless.
     *
     * @return true only if Google accepted the revoke.
     */
    suspend fun unpairAndTearDown(
        store: GoogleMessagesAccountStore,
        clearMessages: Boolean,
    ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val revoked = runCatching {
            (instance as? GoogleMessagesMessageRepository)?.unpairRemote() ?: false
        }.getOrElse {
            Log.w(SESSION_TAG, "unpair failed — continuing with teardown", it)
            false
        }
        shutdown(clearMessages = clearMessages)
        store.clear()
        _teardowns.value += 1
        revoked
    }

    private val _teardowns = MutableStateFlow(0)

    /**
     * Bumped by every completed [unpairAndTearDown]. The messenger UI collects
     * this to decide when to drop back to the sign-in screen.
     *
     * A flow, not a plain counter the UI samples on resume: teardown is
     * uninterruptible and outlives the composition that started it, so the
     * increment can land while a NEW composition is already showing the chat
     * list — and a sampled counter would miss it until the next resume, leaving
     * the user typing into a wiped store with sends stuck on SENDING.
     *
     * It exists so the UI never has to ask the STORE whether it is still paired
     * in order to sign a user OUT. `isPaired()` is `load() != null`, and
     * `load()` returns null for ANY unreadable field, a transient Keystore/Tink
     * hiccup included. Driving sign-out off that would eject a perfectly good
     * session to the sign-in screen on a bad read — and signing back in is what
     * mints the duplicate pairings this change exists to stop.
     */
    val teardowns: StateFlow<Int> = _teardowns.asStateFlow()


    /**
     * Fire-and-forget rung 3: hand a fresh cookie harvest to the live session so an
     * already-paired device can be repaired by a browser sign-in alone — no QR, no
     * emoji, no new registration.
     *
     * Non-suspend because the caller is the relay callback in the launcher's
     * accessibility service, which has no coroutine scope of its own. No-ops when no
     * session is running (nothing to update — the store write the caller already did
     * is enough, and the next session start will load it).
     */
    fun adoptFreshCookiesAsync(cookies: Map<String, String>) {
        val repo = instance as? GoogleMessagesMessageRepository ?: return
        CoroutineScope(Dispatchers.IO).launch { repo.adoptFreshCookies(cookies) }
    }

    /**
     * Debug Settings row -> "Check pairing now".
     *
     * Runs the real pairing check on demand — Google's registration list AND a live
     * re-assert to the phone — so a broken link can be reproduced in ~30 s instead of
     * waiting out the detector's 11-to-30-minute cadence. Returns a one-line summary for
     * the toast; the full picture is in the log under `GMSession`.
     *
     * Returns a plain message rather than throwing when nothing is paired, because the
     * caller is a button.
     */
    suspend fun checkPairingNow(): String =
        (instance as? GoogleMessagesMessageRepository)?.checkPairingNow()
            ?: "No live Google Messages session — nothing to check"

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

    /** How many days of messages this device keeps (Settings row). 0 is Never. */
    fun autoDeleteDays(): Int =
        (instance as? GoogleMessagesMessageRepository)?.autoDeleteDays
            ?: com.offline.dpadmessenger.data.RetentionSettings.DEFAULT_RETENTION_DAYS

    fun setAutoDeleteDays(days: Int) {
        (instance as? GoogleMessagesMessageRepository)?.autoDeleteDays = days
    }

    /** Send-read-receipts setting (Settings toggle). Defaults to OFF. */
    fun isReadReceiptsEnabled(): Boolean =
        (instance as? GoogleMessagesMessageRepository)?.sendReadReceipts ?: false

    fun setReadReceiptsEnabled(enabled: Boolean) {
        (instance as? GoogleMessagesMessageRepository)?.sendReadReceipts = enabled
    }

    private const val TAG = "GMRepo"

    /** Tag for session-lifecycle lines that support needs in a log bundle.
     *
     *  These deliberately do NOT use [TAG]. `GMRepo` also carries the conversation
     *  dump — contact names, phone numbers — so it must stay below the diagnostics
     *  filterspec's `*:W` floor and out of submitted captures. `GMSession` is
     *  allow-listed to DEBUG and carries only session/auth state. */
    private const val SESSION_TAG = "GMSession"
}
