package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.relay.HttpValidationDataRelay
import com.offline.dpadmessenger.backend.smarttxt.relay.StubValidationDataRelay
import com.offline.dpadmessenger.backend.smarttxt.relay.ValidationDataRelay
import com.offline.dpadmessenger.backend.smarttxt.transport.SmartTxtTransport
import com.offline.dpadmessenger.backend.smarttxt.transport.MockRelayTransport
import com.offline.dpadmessenger.backend.smarttxt.transport.NativeRustPushTransport
import com.offline.dpadmessenger.backend.smarttxt.transport.RegisterRequest
import com.offline.dpadmessenger.backend.smarttxt.transport.RegisterResult
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayWebSocketTransport
import com.offline.dpadmessenger.data.MessageRepository
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Factory + process-scoped holder for the SmartTxt backend. Mirror of
 * `GoogleMessagesRepository`.
 *
 * Owns the singleton chain transport → [SmartTxtSession] → repository (a
 * per-caller instance would open duplicate connections and double-notify), the
 * [register]/[renew] entry points the setup screen + renewal worker call, and
 * the [status] the UI gates on.
 *
 * The transport is chosen from [SmartTxtConfig.transportMode]/[relayBaseUrl]:
 *  - a real [RelayWebSocketTransport] when a relay URL is set,
 *  - a [NativeRustPushTransport] for the on-device rustpush path (Phase B),
 *  - otherwise the in-process [MockRelayTransport] so the whole stack runs and
 *    is testable today.
 */
object SmartTxtRepository {

    @Volatile private var transport: SmartTxtTransport? = null
    @Volatile private var session: SmartTxtSession? = null
    @Volatile private var instance: MessageRepository? = null
    @Volatile private var bridge: RustPushBridge? = null
    @Volatile private var nativeInited = false

    private val _status = MutableStateFlow(SmartTxtStatus.UNREGISTERED)
    val status: StateFlow<SmartTxtStatus> = _status.asStateFlow()

    /** Non-null when the NATIVE transport couldn't start (native library missing,
     *  or relay/init failure). The UI shows this error instead of silently falling
     *  back to the mock demo transport. */
    private val _nativeError = MutableStateFlow<String?>(null)
    val nativeError: StateFlow<String?> = _nativeError.asStateFlow()

    fun clearNativeError() { _nativeError.value = null }

    /** Validation-data relay for the NATIVE path only (the WebSocket relay does
     *  validation server-side). */
    private fun buildValidationRelay(): ValidationDataRelay {
        val base = SmartTxtConfig.validationRelayBaseUrl
        return if (base.isNotBlank()) HttpValidationDataRelay(base, SmartTxtConfig.validationRelayAuthToken)
        else StubValidationDataRelay()
    }

    @Synchronized
    fun bridge(): RustPushBridge = bridge ?: RustPushBridge(buildValidationRelay()).also { bridge = it }

    /** Initialise the native rustpush runtime ONCE. Validation runs exclusively
     *  through MacOSConfigRemote (the NAC server) using the migrated OpenBubbles
     *  identity + dumb file — the hardware relay has been removed, so nativeInit
     *  fails if os_config.plist / dumb aren't present. The host/code args are
     *  ignored by the native layer (kept only for signature compatibility). No-op
     *  unless `libsmarttxt_ffi.so` loaded. Must run before
     *  nativeConnect/authenticate/register. */
    @Synchronized
    private fun ensureNativeInit(appContext: Context): Boolean {
        if (nativeInited) return true
        if (!RustPushBridge.NATIVE_AVAILABLE) return false
        // host/code are IGNORED by the native layer (the relay has been removed —
        // validation runs through MacOSConfigRemote/NAC). Passed only to satisfy the
        // existing JNI signature.
        nativeInited = RustPushNative.nativeInit(appContext.filesDir.absolutePath, "", "")
        Log.i(TAG, "nativeInit(MacOSConfigRemote/NAC) = $nativeInited")
        return nativeInited
    }

    /** Build the active transport from config. Relay URL set ⇒ real relay;
     *  NATIVE mode ⇒ rustpush bridge; else the in-process mock. */
    @Synchronized
    private fun transport(): SmartTxtTransport = transport ?: run {
        val mode = when {
            SmartTxtConfig.relayBaseUrl.isNotBlank() -> SmartTxtConfig.TransportMode.RELAY
            else -> SmartTxtConfig.transportMode
        }
        val t: SmartTxtTransport = when (mode) {
            SmartTxtConfig.TransportMode.RELAY ->
                RelayWebSocketTransport(SmartTxtConfig.relayBaseUrl, SmartTxtConfig.relayAuthToken)
            SmartTxtConfig.TransportMode.NATIVE ->
                // Never fall back to the mock here: create() guards NATIVE
                // availability + init and surfaces an error instead.
                NativeRustPushTransport(bridge())
            SmartTxtConfig.TransportMode.MOCK -> MockRelayTransport()
        }
        Log.i(TAG, "transport = ${t::class.java.simpleName} (mode=$mode)")
        t.also { transport = it }
    }

    @Synchronized
    fun session(): SmartTxtSession = session ?: SmartTxtSession(transport()).also { session = it }

