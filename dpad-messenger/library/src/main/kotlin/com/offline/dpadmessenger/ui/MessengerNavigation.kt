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
    /** Auto-delete-old-messages setting (hidden when change handler is null). */
    autoDeleteEnabled: Boolean = true,
    onAutoDeleteChange: ((Boolean) -> Unit)? = null,
    /** 24-hour clock setting (hidden when change handler is null). */
    use24HourTime: Boolean = false,
    onUse24HourTimeChange: ((Boolean) -> Unit)? = null,
    /** Deep link: open this conversation on top of the room list (set when
     *  the user tapped a message notification). [initialRoomKey] must change
     *  per tap so a fresh notification re-navigates. */
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
) {
    val nav = rememberNavController()
    val factory = remember(repository) { RepositoryViewModelFactory(repository) }
    var authed by rememberSaveable { mutableStateOf(startAuthenticated) }

    val startRoute = if (authed) Routes.ROOM_LIST else Routes.LOGIN

    // Notification tap → jump straight into that thread. Keyed on the tap
    // nonce so a second notification for the same room still navigates.
    androidx.compose.runtime.LaunchedEffect(initialRoomKey) {
        if (initialRoomId != null && authed) {
            nav.navigate(Routes.chat(initialRoomId)) { launchSingleTop = true }
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
                    onSettingsClick = { nav.navigate(Routes.SETTINGS) },
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
                ChatScreen(viewModel = vm, onBack = { nav.popBackStack() })
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
                    showDarkThemeToggle = onToggleDarkTheme != null,
                    darkTheme = darkTheme,
                    onDarkThemeChange = { onToggleDarkTheme?.invoke(it) },
                    autoDeleteEnabled = autoDeleteEnabled,
                    onAutoDeleteChange = onAutoDeleteChange,
                    use24HourTime = use24HourTime,
                    onUse24HourTimeChange = onUse24HourTimeChange,
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
