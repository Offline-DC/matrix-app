package com.offline.dpadmessenger.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offline.dpadmessenger.data.ConversationStarter
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.ui.chat.ChatScreen
import com.offline.dpadmessenger.ui.chat.ChatViewModel
import com.offline.dpadmessenger.ui.components.LocalCurrentUserId
import com.offline.dpadmessenger.ui.login.LoginScreen
import com.offline.dpadmessenger.ui.newconversation.NewConversationScreen
import com.offline.dpadmessenger.ui.rooms.RoomListScreen
import com.offline.dpadmessenger.ui.rooms.RoomListViewModel
import com.offline.dpadmessenger.ui.settings.SettingsScreen

/**
 * Drop-in entry point: hand it a [MessageRepository], get a fully-wired UI.
 *
 * Usage:
 * ```
 * setContent {
 *     DpadMessengerTheme {
 *         DpadMessengerApp(repository = myRepo)
 *     }
 * }
 * ```
 *
 * The library does not depend on Hilt or Koin so we hand-build ViewModels via
 * a tiny [ViewModelProvider.Factory]. Consumers can swap this for their own
 * factory if they prefer.
 *
 * @param startAuthenticated when true, skips the login screen on launch. The
 *        in-memory demo repo passes true; a real Matrix-backed integration
 *        would pass false and toggle it after auth succeeds.
 * @param onToggleDarkTheme callback the Settings screen wires its switch to.
 *        Pass `null` to hide the toggle.
 */
