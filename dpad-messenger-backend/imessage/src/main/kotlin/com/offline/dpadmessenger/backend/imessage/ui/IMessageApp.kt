package com.offline.dpadmessenger.backend.imessage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.backend.imessage.IMessageMessageRepository
import com.offline.dpadmessenger.backend.imessage.IMessageRepository
import com.offline.dpadmessenger.backend.imessage.IMessageStatus
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.util.TimeFormatPreference
import kotlinx.coroutines.launch

/**
 * Top-level entry for the in-app iMessage experience. Mirror of
 * `GoogleMessagesApp`:
 *  - REGISTERED → the reused chat UI ([DpadMessengerApp]) backed by the live
 *    iMessage repository, with settings (auto-delete, 12/24h) and logout wired.
 *  - anything else → [IMessageSetupScreen] (register against the relay).
 *
 * The chat UI and the setup UI share the same Activity/host — just a state swap
 * on [IMessageRepository.status].
 */
@Composable
fun IMessageApp(
    modifier: Modifier = Modifier,
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
) {
    val context = LocalContext.current
    val status by IMessageRepository.status.collectAsState()

    when (status) {
        IMessageStatus.REGISTERED -> {
            val repository = remember { IMessageRepository.create(context) }
            val retention = repository as? RetentionSettings
            val scope = rememberCoroutineScope()

            // If the relay link died and couldn't refresh, show a reconnect
            // prompt instead of a chat that silently can't send (like gmessages).
            val authExpired = IMessageRepository.authExpiredFlow()?.collectAsState()?.value ?: false
            if (authExpired) {
                IMessageReconnectScreen(
                    onReconnect = { scope.launch { IMessageRepository.session().reauth() } },
                    modifier = modifier,
                )
                return
            }

            val autoDelete = retention?.autoDeleteEnabled?.collectAsState()?.value ?: true

            val settingsPrefs = remember {
                context.applicationContext.getSharedPreferences("imessage_settings", android.content.Context.MODE_PRIVATE)
            }
            var use24Hour by remember {
                val saved = settingsPrefs.getBoolean("use24HourTime", false)
                TimeFormatPreference.use24Hour = saved
                mutableStateOf(saved)
            }

            DpadMessengerApp(
                repository = repository,
                modifier = modifier,
                onLogout = { IMessageRepository.shutdown(context, wipe = true) },
                onRelink = { scope.launch { IMessageRepository.session().reauth() } },
                autoDeleteEnabled = autoDelete,
                onAutoDeleteChange = retention?.let { r -> { enabled: Boolean -> r.setAutoDeleteEnabled(enabled) } },
                use24HourTime = use24Hour,
                onUse24HourTimeChange = { enabled ->
                    use24Hour = enabled
                    TimeFormatPreference.use24Hour = enabled
                    settingsPrefs.edit().putBoolean("use24HourTime", enabled).apply()
                },
                initialRoomId = initialRoomId,
                initialRoomKey = initialRoomKey,
            )
        }

        else -> IMessageSetupScreen(modifier = modifier)
    }
}
