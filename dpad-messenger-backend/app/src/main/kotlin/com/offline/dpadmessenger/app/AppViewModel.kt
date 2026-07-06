package com.offline.dpadmessenger.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.backend.core.BackendConfig
import com.offline.dpadmessenger.backend.core.BackendFactory
import com.offline.dpadmessenger.backend.core.MockBackendFactory
import com.offline.dpadmessenger.backend.core.SessionStore
import com.offline.dpadmessenger.backend.imessage.IMessageBackendFactory
import com.offline.dpadmessenger.backend.imessage.IMessageConfig
import com.offline.dpadmessenger.backend.signal.SignalAccountStore
import com.offline.dpadmessenger.backend.signal.SignalBackendFactory
import com.offline.dpadmessenger.backend.signal.SignalProvisioningClient
import com.offline.dpadmessenger.backend.signal.SignalProvisioningResult
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Top-level state machine for the wired app.
 *
 * On boot:
 *  1. If a [SignalAccountStore] entry exists → Signal mode, hydrate
 *     [SignalMessageRepository].
 *  2. Else if a Matrix session is persisted (when matrix module enabled) →
 *     Matrix mode.
 *  3. Else → Mock mode (default).
 *
 * The user can switch modes:
 *  - [useMockBackend]: clears everything, falls back to mock.
 *  - [linkSignalDevice]: starts the QR-link flow; transitions to
 *    [RepoState.LinkingSignal] until provisioning completes, then to
 *    [RepoState.Ready].
 *  - [signIn]: Matrix login (requires matrix module enabled).
 */
