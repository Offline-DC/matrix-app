package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtAccountStore
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtRepository
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtStatus
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.util.TimeFormatPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level entry for the in-app SmartTxt experience. Mirror of
 * `GoogleMessagesApp`:
 *  - REGISTERED → the reused chat UI ([DpadMessengerApp]) backed by the live
 *    SmartTxt repository, with settings (auto-delete, 12/24h) and logout wired.
 *  - anything else → [SmartTxtSetupScreen] (register against the relay).
 *
 * The chat UI and the setup UI share the same Activity/host — just a state swap
 * on [SmartTxtRepository.status].
 */
@Composable
fun SmartTxtApp(
    modifier: Modifier = Modifier,
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
) {
    val context = LocalContext.current
    // Restore a previous sign-in on a fresh process BEFORE reading status, so a
    // returning user lands on their chats instead of the setup screen.
    remember { SmartTxtRepository.restoreStatus(context) }
    val status by SmartTxtRepository.status.collectAsState()
    // Post-login handle picker: shown once after registration, before the chats.
    var handlesConfigured by remember { mutableStateOf(SmartTxtAccountStore(context).handlesConfigured()) }

    when {
        status == SmartTxtStatus.REGISTERED && !handlesConfigured ->
            HandlePickerScreen(modifier = modifier, onContinue = { handlesConfigured = true })

        status == SmartTxtStatus.REGISTERED -> {
            // Build the repository OFF the main thread: create() triggers the heavy
            // one-time nativeInit (file I/O + keystore + tokio runtime), which would
            // block the UI thread and ANR if run during composition. On a native
            // failure create() returns null and sets nativeError — we show that
            // error, never the mock demo transport.
            val nativeError by SmartTxtRepository.nativeError.collectAsState()
            var repository by remember { mutableStateOf<MessageRepository?>(null) }
            var buildAttempt by remember { mutableStateOf(0) }
            LaunchedEffect(buildAttempt) {
                repository = withContext(Dispatchers.IO) { SmartTxtRepository.create(context) }
            }
            val repo = repository
            val err = nativeError
            when {
                err != null -> SmartTxtErrorScreen(
                    message = err,
                    onRetry = {
                        SmartTxtRepository.clearNativeError()
                        repository = null
                        buildAttempt++
                    },
                    modifier = modifier,
                )
                repo == null -> DuckLoadingIndicator(modifier = modifier)
                else -> SmartTxtChat(
                    repository = repo,
                    modifier = modifier,
                    initialRoomId = initialRoomId,
                    initialRoomKey = initialRoomKey,
                )
            }
        }

        else -> SmartTxtSetupScreen(modifier = modifier)
    }
}

/** The signed-in chat experience. Split out from [SmartTxtApp] so the repository
 *  (and its heavy native init) can be built off the main thread first. */
@Composable
private fun SmartTxtChat(
    repository: MessageRepository,
    modifier: Modifier,
    initialRoomId: String?,
    initialRoomKey: Any?,
) {
    val context = LocalContext.current
    val retention = repository as? RetentionSettings
    val scope = rememberCoroutineScope()

    // If the relay link died and couldn't refresh, show a reconnect prompt instead
    // of a chat that silently can't send (like gmessages).
    val authExpired = SmartTxtRepository.authExpiredFlow()?.collectAsState()?.value ?: false
    if (authExpired) {
        SmartTxtReconnectScreen(
            onReconnect = { scope.launch { SmartTxtRepository.session().reauth() } },
            modifier = modifier,
        )
        return
    }

    val autoDelete = retention?.autoDeleteEnabled?.collectAsState()?.value ?: true

    // "Send from" default handle: the registered numbers/emails, the saved default,
    // and a setter that persists + pushes it to the native send path.
    val accountStore = remember { SmartTxtAccountStore(context) }
    val sendHandles = remember { accountStore.loadAccount()?.handles.orEmpty() }
    var defaultHandle by remember {
        mutableStateOf(
            accountStore.defaultHandle()
                ?: sendHandles.firstOrNull { it.startsWith("tel:") }
                ?: sendHandles.firstOrNull()
                ?: ""
        )
    }
    // Re-apply the saved default to the native runtime on (re)entry (off-main; the
    // native call can block on a lock). AppState.send_handle resets on process death.
    LaunchedEffect(defaultHandle) {
        if (defaultHandle.isNotBlank()) {
            withContext(Dispatchers.IO) { RustPushNative.runCatchingNativeSetSendHandle(defaultHandle) }
        }
    }

    val settingsPrefs = remember {
        context.applicationContext.getSharedPreferences("smarttxt_settings", android.content.Context.MODE_PRIVATE)
    }
    var use24Hour by remember {
        val saved = settingsPrefs.getBoolean("use24HourTime", false)
        TimeFormatPreference.use24Hour = saved
        mutableStateOf(saved)
    }

    DpadMessengerApp(
        repository = repository,
        modifier = modifier,
        onLogout = { SmartTxtRepository.shutdown(context, wipe = true) },
        // "Re-link phone" and "Days since last link" are intentionally left off for
        // the demo: omitting onRelink/onFreshRelink/linkAgeDays (all default to
        // null) hides both Settings rows. There's no real ~2-week IDS session here.
        autoDeleteEnabled = autoDelete,
        onAutoDeleteChange = retention?.let { r -> { enabled: Boolean -> r.setAutoDeleteEnabled(enabled) } },
        use24HourTime = use24Hour,
        onUse24HourTimeChange = { enabled ->
            use24Hour = enabled
            TimeFormatPreference.use24Hour = enabled
            settingsPrefs.edit().putBoolean("use24HourTime", enabled).apply()
        },
        sendHandles = sendHandles,
        defaultSendHandle = defaultHandle,
        onDefaultSendHandleChange = if (sendHandles.size > 1) { h: String ->
            defaultHandle = h
            scope.launch(Dispatchers.IO) {
                accountStore.saveHandleSelection(enabled = sendHandles, default = h)
                RustPushNative.runCatchingNativeSetSendHandle(h)
            }
            Unit
        } else null,
        initialRoomId = initialRoomId,
        initialRoomKey = initialRoomKey,
    )
}