    /**
     * The shared repository. Builds the transport+session on first call and
     * connects. Returns the chat repository regardless of registration state —
     * the UI gates the chat behind [status].
     */
    /** Restore [status] from persisted state on a fresh process. This object is a
     *  process-scoped singleton, so [_status] resets to UNREGISTERED on every cold
     *  start and nothing re-reads the store until [create] — which the UI only calls
     *  once it's ALREADY REGISTERED. Without this a signed-in user is bounced back
     *  to the setup screen on relaunch. Call this early (the entry composable). */
    fun restoreStatus(context: Context) {
        if (_status.value != SmartTxtStatus.UNREGISTERED) return
        val appContext = context.applicationContext
        val store = SmartTxtAccountStore(appContext)
        _status.value = when {
            store.isRegistered() -> SmartTxtStatus.REGISTERED
            store.isSeeded() -> SmartTxtStatus.SEEDED
            else -> SmartTxtStatus.UNREGISTERED
        }
        Log.i(TAG, "restoreStatus → ${_status.value}")

        // CHECK REGISTRATION HEALTH ON EVERY COLD START. There used to be a 12h
        // WorkManager job here re-running the whole register flow; it duplicated
        // rustpush's own re-registration timer (IdentityResource::schedule_rereg,
        // on Apple's real ~45-day cadence with a 5min-to-24h backoff) and could
        // never actually succeed, because nativeRegister needs the in-memory
        // AppleAccount that only exists right after an interactive sign-in.
        // rustpush owns the schedule now; all this does is ASK for the state, and
        // the only answer it acts on is a terminal one. The field case that
        // motivated the old worker — a handset overdue by 3.7 days
        // ("Reregistering in -323914 seconds") that recovered on relaunch — was
        // rustpush's in-process timer being dead, which a launch-time client
        // liveness check (healIfUnhealthy) addresses directly.
        if (_status.value == SmartTxtStatus.REGISTERED) {
            // OFF THE MAIN THREAD. This function is documented as safe to call from
            // the entry composable, so it can land on the UI thread — and both calls
            // below touch disk (an EncryptedSharedPreferences decrypt + JSON parse in
            // lastRegisteredMs, and a JNI hop in checkRegistrationHealth). Individually
            // cheap, but this runs on every launch on every handset and the store is
            // keystore-backed crypto, so it must never sit on the UI thread.
            Thread {
                runCatching {
                    logRegistrationAge(store.lastRegisteredMs())
                    checkRegistrationHealth(appContext)
                }.onFailure { Log.w(TAG, "launch registration check failed: ${it.message}") }
            }.start()
        }
    }

    /**
     * The one registration outcome the app must act on: rustpush has given up.
     *
     * Reached two ways — the live push path (rustpush's `resource_state` watch
     * channel → `TransportEvent.RegistrationFailed`) and the cold-start pull
     * ([checkRegistrationHealth]). Both funnel here so there is exactly ONE
     * user-visible outcome for the condition.
     *
     * A terminal failure is an IDS 6005: Apple invalidated the registration and
     * wants a real sign-in. Deliberately NOT the reconnect screen — that offers
     * `reauth()`, which cannot fix a 6005. Tear down the session but keep history,
     * and flip [status] to UNREGISTERED so the sign-in screen shows. This is what
     * OpenBubbles does with `markFailedToLogin`.
     */
    fun onTerminalRegistrationFailure(context: Context, error: String) {
        Log.w(
            TAG,
            "REGSTATE FAILED TERMINAL (needs_relogin): $error — Apple invalidated this " +
                "registration. NOT retrying (rustpush marked it DoNotRetry); routing the " +
                "user to sign in again, keeping history.",
        )
        val message = "Apple signed this device out of iMessage. Sign in again to keep sending."
        _nativeError.value = message
        // PERSIST before tearing down. signOutKeepingHistory only flips an in-memory
        // StateFlow; the cold-start gate is isRegistered(), which reads lastRegisteredMs
        // off disk. Without this the sign-out silently undoes itself on the next process
        // death and the user lands back in a chat UI whose registration Apple has already
        // rejected - re-presenting it on every launch thereafter.
        runCatching { SmartTxtAccountStore(context.applicationContext).markTerminalFailure(message) }
            .onFailure { Log.e(TAG, "could not persist the terminal failure: $it") }
        signOutKeepingHistory(context)
    }

