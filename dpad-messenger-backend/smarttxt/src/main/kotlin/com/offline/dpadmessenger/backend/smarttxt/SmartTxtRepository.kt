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

        // RE-ARM THE RENEWAL ON EVERY COLD START. This used to be scheduled ONLY
        // from finishRegister()/markRegisteredExternally() — i.e. once, at sign-in.
        // If that work item ever left WorkManager's DB (force-stop, an OEM battery
        // manager, cleared app data, a half-completed migration) nothing ever
        // re-scheduled it, the IDS registration silently aged out, and the device
        // kept RECEIVING while every send failed — invisible to the user, because
        // [status] still says REGISTERED. Observed in the field: a device whose
        // registration had been overdue for 3.7 days ("Reregistering in -323914
        // seconds") and only recovered because a relaunch let rustpush's own
        // in-process timer fire. KEEP policy makes this idempotent, so calling it
        // on every launch is free and matches what the launcher's other workers
        // already do.
        if (_status.value == SmartTxtStatus.REGISTERED) {
            // OFF THE MAIN THREAD. This function is documented as safe to call from
            // the entry composable, so it can land on the UI thread — and both calls
            // below touch disk (WorkManager's DB; another EncryptedSharedPreferences
            // decrypt + JSON parse in lastRegisteredMs()). Individually cheap, but
            // this runs on every launch on every handset and the store is keystore-
            // backed crypto, so it must never sit on the UI thread. runCatching so a
            // WorkManager failure can never take down the launcher.
            Thread {
                runCatching {
                    SmartTxtRenewalWorker.schedule(appContext)
                    logRegistrationAge(store.lastRegisteredMs())
                }.onFailure { Log.w(TAG, "renewal re-arm failed: ${it.message}") }
            }.start()
        }
    }

    /** True when the registration is old enough that sends are at risk. Deliberately
     *  well under Apple's ~45-day IDS re-registration interval so we self-heal long
     *  before the user notices, and well over the 12h worker period so a couple of
     *  missed runs don't churn. */
    private fun isRegistrationStale(store: SmartTxtAccountStore): Boolean {
        val at = store.lastRegisteredMs()
        return at > 0L && (System.currentTimeMillis() - at) > STALE_AFTER_MS
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
                "(lastRegisteredMs=$at, stale=${ageMs > STALE_AFTER_MS})",
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
            val flags = appContext.getSharedPreferences("smarttxt_flags", Context.MODE_PRIVATE)
            if (!flags.getBoolean("purged_demo_cache_v2", false)) {
                SmartTxtStore(appContext).clear()
                flags.edit().putBoolean("purged_demo_cache_v2", true).apply()
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
            SmartTxtRenewalWorker.schedule(appContext)
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

    /** Re-register (renewal). Called by [SmartTxtRenewalWorker]. */
    suspend fun renew(context: Context): RegistrationResult {
        val store = SmartTxtAccountStore(context.applicationContext)
        val config = store.loadConfig() ?: return RegistrationResult.Failure("no dumb file to renew from")
        val account = store.loadAccount() ?: return RegistrationResult.Failure("no account to renew")
        return when (val r = session().register(config, account.appleId)) {
            is RegisterResult.Success -> {
                val updated = account.copy(lastRegisteredMs = System.currentTimeMillis(), handles = r.handles)
                store.saveAccount(updated); store.markRegistered(updated.lastRegisteredMs)
                RegistrationResult.Success(updated)
            }
            is RegisterResult.Failure -> RegistrationResult.Failure(r.message)
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
        SmartTxtRenewalWorker.schedule(appContext)
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
        runCatching { RustPushNative.nativeDisconnect() }
        val connected = runCatching { RustPushNative.nativeConnect() }.getOrElse {
            Log.w(TAG, "ensureClientUp: nativeConnect threw: ${it.message}")
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
        // The session's OWN connect is already in flight from connect() above, and it
        // reaches nativeConnect by a different path than ensureClientUp — so the lock
        // can't serialise it. Poll first and let it finish. Without this, both raced
        // into the FFI's 4-attempt APNs ladder at once and the log showed two fully
        // interleaved connect sequences.
        repeat(BOOT_CONNECT_GRACE_POLLS) {
            if (nativeClientReady()) return
            runCatching { Thread.sleep(BOOT_CONNECT_GRACE_MS) }
        }
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
     * Belt-and-braces for [SmartTxtRenewalWorker]: WorkManager is not a guarantee.
     * Its work item can be dropped by a force-stop or an OEM battery manager, and
     * even when scheduled it's only best-effort under Doze — so a device can sit
     * de-registered indefinitely while the UI happily says REGISTERED.
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
            val clientDown = !nativeClientReady()
            val stale = isRegistrationStale(store)
            if (!clientDown && !stale) return
            Log.w(TAG, "unhealthy on launch: clientDown=$clientDown staleRegistration=$stale")
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
        var last: RegistrationResult.Failure? = null
        repeat(FIX_ATTEMPTS) { attempt ->
            when (val r = runCatching { fixConnectionBlocking(context) }.getOrElse {
                RegistrationResult.Failure(it.message ?: "unknown error")
            }) {
                is RegistrationResult.Success -> return FixOutcome.Recovered
                is RegistrationResult.Failure -> {
                    // Terminal causes can't be retried away — more attempts would
                    // just hammer Apple for nothing.
                    if (isTerminalFailure(r.message)) return FixOutcome.NeedsUser(r.message)
                    last = r
                    Log.w(TAG, "fixConnection attempt ${attempt + 1}/$FIX_ATTEMPTS failed: ${r.message}")
                    if (attempt < FIX_ATTEMPTS - 1) {
                        runCatching { Thread.sleep(FIX_BACKOFF_MS * (attempt + 1)) }
                    }
                }
            }
        }
        // Exhausted retries on a transient fault. Keep it out of the user's face —
        // the 12h renewal worker and the next-launch heal will pick it up.
        Log.w(TAG, "fixConnection exhausted retries, leaving it to the background: ${last?.message}")
        return FixOutcome.RetryingInBackground
    }

    /** Failures no amount of retrying will fix — they need the user to do something. */
    private fun isTerminalFailure(message: String): Boolean =
        message == NOT_SIGNED_IN_MESSAGE ||
            message == NATIVE_MISSING_MESSAGE ||
            message == IDENTITY_MISSING_MESSAGE

    fun fixConnectionBlocking(context: Context): RegistrationResult = synchronized(healLock) {
        doFixConnection(context)
    }

    private fun doFixConnection(context: Context): RegistrationResult {
        val appContext = context.applicationContext
        val store = SmartTxtAccountStore(appContext)
        if (!store.isRegistered()) return RegistrationResult.Failure(NOT_SIGNED_IN_MESSAGE)

        // 1. The native runtime has to be up before anything else can work.
        if (!ensureNativeInit(appContext)) return RegistrationResult.Failure(initFailureMessage())

        // 2. Rebuild the client if it isn't live.
        //    This used to guard on nativeIsConnected(), which was wrong in the one
        //    state that matters: that flag is set when the APNs socket opens, BEFORE
        //    the client is built, so it reads true when the client is dead — the
        //    reconnect was skipped and we fell straight through to a reregister that
        //    fails on `client == None`. ensureClientUp asks whether the CLIENT exists
        //    and tears the connection down first so the rebuild actually happens.
        if (!ensureClientUp(appContext)) {
            return RegistrationResult.Failure(RECONNECT_FAILED_MESSAGE)
        }

        // 3. Now that a client exists, force the re-registration.
        return when (val r = runBlocking { bridge().reregister() }) {
            is RustPushBridge.ReregisterResult.Success -> {
                val account = store.loadAccount()
                    ?: return RegistrationResult.Failure(NOT_SIGNED_IN_MESSAGE)
                val updated = account.copy(
                    lastRegisteredMs = System.currentTimeMillis(),
                    handles = r.handles.ifEmpty { account.handles },
                )
                store.saveAccount(updated)
                store.markRegistered(updated.lastRegisteredMs)
                // Re-arm the periodic renewal too — if we got here it may well have
                // been missing, which is what let the registration go stale.
                SmartTxtRenewalWorker.schedule(appContext)
                _status.value = SmartTxtStatus.REGISTERED
                Log.i(TAG, "fixConnection: recovered")
                RegistrationResult.Success(updated)
            }
            is RustPushBridge.ReregisterResult.Failure -> {
                Log.w(TAG, "fixConnection: re-register failed: ${r.message}")
                RegistrationResult.Failure(r.message)
            }
        }
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
    fun signOutKeepingHistory(context: Context) {
        shutdown(context, wipe = false)
        _status.value = SmartTxtStatus.UNREGISTERED
    }

    private const val TAG = "IMsgRepoHolder"

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS

    /** Re-register once the stamp is older than this. Apple's IDS interval is ~45
     *  days (rustpush reported "Reregistering in 3887700 seconds" after a fresh
     *  one), so 7 days self-heals with a very wide margin while staying far above
     *  the worker's 12h period — a couple of missed runs won't cause churn. */
    private const val STALE_AFTER_MS = 7L * DAY_MS

    /** Guards against two re-registrations running at once (a boot-path heal racing
     *  a user tapping the Settings row). Re-registration talks to Apple, so
     *  overlapping attempts are exactly what we must not do. */
    private val healing = java.util.concurrent.atomic.AtomicBoolean(false)

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
    private const val FIX_ATTEMPTS = 3
    private const val FIX_BACKOFF_MS = 4_000L

    /** Boot connect retry. Short and bounded on purpose — [registerNetworkRecovery]
     *  is the real recovery for a boot that lost the race with WiFi; this only covers
     *  a brief race. 4 attempts with linear backoff ≈ 18s, all on a background thread. */
    private const val BOOT_CONNECT_ATTEMPTS = 4
    private const val BOOT_CONNECT_BACKOFF_MS = 3_000L

    /** Wait out the session's own in-flight connect before forcing our own. The FFI's
     *  internal APNs ladder takes ~7s to exhaust, so ~12s covers it either way. */
    private const val BOOT_CONNECT_GRACE_POLLS = 12
    private const val BOOT_CONNECT_GRACE_MS = 1_000L

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
