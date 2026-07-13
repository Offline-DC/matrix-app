package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
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
        val store = SmartTxtAccountStore(context.applicationContext)
        _status.value = when {
            store.isRegistered() -> SmartTxtStatus.REGISTERED
            store.isSeeded() -> SmartTxtStatus.SEEDED
            else -> SmartTxtStatus.UNREGISTERED
        }
        Log.i(TAG, "restoreStatus → ${_status.value}")
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
                Log.e(TAG, "create: nativeInit failed (no migrated identity/dumb)")
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
     * stamps it registered, schedules renewal, starts the push service, and
     * flips [status] to REGISTERED.
     */
    suspend fun register(context: Context, config: MacOSConfig, appleId: String): RegistrationResult {
        val appContext = context.applicationContext
        if (!ensureNativeInit(appContext)) {
            Log.e(TAG, "register: nativeInit failed — no migrated identity/dumb")
            return RegistrationResult.Failure(IDENTITY_MISSING_MESSAGE)
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
    ): RegistrationResult {
        val appContext = context.applicationContext
        if (!ensureNativeInit(appContext)) {
            Log.e(TAG, "register: nativeInit failed — no migrated identity/dumb")
            return RegistrationResult.Failure(IDENTITY_MISSING_MESSAGE)
        }
        val store = SmartTxtAccountStore(appContext)
        store.saveConfig(config)
        _status.value = SmartTxtStatus.REGISTERING
        val request = RegisterRequest(
            config = config,
            appleId = appleId,
            password = password,
            twoFactorProvider = twoFactorProvider,
        )
        return finishRegister(appContext, store, appleId, session().register(request))
    }

    /** Shared post-register persistence for both [register] overloads: on
     *  success save the account, stamp it registered, schedule renewal, start
     *  the push service, and flip [status]; on failure roll [status] back. */
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
            APNsForegroundService.start(appContext)
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
     *  flip [status], schedule renewal, and start the push service — the same tail
     *  as [finishRegister] minus the live register call. */
    fun markRegisteredExternally(context: Context) {
        val appContext = context.applicationContext
        SmartTxtRenewalWorker.schedule(appContext)
        APNsForegroundService.start(appContext)
        _status.value = SmartTxtStatus.REGISTERED
        Log.i(TAG, "markRegisteredExternally → REGISTERED")
    }

    /** Connect the live session (called by the foreground service / on create). */
    fun connect(context: Context) {
        create(context) // building the repository connects the session
    }

    /** Bring the background push connection up on launcher start if the user is
     *  already signed in, so messages sync whenever the launcher process and the
     *  phone are on — not only while the Smart Txt screen is open. Safe to call on
     *  every launch: the foreground service (START_STICKY) and connect are
     *  idempotent. Missed messages from while the phone was OFF aren't backfilled,
     *  but anything APNs queued while briefly disconnected replays on reconnect. */
    fun startBackgroundSyncIfRegistered(context: Context) {
        val appContext = context.applicationContext
        // Off the main thread so reading EncryptedSharedPreferences + starting the
        // service never blocks the launcher's Application.onCreate (ANR risk).
        Thread {
            if (!SmartTxtAccountStore(appContext).isRegistered()) return@Thread
            restoreStatus(appContext)
            runCatching { APNsForegroundService.start(appContext) }
                .onFailure { Log.w(TAG, "background sync start on boot failed: ${it.message}") }
        }.start()
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

    /** Shown whenever nativeInit fails. With the relay removed, that happens for
     *  exactly one reason: the migrated device identity (os_config.plist) and/or the
     *  `dumb` file aren't on disk, so NAC validation can't run. Naming the dumb file
     *  makes the real cause obvious instead of a downstream "APNs connect failed". */
    const val IDENTITY_MISSING_MESSAGE =
        "Can't start iMessage — the device identity (the OpenBubbles “dumb” file) wasn't transferred. " +
        "Reopen the app to run the OpenBubbles transfer (OpenBubbles must be installed and signed in), then try again."
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