    /**
     * Ask rustpush what the IDS registration is actually doing, and act on the
     * ONE answer that needs us: a terminal failure.
     *
     * rustpush owns all retry — Apple's ~45-day re-registration cadence, plus a
     * 5-minute-to-24-hour exponential backoff with unlimited retries for
     * anything transient. We deliberately do NOT retry anything here. The single
     * case it refuses to retry is an IDS 6005, which it reports as
     * `needs_relogin` (a `Failed` state with no `retry_wait`), because Apple has
     * invalidated the registration and wants a real sign-in.
     *
     * On that signal we tear the session down but KEEP history
     * ([signOutKeepingHistory]) and flip [status] to UNREGISTERED, which is what
     * routes the UI back to the sign-in screen. This mirrors OpenBubbles'
     * `markFailedToLogin`. It replaces an earlier native auto-recover that
     * silently re-logged-in from a stored password hash on every 6005 with no
     * backoff or cap — the exact pattern that gets an Apple ID rate-limited.
     *
     * Blocking (one JNI call); callers must be off the main thread.
     */
    fun checkRegistrationHealth(context: Context) {
        val raw = RustPushNative.runCatchingNativeRegisterState()
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            Log.w(TAG, "REGSTATE unparseable: $raw")
            return
        }
        when (val state = obj.optString("state", "unknown")) {
            "registered" -> Log.i(TAG, "REGSTATE registered; next re-register in ${obj.optLong("next_s", -1L)}s")
            "registering" -> Log.i(TAG, "REGSTATE registering (rustpush is working on it)")
            "no_client", "unknown" -> Log.i(TAG, "REGSTATE $state — no signal, leaving status alone")
            "failed" -> {
                val error = obj.optString("error", "unknown error")
                if (obj.optBoolean("needs_relogin", false)) {
                    onTerminalRegistrationFailure(context, error)
                } else {
                    // Transient: rustpush is already backing off and WILL retry. Do nothing.
                    Log.i(
                        TAG,
                        "REGSTATE failed but retryable — rustpush retries in " +
                            "${obj.optLong("retry_wait", -1L)}s: $error. Not intervening.",
                    )
                }
            }
            else -> Log.w(TAG, "REGSTATE unrecognised state '$state': $raw")
        }
    }

    /** One greppable line with the registration age — `adb logcat -s IMsgRepoHolder`
     *  tells you instantly whether a handset is drifting, no backend required. */
    private fun logRegistrationAge(at: Long) {
        if (at <= 0L) {
            Log.i(TAG, "registration age: never registered")
            return
        }
        val ageMs = System.currentTimeMillis() - at
        Log.i(
            TAG,
            "registration age: ${ageMs / DAY_MS}d ${(ageMs % DAY_MS) / HOUR_MS}h " +
                "(lastRegisteredMs=$at; rustpush owns the real re-register schedule)",
        )
    }

    @Synchronized
    fun create(context: Context): MessageRepository? {
        instance?.let { return it }
        val appContext = context.applicationContext
        // The real path is NATIVE. Require the engine + a successful init and NEVER
        // fall back to the mock demo transport — surface the reason instead.
        val mode = if (SmartTxtConfig.relayBaseUrl.isNotBlank()) SmartTxtConfig.TransportMode.RELAY
            else SmartTxtConfig.transportMode
        if (mode == SmartTxtConfig.TransportMode.NATIVE) {
            if (!RustPushBridge.NATIVE_AVAILABLE) {
                _nativeError.value = "iMessage engine isn't available on this device (the native library failed to load)."
                Log.e(TAG, "create: native library not available")
                return null
            }
            if (!ensureNativeInit(appContext)) {
                _nativeError.value = IDENTITY_MISSING_MESSAGE
                // The native lib is confirmed loaded above, so this IS the identity: the
                // dumb is missing, empty, or unreadable. smarttxt_ffi logs which (and the
                // file's owner — uid 0 means root put it there and the app can't read it).
                Log.e(TAG, "create: nativeInit failed — dumb missing/empty/unreadable (see smarttxt_ffi log)")
                return null
            }
        } else {
            ensureNativeInit(appContext)
        }
        _nativeError.value = null
        // One-time purge of the demo/mock chats that earlier MOCK runs persisted to
        // the on-disk cache, so the real (native) path starts blank. Guarded by a
        // flag so real message history isn't wiped on later launches.
        if (RustPushBridge.NATIVE_AVAILABLE) {
            val flags = appContext.getSharedPreferences(DEMO_PURGE_PREFS, Context.MODE_PRIVATE)
            if (!flags.getBoolean(DEMO_PURGE_KEY, false)) {
                SmartTxtStore(appContext).clear()
                flags.edit().putBoolean(DEMO_PURGE_KEY, true).apply()
                Log.i(TAG, "purged leftover demo chat cache (first native run)")
            }
        }
        val store = SmartTxtAccountStore(appContext)
        _status.value = when {
            store.isRegistered() -> SmartTxtStatus.REGISTERED
            store.isSeeded() -> SmartTxtStatus.SEEDED
            else -> SmartTxtStatus.UNREGISTERED
        }
        return SmartTxtMessageRepository(session(), appContext).also { instance = it }
    }

    /** Observe whether the link died (so the UI can show a reconnect prompt). */
    fun authExpiredFlow(): StateFlow<Boolean>? =
        (instance as? SmartTxtMessageRepository)?.authExpired

    /**
     * Register this identity with Apple via the active transport (the relay
     * obtains validation data + runs IDS registration; the native path runs the
     * §2.2 four-call sequence on-device). Persists the dumb file + account,
     * stamps it registered, schedules renewal, brings up the background
     * connection, and flips [status] to REGISTERED.
     */
    suspend fun register(context: Context, config: MacOSConfig, appleId: String): RegistrationResult {
        val appContext = context.applicationContext
        if (!ensureNativeInit(appContext)) {
            Log.e(TAG, "register: nativeInit failed — " +
                if (!RustPushBridge.NATIVE_AVAILABLE) "native library not loaded"
                else "the dumb is missing/empty/unreadable (see smarttxt_ffi log for which)")
            return RegistrationResult.Failure(initFailureMessage())
        }
        val store = SmartTxtAccountStore(appContext)
        store.saveConfig(config)
        _status.value = SmartTxtStatus.REGISTERING
        return finishRegister(appContext, store, appleId, session().register(config, appleId))
    }

    /**
     * Rich registration with an Apple ID password and interactive 2FA. Same
     * persistence/status contract as the 3-arg [register]; the password is
     * transient — it is passed into the [RegisterRequest] for the auth
     * round-trip and never persisted.
     */
    suspend fun register(
        context: Context,
        config: MacOSConfig,
        appleId: String,
        password: String,
        twoFactorProvider: (suspend () -> String?)? = null,
        fsaProvider: (suspend (FsaChallenge) -> FsaResponse?)? = null,
    ): RegistrationResult {
        val appContext = context.applicationContext
        if (!ensureNativeInit(appContext)) {
            Log.e(TAG, "register: nativeInit failed — " +
                if (!RustPushBridge.NATIVE_AVAILABLE) "native library not loaded"
                else "the dumb is missing/empty/unreadable (see smarttxt_ffi log for which)")
            return RegistrationResult.Failure(initFailureMessage())
        }
        val store = SmartTxtAccountStore(appContext)
        store.saveConfig(config)
        _status.value = SmartTxtStatus.REGISTERING
        val request = RegisterRequest(
            config = config,
            appleId = appleId,
            password = password,
            twoFactorProvider = twoFactorProvider,
            fsaProvider = fsaProvider,
        )
        return finishRegister(appContext, store, appleId, session().register(request))
    }

    /** Shared post-register persistence for both [register] overloads: on
     *  success save the account, stamp it registered, schedule renewal, bring up
     *  the background connection, and flip [status]; on failure roll [status] back. */
    private fun finishRegister(
        appContext: Context,
        store: SmartTxtAccountStore,
        appleId: String,
        result: RegisterResult,
    ): RegistrationResult = when (result) {
        is RegisterResult.Success -> {
            val account = SmartTxtAccount(
                appleId = appleId,
                identityTokenB64 = "",
                pushTokenB64 = "",
                lastRegisteredMs = System.currentTimeMillis(),
                handles = result.handles,
            )
            store.saveAccount(account)
            store.markRegistered(account.lastRegisteredMs)
            connectInBackground(appContext)
            _status.value = SmartTxtStatus.REGISTERED
            RegistrationResult.Success(account)
        }
        is RegisterResult.Failure -> {
            _status.value = SmartTxtStatus.UNREGISTERED
            Log.w(TAG, "registration failed: ${result.message}")
            RegistrationResult.Failure(result.message)
        }
    }

    /** Manually force a re-registration NOW — the Settings "Re-register now" test
     *  hook for the periodic renewal. Unlike [renew] (which re-runs the register
     *  transport path), this asks the LIVE native client to re-register with the
     *  current identity — no login, no 2FA — so the running session picks it up.
     *  Requires being signed in AND connected; the native side errors otherwise. */
    suspend fun reregisterNow(context: Context): RegistrationResult {
        val store = SmartTxtAccountStore(context.applicationContext)
        val account = store.loadAccount() ?: return RegistrationResult.Failure("You're not signed in yet.")
        return when (val r = bridge().reregister()) {
            is RustPushBridge.ReregisterResult.Success -> {
                val updated = account.copy(
                    lastRegisteredMs = System.currentTimeMillis(),
                    handles = r.handles.ifEmpty { account.handles },
                )
                store.saveAccount(updated); store.markRegistered(updated.lastRegisteredMs)
                Log.i(TAG, "manual re-register ok (${updated.handles.size} handles)")
                RegistrationResult.Success(updated)
            }
            is RustPushBridge.ReregisterResult.Failure -> {
                Log.w(TAG, "manual re-register failed: ${r.message}")
                RegistrationResult.Failure(r.message)
            }
        }
    }

    /** Mark the identity REGISTERED after an out-of-band sign-in (the OpenBubbles
     *  migration): the account store + native files are already written, so just
     *  flip [status], schedule renewal, and bring up the connection — the same tail
     *  as [finishRegister] minus the live register call. */
    fun markRegisteredExternally(context: Context) {
        val appContext = context.applicationContext
        connectInBackground(appContext)
        _status.value = SmartTxtStatus.REGISTERED
        Log.i(TAG, "markRegisteredExternally → REGISTERED")
    }

    /** Connect the live session (called on launcher start / after registration). */
    fun connect(context: Context) {
        create(context) // building the repository connects the session
    }

    /**
     * Bring the process-scoped session up OFF the main thread — [connect] →
     * [create] runs the one-time native init (keystore + file I/O + tokio
     * runtime), which must never run on the caller's main thread.
     *
     * This IS the "keep Smart Txt connected in the background" mechanism. Like
     * the sibling [com.offline.dpadmessenger.backend.gmessages.GoogleMessagesRepository]
     * and Signal backends, it runs entirely in-process with NO foreground
     * service: the launcher is the persistent (`android:persistent`) HOME app,
     * so its process stays alive and holds the APNs socket open on its own. A
     * foreground service would only add Android's mandatory — and, on API 30,
     * un-hideable — status-bar notification for no reliability gain (the OS
     * floors any FGS notification channel to IMPORTANCE_LOW, so the icon can't
     * be suppressed; that was the phantom "smart txt connection" icon).
     */
    private fun connectInBackground(appContext: Context) {
        Thread {
            runCatching { connect(appContext) }
                .onFailure { Log.w(TAG, "session connect failed: ${it.message}") }
        }.start()
    }

    /** Bring the background push connection up on launcher start if the user is
     *  already signed in, so messages sync whenever the launcher process and the
     *  phone are on — not only while the Smart Txt screen is open. Safe to call on
     *  every launch: [connect] is idempotent (the session is a process-scoped
     *  singleton). Missed messages from while the phone was OFF aren't backfilled,
     *  but anything APNs queued while briefly disconnected replays on reconnect. */
    fun startBackgroundSyncIfRegistered(context: Context) {
        val appContext = context.applicationContext
        // Off the main thread so reading EncryptedSharedPreferences + the native
        // connect never block the launcher's Application.onCreate (ANR risk).
        Thread {
            val store = SmartTxtAccountStore(appContext)
            if (!store.isRegistered()) return@Thread
            // Signed in ⇒ OpenBubbles must not be on this device, full stop: it holds the
            // SAME push identity, so leaving it installed makes Apple thrash both apps.
            // OpenBubblesMigrator.migrate() already uninstalls it, but only on the one launch
            // that actually runs a transfer — which misses an OpenBubbles that came back
            // afterwards (a rollback off the beta build reinstalls it, and returning to Smart
            // Txt then finds it running in the background). Sweeping from launcher start means
            // an app UPDATE alone repairs an affected handset; the user never has to open
            // Smart Txt. Fire-and-forget so the root probe can't delay the push connect below.
            OpenBubblesMigrator.sweepOnLaunchAsync(appContext)
            restoreStatus(appContext)
            // Build the repo/session once, then make sure the CLIENT is actually up.
            runCatching { connect(appContext) }
                .onFailure { Log.w(TAG, "background sync start on boot failed: ${it.message}") }
            // Register the network watcher BEFORE the retry ladder, not after. It used
            // to go last, so during the ~40s of retries nothing was listening — a
            // network arriving mid-ladder was missed entirely.
            registerNetworkRecovery(appContext)
            connectWithRetry(appContext)
            healIfUnhealthy(appContext, store)
        }.start()
    }

    /**
     * True when the iMessage client is BUILT, not merely when a socket is open.
     *
     * Falls back to [RustPushNative.nativeIsConnected] if the bundled `.so` predates
     * `nativeHasClient` — the JNI call throws UnsatisfiedLinkError on version skew,
     * and degrading to the old (weaker) signal beats crashing or looping forever.
     */
    private fun nativeClientReady(): Boolean {
        if (!RustPushBridge.NATIVE_AVAILABLE) return false
        return runCatching { RustPushNative.nativeHasClient() }.getOrElse {
            Log.w(TAG, "nativeHasClient unavailable (old .so?) — falling back to nativeIsConnected")
            runCatching { RustPushNative.nativeIsConnected() }.getOrDefault(false)
        }
    }

    /**
     * Get the client up, and return whether it is.
     *
     * The subtlety: a plain retry of `nativeConnect` does NOT help in the failure we
     * actually shipped. It short-circuits on `connected && connection.is_some()` and
     * returns true without rebuilding anything — so when the socket is up but the
     * client build failed, retrying is a no-op. Tearing the connection down first
     * forces `nativeConnect` back through the full path, including
     * `build_client_guarded`.
     *
     * Only does that when the client is actually down, so a healthy process never
     * churns its APNs socket (two sockets on one push token makes Apple drop both).
     */
    private fun ensureClientUp(appContext: Context): Boolean = synchronized(healLock) {
        doEnsureClientUp(appContext)
    }

    /** Body of [ensureClientUp]; always called under [healLock] so the boot ladder,
     *  the network callback and the Settings row can never drive overlapping
     *  reconnects. `synchronized` is reentrant, so [fixConnectionBlocking] taking the
     *  same lock before calling in is fine. */
    private fun doEnsureClientUp(appContext: Context): Boolean {
        if (nativeClientReady()) return true
        if (!ensureNativeInit(appContext)) {
            appendConnectLog(appContext, "ensureClientUp: nativeInit FAILED")
            return false
        }
        // Through RustPushBridge.connectApns — the one funnel — never straight to the
        // FFI. It owns the teardown too, so the drop and the reconnect happen under one
        // lock and the session's own connect cannot slip in between them.
        val connected = runCatching { bridge().connectApns(rebuild = true) }.getOrElse {
            Log.w(TAG, "ensureClientUp: connectApns threw: ${it.message}")
            false
        }
        val ready = nativeClientReady()
        appendConnectLog(appContext, "ensureClientUp: nativeConnect=$connected clientReady=$ready")
        return ready
    }

    /**
     * THE BOOT FIX. This used to be a single `connect()` whose failure was logged and
     * forgotten, so a launcher that started before WiFi came up — it's the persistent
     * HOME app, so it starts very early — left the client dead for the entire life of
     * the process. On a phone nobody reboots, that is forever: two handsets sat dead
     * for 24h+ after a power-off, unable to send OR receive, until a force-stop.
     */
    private fun connectWithRetry(appContext: Context) {
        // There used to be a 12 s poll here, waiting out the session's own connect
        // because it reached nativeConnect by a path healLock couldn't cover. That is
        // gone: both now go through RustPushBridge.connectApns, so this simply blocks
        // on the funnel and then observes the winner's result. A timing guess is not a
        // substitute for a single call site, and this one was losing — the 2026-08-06
        // capture has the two connects 225 ms apart, well inside the grace window.
        for (attempt in 1..BOOT_CONNECT_ATTEMPTS) {
            if (ensureClientUp(appContext)) {
                if (attempt > 1) Log.i(TAG, "boot connect recovered on attempt $attempt")
                return
            }
            Log.w(TAG, "boot connect attempt $attempt/$BOOT_CONNECT_ATTEMPTS: client still down")
            if (attempt < BOOT_CONNECT_ATTEMPTS) {
                runCatching { Thread.sleep(BOOT_CONNECT_BACKOFF_MS * attempt) }
            }
        }
        // Not fatal: the network callback below picks it up the moment we get an
        // interface, which is the common case for a boot that raced WiFi.
        Log.w(TAG, "boot connect exhausted — leaving it to the network callback")
        appendConnectLog(appContext, "boot connect EXHAUSTED after $BOOT_CONNECT_ATTEMPTS attempts")
    }

    /**
     * Reconnect as soon as the device actually has a network. This is what makes a
     * no-network boot self-heal in seconds instead of waiting on the retry ladder —
     * and it's the same trick the launcher's DeviceRegistrar already uses.
     * Registered once per process; cheap no-op when the client is already up.
     */
    private fun registerNetworkRecovery(appContext: Context) {
        if (!networkRecoveryRegistered.compareAndSet(false, true)) return
        val cm = runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Callback runs on a system binder thread — never do native work here.
                    Thread {
                        runCatching {
                            if (!nativeClientReady()) {
                                // COOLDOWN. onAvailable fires on every default-network
                                // change, so a handset flapping between WiFi and cellular
                                // with a dead client would drive an unbounded reconnect
                                // loop — each pass tearing down and rebuilding the APS
                                // connection, which resets rustpush's backoff. rustpush
                                // already retries on its own schedule; this callback
                                // exists only to SHORTEN the wait when the network
                                // genuinely returns, not to replace the retry.
                                val now = android.os.SystemClock.elapsedRealtime()
                                val prev = lastNetworkReconnectMs.get()
                                if (prev != 0L && now - prev < NETWORK_RECONNECT_COOLDOWN_MS) {
                                    Log.i(
                                        TAG,
                                        "network available, but a reconnect ran " +
                                            "${(now - prev) / 1000}s ago — skipping " +
                                            "(rustpush is still retrying on its own)",
                                    )
                                    return@runCatching
                                }
                                lastNetworkReconnectMs.set(now)
                                Log.w(TAG, "network available and client is DOWN — reconnecting")
                                appendConnectLog(appContext, "network available, client down → reconnect")
                                ensureClientUp(appContext)
                            }
                        }.onFailure { Log.w(TAG, "network reconnect failed: ${it.message}") }
                    }.start()
                }
            })
            Log.i(TAG, "network recovery callback registered")
        }.onFailure {
            networkRecoveryRegistered.set(false)
            Log.w(TAG, "network callback registration failed: ${it.message}")
        }
    }

    /**
     * Small capped log that OUTLIVES logcat. The ring buffer rolls in hours; these
     * failures aren't reported for days, which is why the original cause took two
     * handsets and a four-hour investigation to pin down. Best-effort — never throws.
     */
    private fun appendConnectLog(appContext: Context, line: String) {
        runCatching {
            val f = java.io.File(appContext.filesDir, CONNECT_LOG_NAME)
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$stamp $line\n")
            if (f.length() > CONNECT_LOG_MAX_BYTES) {
                val keep = f.readLines().takeLast(CONNECT_LOG_KEEP_LINES)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /**
     * Belt-and-braces for rustpush's in-process re-registration timer: it only
     * runs while the process is alive, so a handset that was force-stopped can come
     * back with no client and nothing driving it.
     *
     * On every launch, once connected, check the registration age ourselves and
     * silently re-register if it's stale. Runs on the caller's background thread.
     * Silent by design: transient faults are retried in code and then left to the
     * periodic worker, so nothing reaches the user. Unrecoverable causes are logged
     * for support rather than shown — the user can reach the same recovery from the
     * Settings row, and a banner would need the shared room list, which is out of
     * scope here.
     */
    private fun healIfUnhealthy(appContext: Context, store: SmartTxtAccountStore) {
        // The ENTIRE body is defensive. This runs on the boot path of every handset,
        // not just broken ones, so nothing in here may ever propagate out and take
        // down the launcher — which is the HOME app, so a crash here is a brick.
        try {
            // Two independent reasons to act, and the client one matters MORE. Gating
            // purely on registration age was wrong: a handset was found completely
            // dead — no sends, no receives — with 18 days of registration left, so an
            // age check would never have fired. A dead client is the actual failure;
            // a stale registration is usually just its downstream symptom (no client
            // ⇒ no reregister timer ⇒ drift).
            // Registration AGE is no longer consulted: rustpush computes the real
            // re-registration time itself (calculate_rereg_time_s, surfaced as
            // `next_s` by nativeRegisterState) on Apple's ~45-day cadence, and
            // schedules it. A local 7-day guess only ever fired early. A dead
            // client is the actual failure worth healing here.
            val clientDown = !nativeClientReady()
            if (!clientDown) return
            Log.w(TAG, "unhealthy on launch: clientDown=true")
            // In-process re-entry guard. Skip rather than queue: this is a boot
            // thread, and blocking it behind an in-flight attempt buys nothing.
            if (!healing.compareAndSet(false, true)) {
                Log.i(TAG, "self-heal already in flight — skipping")
                return
            }
            try {
                // PERSISTED BACKOFF. A device that keeps failing would otherwise hit
                // Apple once per boot, and on a reboot loop that's a fast route to
                // rate-limiting the account — a far worse outcome than the stale
                // registration we're repairing. At most one automatic attempt per
                // cooldown. The user-initiated Settings row deliberately does NOT go
                // through here, so tapping it always tries immediately.
                val prefs = appContext.getSharedPreferences(HEAL_PREFS, Context.MODE_PRIVATE)
                val lastAttempt = prefs.getLong(KEY_LAST_HEAL_ATTEMPT, 0L)
                val sinceMs = System.currentTimeMillis() - lastAttempt
                if (lastAttempt > 0L && sinceMs < HEAL_COOLDOWN_MS) {
                    Log.i(TAG, "self-heal on cooldown — last attempt ${sinceMs / (60L * 1000L)}m ago")
                    return
                }
                prefs.edit().putLong(KEY_LAST_HEAL_ATTEMPT, System.currentTimeMillis()).apply()

                Log.w(TAG, "registration is stale — self-healing with a silent re-register")
                when (val r = fixConnection(appContext)) {
                    is FixOutcome.Recovered ->
                        Log.i(TAG, "stale registration healed silently")
                    is FixOutcome.RetryingInBackground -> {
                        // Transient. Already retried in code; the 12h worker and the
                        // next launch will keep at it. Explicitly do NOT flag the
                        // user — there is nothing for them to do.
                        Log.w(TAG, "stale registration heal deferred to background retry")
                    }
                    is FixOutcome.NeedsUser ->
                        Log.w(TAG, "stale registration needs user action: ${r.message}")
                }
            } finally {
                healing.set(false)
            }
        } catch (t: Throwable) {
            // Suppressed on purpose — a broken self-heal must degrade to "messaging
            // is stale", never to "the phone's launcher died on boot".
            Log.e(TAG, "self-heal threw — suppressed to protect the boot path", t)
        }
    }

    /**
     * The full recovery ladder, and the one thing both the automatic staleness
     * check and the Settings "Re-register now" row call.
     *
     * Why this is not just [reregisterNow]: that path goes straight to
     * `bridge().reregister()`, which needs a LIVE native client. In the exact state
     * we're recovering from — process up but `st().client == None` — it fails with
     * "the iMessage engine isn't running yet", i.e. the dev hook is useless in the
     * only situation anyone needs it. So rebuild the client FIRST, then re-register.
     *
     * Blocking; callers must be off the main thread.
     */
    /**
     * What the UI should do about a recovery attempt. Deliberately NOT a raw
     * error string: most failures here are transient (a flaky NAC/anisette call,
     * a dropped connection) and the right response is to retry in code, not to
     * put a technical message in front of someone holding a dumb phone.
     */
    sealed class FixOutcome {
        /** Registered again. Sending works. */
        object Recovered : FixOutcome()
        /** Didn't get there this time, but it's a transient class of failure and
         *  the periodic renewal + next-launch heal will keep trying. Nothing is
         *  required of the user, so don't show them an error. */
        object RetryingInBackground : FixOutcome()
        /** Genuinely unrecoverable without the user (not signed in, missing
         *  identity, missing native library) — this one has to be surfaced, or we
         *  recreate the original bug where a broken phone looks fine. */
        data class NeedsUser(val message: String) : FixOutcome()
    }

    /**
     * [fixConnectionBlocking] plus retry and classification — this is what the UI
     * should call. Retries transient failures [FIX_ATTEMPTS] times with a short
     * backoff so the overwhelmingly common case (a hiccup talking to Apple)
     * resolves in code and the user never sees a failure at all.
     *
     * Blocking; callers must be off the main thread.
     */
    fun fixConnection(context: Context): FixOutcome {
        val appContext = context.applicationContext
        if (!SmartTxtAccountStore(appContext).isRegistered()) {
            return FixOutcome.NeedsUser(NOT_SIGNED_IN_MESSAGE)
        }

        // PHASE 1 — the LOCAL half, retried.
        //
        // Bringing the native runtime and the client back up fails transiently while
        // the network is still coming up, and retrying it costs Apple nothing beyond
        // the APNs connect rustpush would make on its own.
        var lastLocal: String? = null
        var clientUp = false
        for (attempt in 0 until FIX_ATTEMPTS) {
            val err = runCatching { prepareClient(appContext) }
                .getOrElse { it.message ?: "unknown error" }
            if (err == null) {
                clientUp = true
                break
            }
            if (isTerminalFailure(err)) return FixOutcome.NeedsUser(err)
            lastLocal = err
            Log.w(TAG, "fixConnection: client rebuild ${attempt + 1}/$FIX_ATTEMPTS failed: $err")
            if (attempt < FIX_ATTEMPTS - 1) {
                runCatching { Thread.sleep(FIX_BACKOFF_MS * (attempt + 1)) }
            }
        }
        if (!clientUp) {
            Log.w(TAG, "fixConnection: client would not come up ($lastLocal) — leaving it to rustpush")
            return FixOutcome.RetryingInBackground
        }

        // PHASE 2 — the APPLE-FACING half, exactly ONCE.
        //
        // This used to sit inside the retry loop above, so a failing fix sent THREE
        // re-registrations at t=0, +4s, +12s. Worse than it sounds: each one calls
        // refresh_now(), which fires rustpush's retry_now_signal and wakes the
        // ResourceManager out of its sleep, so the 5-minute floor rustpush would
        // otherwise impose never applied. On an account that is already rate-limited
        // that is the worst available response — and it is the one case where Smart
        // Txt hit Apple HARDER than OpenBubbles, which sends exactly one (doReregister
        // -> a single refresh_now, guarded by a busy flag).
        //
        // One attempt. If it fails, rustpush owns the retry: 5 minutes to 24 hours,
        // unlimited attempts, already running.
        val r = runCatching { reregisterOnce(appContext) }.getOrElse {
            RegistrationResult.Failure(it.message ?: "unknown error")
        }
        return when (r) {
            is RegistrationResult.Success -> FixOutcome.Recovered
            is RegistrationResult.Failure ->
                if (isTerminalFailure(r.message)) {
                    FixOutcome.NeedsUser(r.message)
                } else {
                    Log.w(
                        TAG,
                        "fixConnection: re-register failed (${r.message}) — NOT retrying. " +
                            "rustpush owns the backoff from here (5min→24h, unlimited).",
                    )
                    FixOutcome.RetryingInBackground
                }
        }
    }

    /** PHASE 1 of a fix: get the native runtime and a live client back up. Local work
     *  only, so it is safe to retry. Returns null on success, else the failure text. */
    private fun prepareClient(appContext: Context): String? = synchronized(healLock) {
        when {
            !ensureNativeInit(appContext) -> initFailureMessage()
            !doEnsureClientUp(appContext) -> RECONNECT_FAILED_MESSAGE
            else -> null
        }
    }

    /** PHASE 2 of a fix: the Apple-facing half. Sends exactly ONE re-registration.
     *  Never call this in a loop — see the note in [fixConnection]. */
    private fun reregisterOnce(appContext: Context): RegistrationResult = synchronized(healLock) {
        val store = SmartTxtAccountStore(appContext)
        when (val r = runBlocking { bridge().reregister() }) {
            is RustPushBridge.ReregisterResult.Success -> {
                val account = store.loadAccount()
                if (account == null) {
                    RegistrationResult.Failure(NOT_SIGNED_IN_MESSAGE)
                } else {
                    val updated = account.copy(
                        lastRegisteredMs = System.currentTimeMillis(),
                        handles = r.handles.ifEmpty { account.handles },
                    )
                    store.saveAccount(updated)
                    store.markRegistered(updated.lastRegisteredMs)
                    _status.value = SmartTxtStatus.REGISTERED
                    Log.i(TAG, "fixConnection: recovered")
                    RegistrationResult.Success(updated)
                }
            }
            is RustPushBridge.ReregisterResult.Failure -> {
                Log.w(TAG, "fixConnection: re-register failed: ${r.message}")
                RegistrationResult.Failure(r.message)
            }
        }
    }

    /** Failures no amount of retrying will fix — they need the user to do something. */
    private fun isTerminalFailure(message: String): Boolean {
        if (message == NOT_SIGNED_IN_MESSAGE ||
            message == NATIVE_MISSING_MESSAGE ||
            message == IDENTITY_MISSING_MESSAGE
        ) {
            return true
        }
        // The three above are LOCAL conditions. Everything Apple said used to fall
        // through here as "transient" and get retried, which is exactly backwards for
        // the replies that matter most. rustpush's own rate-limit text (error.rs:38)
        // says it outright: "trying to reconfigure or re-install to 'fix' the rate
        // limit will result in being temporarily blocked from iMessage." Retrying
        // converts a soft limit into a hard one. A 6005 is marked DoNotRetry upstream
        // and cannot be retried away either.
        //
        // Matched on rustpush's `#[error(...)]` Display text, because a String is all
        // that survives the JNI boundary.
        val m = message.lowercase()
        return m.contains("do not retry") ||             // PushError::DoNotRetry
            m.contains("rate-limited") ||                // error.rs:38
            m.contains("temporarily disabled") ||        // error.rs:51 - iMessage access pulled
            m.contains("trusted phone number") ||        // error.rs:65 - needs the user
            m.contains("failed to authenticate") ||      // error.rs:63 - needs the user
            (m.contains("registration error") && m.contains("6005"))
    }

    fun fixConnectionBlocking(context: Context): RegistrationResult = synchronized(healLock) {
        doFixConnection(context)
    }

    private fun doFixConnection(context: Context): RegistrationResult {
        val appContext = context.applicationContext
        val store = SmartTxtAccountStore(appContext)
        if (!store.isRegistered()) return RegistrationResult.Failure(NOT_SIGNED_IN_MESSAGE)

        // The two phases, once each. [fixConnection] is the entry point the UI uses
        // and it retries phase 1 only; this variant keeps the original single-shot
        // semantics for any caller that wants the raw result.
        prepareClient(appContext)?.let { return RegistrationResult.Failure(it) }
        return reregisterOnce(appContext)
    }

    @Synchronized
    fun shutdown(context: Context, wipe: Boolean) {
        (instance as? SmartTxtMessageRepository)?.shutdown(clearCache = wipe)
        session?.shutdown()
        instance = null
        session = null
        transport = null
        bridge = null
        if (wipe) {
            // Clear the NATIVE login state too (config.plist, keystore.plist, creds,
            // id_cache, anisette) — not just the Kotlin store — so the next sign-in is
            // fresh with no OpenBubbles/migrated resume. Device identity (dumb +
            // os_config.plist) is kept so a fresh registration still validates via NAC.
            RustPushNative.runCatchingNativeLogout()
            SmartTxtAccountStore(context.applicationContext).clear()
            _status.value = SmartTxtStatus.UNREGISTERED
        }
    }

    /**
     * Full re-sign-in KEEPING history: tears down the live session/transport
     * without wiping the cached account/config, then flips [status] to
     * UNREGISTERED so the sign-in screen shows. Unlike [shutdown] with
     * `wipe=true`, the encrypted store is left intact, so re-registering reuses
     * the same identity and the chat history survives.
     */
    /** True while Apple is refusing this device's APS connect. Drives the re-setup
     *  modal. Not persisted: it is re-derived within one poll tick of any process
     *  start, and persisting it would strand a user behind the modal if the state
     *  cleared while the app was closed. */
    private val _pushCertRejected = MutableStateFlow(false)
    val pushCertRejected: StateFlow<Boolean> = _pushCertRejected

    internal fun setPushCertRejected(context: Context, rejected: Boolean) {
        if (_pushCertRejected.value == rejected) return
        Log.w(TAG, "pushCertRejected → $rejected")
        _pushCertRejected.value = rejected
        // The notification is the PRIMARY surface, not the dialog: this failure happens
        // while the app is closed (the push connection lives in the launcher process),
        // so a dialog alone waits for a visit that may never come. Mirrors OB's
        // createRegisterFailed / clearRegisterFailed pair.
        val notifier = SmartTxtNotifier(context.applicationContext)
        runCatching {
            if (rejected) notifier.notifyPushCertRejected() else notifier.clearPushCertRejected()
        }.onFailure { Log.w(TAG, "push-cert notification failed: ${it.message}") }
    }

    fun signOutKeepingHistory(context: Context) {
        shutdown(context, wipe = false)
        _status.value = SmartTxtStatus.UNREGISTERED
    }

    /**
     * The one-time demo-cache purge above. Exposed (rather than inlined) because
     * [ObHistoryImporter] has to CLAIM it: `create()` runs the purge on the first
     * native launch, and on a migrating device that launch happens AFTER the
     * OpenBubbles history has been written — on 2026-08-05 it wiped a verified
     * import of 4 rooms / 22 messages microseconds after it landed. The importer
     * therefore performs this purge itself, before writing, and sets the flag.
     * Keep both sides on these constants so they can't drift apart.
     */
    internal const val DEMO_PURGE_PREFS = "smarttxt_flags"
    internal const val DEMO_PURGE_KEY = "purged_demo_cache_v2"

    private const val TAG = "IMsgRepoHolder"

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS


    /** Guards against two re-registrations running at once (a boot-path heal racing
     *  a user tapping the Settings row). Re-registration talks to Apple, so
     *  overlapping attempts are exactly what we must not do. */
    private val healing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Last network-triggered reconnect, for the [NETWORK_RECONNECT_COOLDOWN_MS] guard. */
    private val lastNetworkReconnectMs = java.util.concurrent.atomic.AtomicLong(0L)

    /** Serialises [fixConnectionBlocking] without taking the object monitor — the
     *  other @Synchronized members (create/transport/bridge/shutdown) share that
     *  monitor, and this call blocks for ~30s, so holding it would stall them. */
    private val healLock = Any()

    private const val HEAL_PREFS = "smarttxt_heal"
    private const val KEY_LAST_HEAL_ATTEMPT = "lastHealAttemptMs"

    /** Minimum gap between AUTOMATIC heal attempts. A device stuck in a failing
     *  state would otherwise call Apple once per boot; on a reboot loop that risks
     *  rate-limiting the Apple ID, which is worse than the stale registration. The
     *  user-initiated Settings row bypasses this. */
    private const val HEAL_COOLDOWN_MS = 6L * HOUR_MS

    /** Transient-failure retries inside one heal attempt. Covers the common case —
     *  a flaky NAC/anisette call or a dropped connection — without ever surfacing
     *  anything to the user. */
    /** Minimum gap between network-triggered reconnects. rustpush's APS resource
     *  retries on its own (capped at 30s), so this only needs to be short enough to
     *  feel responsive when the network really comes back. */
    private const val NETWORK_RECONNECT_COOLDOWN_MS = 60_000L

    /** Attempts for the LOCAL half of a fix only (native init + client rebuild).
     *  The Apple-facing re-registration is sent exactly once — see [fixConnection]. */
    private const val FIX_ATTEMPTS = 3
    private const val FIX_BACKOFF_MS = 4_000L

    /** Boot connect retry. Short and bounded on purpose — [registerNetworkRecovery]
     *  is the real recovery for a boot that lost the race with WiFi; this only covers
     *  a brief race. 4 attempts with linear backoff ≈ 18s, all on a background thread. */
    private const val BOOT_CONNECT_ATTEMPTS = 4
    private const val BOOT_CONNECT_BACKOFF_MS = 3_000L

    /** One network callback per process. */
    private val networkRecoveryRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Capped connect log that outlives logcat's ring buffer. Read it with:
     *  `adb shell su -c 'cat /data/data/<pkg>/files/smarttxt_connect_log.txt'` */
    private const val CONNECT_LOG_NAME = "smarttxt_connect_log.txt"
    private const val CONNECT_LOG_MAX_BYTES = 32L * 1024L
    private const val CONNECT_LOG_KEEP_LINES = 200

    /** No account at all — recovery is impossible, the user has to sign in. */
    const val NOT_SIGNED_IN_MESSAGE =
        "You're not signed in to iMessage yet. Open Smart Txt setup to sign in."

    /** We had an account and a live runtime, but couldn't get an APNs connection —
     *  almost always plain connectivity rather than anything identity-related. */
    const val RECONNECT_FAILED_MESSAGE =
        "Couldn't reach Apple to reconnect. Check your internet connection and try again."

    /** Shown when nativeInit fails because the identity isn't usable. The `dumb` is the
     *  ONLY file required — rustpush rebuilds the whole device identity from it, so
     *  os_config.plist / config.plist / keystore.plist being absent is FINE (that's just
     *  a device that hasn't signed in yet). This message means the dumb is missing, empty,
     *  or unreadable; `smarttxt_ffi` logs which, including the file's owner — a root-owned
     *  dumb (uid 0, e.g. dropped in with `su cp`) exists but the app can't read it. */
    const val IDENTITY_MISSING_MESSAGE =
        "Can't start iMessage — the device identity (the “dumb” file) is missing or unreadable. " +
        "Reopen the app to run the OpenBubbles transfer (OpenBubbles must be installed and signed in), then try again."

    /** nativeInit is only reached when the .so actually loaded. Blaming the identity for a
     *  missing native library sent us chasing a dumb file that was sitting right there. */
    const val NATIVE_MISSING_MESSAGE =
        "iMessage engine isn't available on this device (the native library failed to load)."

    /** Why did [ensureNativeInit] fail? Distinguishes the two very different causes. */
    private fun initFailureMessage(): String =
        if (!RustPushBridge.NATIVE_AVAILABLE) NATIVE_MISSING_MESSAGE else IDENTITY_MISSING_MESSAGE
}

/** Where the SmartTxt identity is in its lifecycle. The UI gates on this. */
enum class SmartTxtStatus {
    /** No dumb file / account. Show setup. */
    UNREGISTERED,
    /** Dumb file + account imported but `register` not yet run. */
    SEEDED,
    /** Registration in progress. */
    REGISTERING,
    /** Registered with IDS — show the chat. */
    REGISTERED,
}