@Composable
fun DpadMessengerApp(
    repository: MessageRepository,
    modifier: Modifier = Modifier,
    startAuthenticated: Boolean = true,
    darkTheme: Boolean = false,
    onToggleDarkTheme: ((Boolean) -> Unit)? = null,
    /** Host-provided logout. When set (launcher), Settings → Log out calls
     *  this instead of the built-in stub login navigation, so the host can
     *  clear the real pairing and return to its own link screen. */
    onLogout: (() -> Unit)? = null,
    /** Host-provided re-link: the ADAPTIVE recovery path — token-refresh from the
     *  stored cookies first, full re-pair only if Google rejects them. Keeps
     *  message history. Used where a silent recovery is the right answer: the
     *  auth-expired reconnect screen. NOT the failed-message action, which is
     *  user-initiated and uses [onFreshRelink] (see the chat route below). */
    onRelink: (() -> Unit)? = null,
    /** User-initiated re-link — Settings, the day-13 banner that routes there, and
     *  "Re-link phone" on a failed message: ALWAYS a full re-sign-in for a
     *  brand-new ~2-week session, since token refresh can't extend the Google
     *  session ceiling. Keeps history. Falls back to [onRelink] when null. */
    onFreshRelink: (() -> Unit)? = null,
    /** Whole days since the last fresh sign-in. Drives the Settings "last linked"
     *  row and the day-13 "re-link soon" banner on the room list. Null hides both. */
    linkAgeDays: Int? = null,
    /** How long this device keeps messages (hidden when change handler is null). */
    autoDeleteDays: Int = com.offline.dpadmessenger.data.RetentionSettings.DEFAULT_RETENTION_DAYS,
    onAutoDeleteDaysChange: ((Int) -> Unit)? = null,
    /** Send-read-receipts setting (hidden when change handler is null). */
    sendReadReceipts: Boolean = false,
    onSendReadReceiptsChange: ((Boolean) -> Unit)? = null,
    /** 24-hour clock setting (hidden when change handler is null). */
    use24HourTime: Boolean = false,
    onUse24HourTimeChange: ((Boolean) -> Unit)? = null,
    /** iMessage "send from" handles + current default. When [sendHandles] is
     *  non-empty and [onDefaultSendHandleChange] is set, Settings shows a picker
     *  to change the default number/email outgoing texts are sent from. */
    sendHandles: List<String> = emptyList(),
    defaultSendHandle: String = "",
    onDefaultSendHandleChange: ((String) -> Unit)? = null,
    /** "Calling" picker — see the same-named params on [SettingsScreen]. Passed
     *  straight through; this module renders labelled choices without knowing
     *  what they mean. */
    defaultCallingOptions: List<Pair<String, String>> = emptyList(),
    defaultCalling: String = "",
    onDefaultCallingChange: ((String) -> Unit)? = null,
    /** Force an IDS re-registration now (periodic renewal, on demand). Adds a
     *  "Re-register now" row to Settings when set; null hides it. */
    onReregister: (() -> Unit)? = null,
    /** Export Smart Txt logs to support. Adds an "Export logs" row to Settings
     *  (just above Log out) when set; null hides it. */
    onExportLogs: (() -> Unit)? = null,
    /** Deep link: open this conversation on top of the room list (set when
     *  the user tapped a message notification). [initialRoomKey] must change
     *  per tap so a fresh notification re-navigates. */
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
) {
    val nav = rememberNavController()
    val factory = remember(repository) { RepositoryViewModelFactory(repository) }
    var authed by rememberSaveable { mutableStateOf(startAuthenticated) }
    // Set when the user taps the day-13 re-link banner, so Settings lands focus
    // on the Re-link row; cleared when Settings is opened via the cog.
    var focusRelinkOnSettings by remember { mutableStateOf(false) }

    val startRoute = if (authed) Routes.ROOM_LIST else Routes.LOGIN

    // Notification tap → jump straight into that thread. Keyed on the tap
    // nonce so a second notification for the same room still navigates, and on
    // [authed] so a tap that arrives before pairing finishes still lands the
    // user in the thread once they're authenticated (rather than being dropped).
    androidx.compose.runtime.LaunchedEffect(initialRoomKey, authed) {
        if (initialRoomId != null && authed) {
            nav.navigate(Routes.chat(initialRoomId)) {
                // Pop any thread the user was already sitting in back to the room
                // list first. Without this, deep-linking from one chat to another
                // reuses the same "chat/{roomId}" destination (and its existing
                // ChatViewModel, still bound to the old room) — so the tap appeared
                // to "do nothing" and left you in the thread you were already in.
                // Popping to the list guarantees a fresh chat entry for the target.
                popUpTo(Routes.ROOM_LIST)
                launchSingleTop = true
            }
        }
    }

    CompositionLocalProvider(LocalCurrentUserId provides repository.currentUser.id) {
        NavHost(
            navController = nav,
            startDestination = startRoute,
            modifier = modifier,
            // Snap between destinations — no fades. DPAD users on tiny screens
            // shouldn't have to wait through 200ms of cross-fade to use the app.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginSuccess = {
                        authed = true
                        nav.navigate(Routes.ROOM_LIST) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.ROOM_LIST) {
                val vm: RoomListViewModel = viewModel(factory = factory)
                RoomListScreen(
                    viewModel = vm,
                    onRoomClick = { roomId -> nav.navigate(Routes.chat(roomId)) },
                    onSettingsClick = {
                        focusRelinkOnSettings = false
                        nav.navigate(Routes.SETTINGS)
                    },
                    linkAgeDays = linkAgeDays,
                    onRelinkWarningClick = {
                        focusRelinkOnSettings = true
                        nav.navigate(Routes.SETTINGS)
                    },
                    // Only offer "new message" if the repo can actually start
                    // conversations (the mock can't).
                    onNewMessage = if (repository is ConversationStarter) {
                        { nav.navigate(Routes.NEW_CONVERSATION) }
                    } else null,
                )
            }
            composable(Routes.NEW_CONVERSATION) {
                NewConversationScreen(
                    starter = repository as ConversationStarter,
                    contactsSource = repository as? com.offline.dpadmessenger.data.ContactsSource,
                    groupStarter = repository as? com.offline.dpadmessenger.data.GroupConversationStarter,
                    onBack = { nav.popBackStack() },
                    onConversationStarted = { roomId ->
                        nav.navigate(Routes.chat(roomId)) {
                            // Replace the compose screen so Back from the chat
                            // returns to the room list, not the number entry.
                            popUpTo(Routes.NEW_CONVERSATION) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument(Routes.ARG_ROOM_ID) { type = NavType.StringType }),
            ) { backStack ->
                val roomId = backStack.arguments?.getString(Routes.ARG_ROOM_ID) ?: return@composable
                // Remember the factory so we don't recreate the VM on every recomposition.
                val chatFactory = remember(repository, roomId) {
                    ChatViewModelFactory(repository, roomId)
                }
                val vm: ChatViewModel = viewModel(factory = chatFactory)
                // Tell the room list which conversation the user is in, whatever
                // route they took to get here — a list tap, a notification, or a
                // conversation they just started. Back then lands focus on THIS
                // row.
                //
                // The list's own onRoomClick used to be the only thing that set
                // this, so a chat opened from a notification left it pointing at
                // whichever row was last tapped IN the list: open a chat, exit
                // with the red key, tap a notification for a different chat, press
                // Back — and the highlight sat on the older conversation.
                //
                // ROOM_LIST is the start destination and the deep link above pops
                // up to it (not inclusive), so it is on the back stack whenever a
                // chat is; runCatching covers the case where it somehow isn't.
                val roomListEntry = remember(backStack) {
                    runCatching { nav.getBackStackEntry(Routes.ROOM_LIST) }.getOrNull()
                }
                if (roomListEntry != null) {
                    val roomListVm: RoomListViewModel =
                        viewModel(viewModelStoreOwner = roomListEntry, factory = factory)
                    androidx.compose.runtime.LaunchedEffect(roomId) {
                        roomListVm.setLastOpened(roomId)
                    }
                }
                // "Re-link phone" on a failed message is a USER-INITIATED repair,
                // so it gets the same full re-sign-in Settings does.
                //
                // It used to call the adaptive [onRelink], which tries a token
                // refresh from the stored cookies first. That refresh SUCCEEDS
                // against a phone that has been unlinked on the Google Messages
                // side — you get "reauth OK — link restored ... WITHOUT
                // re-pairing", the pairing is still gone, the message still can't
                // send, and the user sees absolutely nothing happen. Pressing a
                // button labelled "Re-link phone" and getting a silent no-op is
                // worse than the failed send itself.
                //
                // The adaptive path still owns the REACTIVE case (the auth-expired
                // reconnect screen), where a silent refresh is exactly right.
                ChatScreen(
                    viewModel = vm,
                    onBack = { nav.popBackStack() },
                    onRelink = onFreshRelink ?: onRelink,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onLogout = {
                        if (onLogout != null) {
                            // Host clears the real session/pairing and swaps
                            // this whole UI out for its link screen.
                            onLogout()
                        } else {
                            authed = false
                            // Clear the entire back stack — there can be a chat
                            // in it from before settings was opened, and the
                            // user shouldn't be able to back into it post-logout.
                            nav.navigate(Routes.LOGIN) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    // Settings re-link is the user-initiated FRESH re-sign-in
                    // (new ~2-week session). Fall back to the adaptive onRelink
                    // if the host didn't supply a fresh variant.
                    onRelink = (onFreshRelink ?: onRelink)?.let { r -> { r(); nav.popBackStack() } },
                    linkAgeDays = linkAgeDays,
                    focusRelinkOnEntry = focusRelinkOnSettings,
                    showDarkThemeToggle = onToggleDarkTheme != null,
                    darkTheme = darkTheme,
                    onDarkThemeChange = { onToggleDarkTheme?.invoke(it) },
                    autoDeleteDays = autoDeleteDays,
                    onAutoDeleteDaysChange = onAutoDeleteDaysChange,
                    sendReadReceipts = sendReadReceipts,
                    onSendReadReceiptsChange = onSendReadReceiptsChange,
                    use24HourTime = use24HourTime,
                    onUse24HourTimeChange = onUse24HourTimeChange,
                    sendHandles = sendHandles,
                    defaultSendHandle = defaultSendHandle,
                    onDefaultSendHandleChange = onDefaultSendHandleChange,
                    defaultCallingOptions = defaultCallingOptions,
                    defaultCalling = defaultCalling,
                    onDefaultCallingChange = onDefaultCallingChange,
                    onReregister = onReregister,
                    onExportLogs = onExportLogs,
                )
            }
        }
    }
}

internal object Routes {
    const val LOGIN = "login"
    const val ROOM_LIST = "rooms"
    const val SETTINGS = "settings"
    const val NEW_CONVERSATION = "new_conversation"
    const val ARG_ROOM_ID = "roomId"
    const val CHAT = "chat/{$ARG_ROOM_ID}"
    fun chat(roomId: String) = "chat/$roomId"
}

internal class RepositoryViewModelFactory(
    private val repository: MessageRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(RoomListViewModel::class.java) ->
                RoomListViewModel(repository) as T
            else -> error("Unknown VM ${modelClass.name}")
        }
    }
}

internal class ChatViewModelFactory(
    private val repository: MessageRepository,
    private val roomId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(repository, roomId) as T
    }
}