class AppViewModel(
    private val appContext: Context,
) : ViewModel() {

    private val sessionStore = SessionStore(appContext)
    private val signalAccountStore = SignalAccountStore(appContext)
    private val imessageRelayStore = IMessageRelayStore(appContext)
    private val mockFactory: BackendFactory = MockBackendFactory()
    private val signalFactory: BackendFactory = SignalBackendFactory()
    private val imessageFactory: BackendFactory = IMessageBackendFactory()
    private val matrixFactory: BackendFactory? = matrixFactoryOrNull()

    private val _state = MutableStateFlow<RepoState>(RepoState.Loading)
    val state: StateFlow<RepoState> = _state.asStateFlow()

    /** Live during [RepoState.LinkingSignal]. */
    private var provisioningClient: SignalProvisioningClient? = null

    init {
        viewModelScope.launch {
            // Order: Signal first (most specific), then iMessage (if a relay is
            // configured), then Matrix, then mock.
            val signalRepo = trySignal()
            if (signalRepo != null) {
                _state.value = RepoState.Ready(signalRepo, mode = BackendMode.Signal)
            } else {
                val imessageRepo = tryIMessage()
                if (imessageRepo != null) {
                    _state.value = RepoState.Ready(imessageRepo, mode = BackendMode.IMessage)
                } else {
                    val matrixRepo = matrixFactory?.let { tryRestoreMatrix(it) }
                    if (matrixRepo != null) {
                        _state.value = RepoState.Ready(matrixRepo, mode = BackendMode.Matrix)
                    } else {
                        loadMock()
                    }
                }
            }
            startPhantomTraffic()
        }
    }

    fun useMockBackend() {
        viewModelScope.launch {
            sessionStore.clear()
            signalAccountStore.clear()
            imessageRelayStore.clear()
            provisioningClient?.cancel()
            provisioningClient = null
            loadMock()
        }
    }

    /**
     * Connect to a running `imessage-relay` daemon and switch to iMessage mode.
     * The daemon owns Apple registration (dumb file + Apple ID + 2FA), so all
     * this needs is the daemon's URL (e.g. `http://192.168.1.10:8765`) and its
     * optional bearer token. The Signal-like shared UI then drives iMessage.
     * The connection is persisted so the app reconnects on boot.
     */
    fun useIMessageBackend(relayBaseUrl: String, authToken: String?) {
        viewModelScope.launch {
            _state.value = RepoState.Loading
            IMessageConfig.relayBaseUrl = relayBaseUrl.trim()
            IMessageConfig.relayAuthToken = authToken?.trim()?.ifBlank { null }
            IMessageConfig.transportMode =
                if (relayBaseUrl.isNotBlank()) IMessageConfig.TransportMode.RELAY
                else IMessageConfig.TransportMode.MOCK
            imessageRelayStore.save(relayBaseUrl, authToken)
            val repo = runCatching {
                imessageFactory.create(appContext, BackendConfig.IMessageNative)
            }.getOrElse {
                _state.value = RepoState.Error(
                    "iMessage connect failed: ${it.message ?: it::class.java.simpleName}"
                )
                return@launch
            }
            if (repo != null) {
                _state.value = RepoState.Ready(repo, mode = BackendMode.IMessage)
            } else {
                _state.value = RepoState.Error("iMessage backend returned no repository.")
            }
        }
    }

    /**
     * Begin linking this device to the user's primary Signal account.
     * UI should observe [state] and render [SignalLinkScreen] while
     * the flow is in [RepoState.LinkingSignal].
     */
    fun linkSignalDevice() {
        viewModelScope.launch {
            val client = SignalProvisioningClient(appContext)
            provisioningClient = client
            _state.value = RepoState.LinkingSignal(SignalProvisioningResult.Idle)
            // Mirror provisioning state into our outer state machine.
            launch {
                client.state.collect { result ->
                    _state.value = RepoState.LinkingSignal(result)
                    if (result is SignalProvisioningResult.Linked) {
                        signalAccountStore.save(result.account)
                        val repo = signalFactory.create(appContext, BackendConfig.SignalDirect)
                        if (repo != null) {
                            _state.value = RepoState.Ready(repo, mode = BackendMode.Signal)
                        }
                    }
                }
            }
            client.start()
        }
    }

    fun cancelSignalLink() {
        provisioningClient?.cancel()
        provisioningClient = null
        viewModelScope.launch { loadMock() }
    }

    fun signIn(homeserverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            val factory = matrixFactory
            if (factory == null) {
                _state.value = RepoState.Error(
                    "Matrix backend not enabled. Uncomment :matrix in app/build.gradle.kts."
                )
                return@launch
            }
            _state.value = RepoState.Loading
            val repo = runCatching {
                signInViaReflection(homeserverUrl, username, password)
                factory.create(appContext, BackendConfig.RemoteMatrix(homeserverUrl))
            }.getOrElse {
                _state.value = RepoState.Error("Sign-in failed: ${it.message ?: it::class.java.simpleName}")
                return@launch
            }
            if (repo != null) {
                _state.value = RepoState.Ready(repo, mode = BackendMode.Matrix)
            } else {
                _state.value = RepoState.Error("Sign-in returned no session.")
            }
        }
    }

    private suspend fun loadMock() {
        val repo = mockFactory.create(appContext, BackendConfig.Mock)
            ?: error("Mock factory returned null — check assets/mock_data.json")
        _state.value = RepoState.Ready(repo, mode = BackendMode.Mock)
    }

    private suspend fun trySignal(): MessageRepository? {
        if (signalAccountStore.load() == null) return null
        return runCatching { signalFactory.create(appContext, BackendConfig.SignalDirect) }
            .getOrNull()
    }

    private suspend fun tryIMessage(): MessageRepository? {
        val url = imessageRelayStore.url() ?: return null
        IMessageConfig.relayBaseUrl = url
        IMessageConfig.relayAuthToken = imessageRelayStore.token()
        IMessageConfig.transportMode = IMessageConfig.TransportMode.RELAY
        return runCatching { imessageFactory.create(appContext, BackendConfig.IMessageNative) }
            .getOrNull()
    }

    private suspend fun tryRestoreMatrix(factory: BackendFactory): MessageRepository? {
        val session = sessionStore.load() ?: return null
        return runCatching {
            factory.create(appContext, BackendConfig.RemoteMatrix(session.homeserverUrl))
        }.getOrNull()
    }

    private fun startPhantomTraffic() {
        viewModelScope.launch {
            val canned = listOf(
                "r1" to ("alice" to "Hey, are you free tonight?"),
                "r2" to ("bob" to "Anyone got the doc?"),
                "r3" to ("dave" to "Order's out for delivery."),
                "r4" to ("eve" to "Standup pushed to 9:30."),
            )
            var i = 0
            while (true) {
                delay(12_000)
                val current = (_state.value as? RepoState.Ready)?.repository ?: continue
                if (current is com.offline.dpadmessenger.data.InMemoryMessageRepository) {
                    val (room, senderAndBody) = canned[i % canned.size]
                    runCatching { current.simulateIncoming(room, senderAndBody.first, senderAndBody.second) }
                    i++
                }
            }
        }
    }
}

sealed class RepoState {
    data object Loading : RepoState()
    data class Ready(val repository: MessageRepository, val mode: BackendMode) : RepoState()
    data class LinkingSignal(val provisioning: SignalProvisioningResult) : RepoState()
    data class Error(val message: String) : RepoState()
}

enum class BackendMode { Mock, Matrix, Signal, IMessage }

class AppViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppViewModel(context.applicationContext) as T
}

// ---- matrix module hook (unchanged from earlier) ---------------------------

private fun matrixFactoryOrNull(): BackendFactory? {
    return try {
        val cls = Class.forName("com.offline.dpadmessenger.backend.matrix.MatrixBackendFactory")
        cls.getDeclaredConstructor().newInstance() as BackendFactory
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: Throwable) {
        null
    }
}

private suspend fun signInViaReflection(
    homeserverUrl: String,
    username: String,
    password: String,
) {
    Class.forName("com.offline.dpadmessenger.backend.matrix.MatrixAuth")
    throw IllegalStateException(
        "Matrix module is on the classpath but the login call site needs " +
            "wiring — see app/src/main/kotlin/com/offline/dpadmessenger/app/" +
            "AppViewModel.kt::signInViaReflection."
    )
}
