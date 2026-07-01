package com.offline.dpadmessenger.backend.imessage

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.imessage.relay.HttpValidationDataRelay
import com.offline.dpadmessenger.backend.imessage.relay.StubValidationDataRelay
import com.offline.dpadmessenger.backend.imessage.relay.ValidationDataRelay
import com.offline.dpadmessenger.backend.imessage.transport.IMessageTransport
import com.offline.dpadmessenger.backend.imessage.transport.MockRelayTransport
import com.offline.dpadmessenger.backend.imessage.transport.NativeRustPushTransport
import com.offline.dpadmessenger.backend.imessage.transport.RegisterResult
import com.offline.dpadmessenger.backend.imessage.transport.RelayWebSocketTransport
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Factory + process-scoped holder for the iMessage backend. Mirror of
 * `GoogleMessagesRepository`.
 *
 * Owns the singleton chain transport → [IMessageSession] → repository (a
 * per-caller instance would open duplicate connections and double-notify), the
 * [register]/[renew] entry points the setup screen + renewal worker call, and
 * the [status] the UI gates on.
 *
 * The transport is chosen from [IMessageConfig.transportMode]/[relayBaseUrl]:
 *  - a real [RelayWebSocketTransport] when a relay URL is set,
 *  - a [NativeRustPushTransport] for the on-device rustpush path (Phase B),
 *  - otherwise the in-process [MockRelayTransport] so the whole stack runs and
 *    is testable today.
 */
object IMessageRepository {

    @Volatile private var transport: IMessageTransport? = null
    @Volatile private var session: IMessageSession? = null
    @Volatile private var instance: MessageRepository? = null
    @Volatile private var bridge: RustPushBridge? = null

    private val _status = MutableStateFlow(IMessageStatus.UNREGISTERED)
    val status: StateFlow<IMessageStatus> = _status.asStateFlow()

    /** Validation-data relay for the NATIVE path only (the WebSocket relay does
     *  validation server-side). */
    private fun buildValidationRelay(): ValidationDataRelay {
        val base = IMessageConfig.validationRelayBaseUrl
        return if (base.isNotBlank()) HttpValidationDataRelay(base, IMessageConfig.validationRelayAuthToken)
        else StubValidationDataRelay()
    }

    @Synchronized
    fun bridge(): RustPushBridge = bridge ?: RustPushBridge(buildValidationRelay()).also { bridge = it }

    /** Build the active transport from config. Relay URL set ⇒ real relay;
     *  NATIVE mode ⇒ rustpush bridge; else the in-process mock. */
    @Synchronized
    private fun transport(): IMessageTransport = transport ?: run {
        val mode = when {
            IMessageConfig.relayBaseUrl.isNotBlank() -> IMessageConfig.TransportMode.RELAY
            else -> IMessageConfig.transportMode
        }
        val t: IMessageTransport = when (mode) {
            IMessageConfig.TransportMode.RELAY ->
                RelayWebSocketTransport(IMessageConfig.relayBaseUrl, IMessageConfig.relayAuthToken)
            IMessageConfig.TransportMode.NATIVE ->
                if (RustPushBridge.NATIVE_AVAILABLE) NativeRustPushTransport(bridge()) else MockRelayTransport()
            IMessageConfig.TransportMode.MOCK -> MockRelayTransport()
        }
        Log.i(TAG, "transport = ${t::class.java.simpleName} (mode=$mode)")
        t.also { transport = it }
    }

    @Synchronized
    fun session(): IMessageSession = session ?: IMessageSession(transport()).also { session = it }

    /**
     * The shared repository. Builds the transport+session on first call and
     * connects. Returns the chat repository regardless of registration state —
     * the UI gates the chat behind [status].
     */
    @Synchronized
    fun create(context: Context): MessageRepository {
        instance?.let { return it }
        val appContext = context.applicationContext
        val store = IMessageAccountStore(appContext)
        _status.value = when {
            store.isRegistered() -> IMessageStatus.REGISTERED
            store.isSeeded() -> IMessageStatus.SEEDED
            else -> IMessageStatus.UNREGISTERED
        }
        return IMessageMessageRepository(session(), appContext).also { instance = it }
    }

    /** Observe whether the link died (so the UI can show a reconnect prompt). */
    fun authExpiredFlow(): StateFlow<Boolean>? =
        (instance as? IMessageMessageRepository)?.authExpired

    /**
     * Register this identity with Apple via the active transport (the relay
     * obtains validation data + runs IDS registration; the native path runs the
     * §2.2 four-call sequence on-device). Persists the dumb file + account,
     * stamps it registered, schedules renewal, starts the push service, and
     * flips [status] to REGISTERED.
     */
    suspend fun register(context: Context, config: MacOSConfig, appleId: String): RegistrationResult {
        val appContext = context.applicationContext
        val store = IMessageAccountStore(appContext)
        store.saveConfig(config)
        _status.value = IMessageStatus.REGISTERING
        return when (val result = session().register(config, appleId)) {
            is RegisterResult.Success -> {
                val account = IMessageAccount(
                    appleId = appleId,
                    identityTokenB64 = "",
                    pushTokenB64 = "",
                    lastRegisteredMs = System.currentTimeMillis(),
                    handles = result.handles,
                )
                store.saveAccount(account)
                store.markRegistered(account.lastRegisteredMs)
                IMessageRenewalWorker.schedule(appContext)
                APNsForegroundService.start(appContext)
                _status.value = IMessageStatus.REGISTERED
                RegistrationResult.Success(account)
            }
            is RegisterResult.Failure -> {
                _status.value = IMessageStatus.UNREGISTERED
                Log.w(TAG, "registration failed: ${result.message}")
                RegistrationResult.Failure(result.message)
            }
        }
    }

    /** Re-register (renewal). Called by [IMessageRenewalWorker]. */
    suspend fun renew(context: Context): RegistrationResult {
        val store = IMessageAccountStore(context.applicationContext)
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

    /** Connect the live session (called by the foreground service / on create). */
    fun connect(context: Context) {
        create(context) // building the repository connects the session
    }

    @Synchronized
    fun shutdown(context: Context, wipe: Boolean) {
        (instance as? IMessageMessageRepository)?.shutdown(clearCache = wipe)
        session?.shutdown()
        instance = null
        session = null
        transport = null
        bridge = null
        if (wipe) {
            IMessageAccountStore(context.applicationContext).clear()
            _status.value = IMessageStatus.UNREGISTERED
        }
    }

    private const val TAG = "IMsgRepoHolder"
}

/** Where the iMessage identity is in its lifecycle. The UI gates on this. */
enum class IMessageStatus {
    /** No dumb file / account. Show setup. */
    UNREGISTERED,
    /** Dumb file + account imported but `register` not yet run. */
    SEEDED,
    /** Registration in progress. */
    REGISTERING,
    /** Registered with IDS — show the chat. */
    REGISTERED,
}
