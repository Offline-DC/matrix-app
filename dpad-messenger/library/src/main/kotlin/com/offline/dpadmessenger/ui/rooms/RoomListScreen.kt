package com.offline.dpadmessenger.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.components.RoomListItem
import com.offline.dpadmessenger.ui.settings.RELINK_WARN_DAYS

/**
 * Top-level room list. DPAD Up/Down moves between rooms; OK opens the chat.
 * Settings cog in the top bar; DPAD-Down from the cog returns to the chat
 * list. The most recently opened conversation gets the initial focus on
 * return, not the first row, so the user lands where they left off.
 *
 * A floating "new message" compose button sits bottom-right. From any list
 * row, DPAD-Right focuses it; DPAD-Left returns to the list. OK starts a new
 * conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    viewModel: RoomListViewModel,
    onRoomClick: (roomId: String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    topBarTitle: String = "Chats",
    /** Open the new-conversation flow. When null, the compose button is hidden
     *  (repository can't start conversations — e.g. the mock). */
    onNewMessage: (() -> Unit)? = null,
    /** Whole days since the last fresh sign-in. At/after [RELINK_WARN_DAYS] a red
     *  "re-link in settings" banner is pinned above the list. Null hides it. */
    linkAgeDays: Int? = null,
    /** Tapping the day-13 re-link banner. Defaults to [onSettingsClick]; the host
     *  passes a variant that opens Settings focused on the Re-link row. */
    onRelinkWarningClick: (() -> Unit)? = null,
) {
    val rooms by viewModel.rooms.collectAsState()
    val isLoading by viewModel.isInitialLoading.collectAsState()
    val lastOpenedRoomId by viewModel.lastOpenedRoomId.collectAsState()
    // Two distinct focus targets:
    //  - entryRowFocus is attached to the last-opened row so screen entry
    //    lands the user where they left off.
    //  - firstRowFocus is ALWAYS attached to row 0 so the settings cog can
    //    hand focus to the top of the list regardless of last-opened.
    // When the last-opened IS row 0, both requesters point at the same row.
    val entryRowFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val composeButtonFocus = remember { FocusRequester() }
    // The settings cog's own focus handle, so the compose button can hand focus
    // back up to it when the list is empty (otherwise the user would be trapped
    // on the compose button with no row to return to).
    val settingsFocus = remember { FocusRequester() }
    // Hoisted so the settings-cog Down handler can scroll the top row back into
    // composition before focusing it.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CompactTopBar(
                title = topBarTitle,
                actions = {
                    CompactBarButton(
                        onClick = onSettingsClick,
                        extraModifier = Modifier
                            .focusRequester(settingsFocus)
                            .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                if (rooms.isEmpty()) {
                                    // No rows to land on — go straight to the
                                    // compose button (when present) so Down from
                                    // the cog still does something useful.
                                    if (onNewMessage != null) {
                                        scope.launch {
                                            repeat(8) {
                                                withFrameNanos {}
                                                if (runCatching { composeButtonFocus.requestFocus() }.isSuccess) return@launch
                                            }
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                // Move focus into the list. A just-arrived
                                // message can re-sort the list and re-bind row
                                // 0's focus requester this frame, so retry a few
                                // frames; if the top row is scrolled out of
                                // composition, bring it back first. (Without
                                // this, Down from the cog silently no-ops and
                                // the user is stuck on the cog.)
                                scope.launch {
                                    repeat(3) {
                                        withFrameNanos {}
                                        if (runCatching { firstRowFocus.requestFocus() }.isSuccess) return@launch
                                    }
                                    runCatching { listState.scrollToItem(0) }
                                    repeat(8) {
                                        withFrameNanos {}
                                        if (runCatching { firstRowFocus.requestFocus() }.isSuccess) return@launch
                                    }
                                }
                                true
                            } else false
                        },
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        // When the session is near its ~2-week end, pin a red re-link banner
        // above the list (tapping it opens Settings). The top inset is applied
        // once here on the Column so the banner clears the top bar; the list
        // below only needs its bottom inset.
        val showRelinkWarning = linkAgeDays != null && linkAgeDays >= RELINK_WARN_DAYS
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                if (showRelinkWarning) {
                    RelinkWarningBanner(onClick = onRelinkWarningClick ?: onSettingsClick)
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading && rooms.isEmpty() -> LoadingState(innerPadding)
                        rooms.isEmpty() -> EmptyState(innerPadding)
                        else -> RoomList(
                            rooms = rooms,
                            lastOpenedRoomId = lastOpenedRoomId,
                            senderNameFor = viewModel::senderName,
                            onRoomClick = { roomId ->
                                viewModel.setLastOpened(roomId)
                                onRoomClick(roomId)
                            },
                            // Top inset already applied on the Column above.
                            padding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                            entryRowFocus = entryRowFocus,
                            firstRowFocus = firstRowFocus,
                            // DPAD-Right anywhere in the list jumps to the compose button.
                            composeButtonFocus = composeButtonFocus.takeIf { onNewMessage != null },
                            savedScroll = viewModel.savedScroll,
                            onScrollChanged = viewModel::saveScroll,
                            listState = listState,
                        )
                    }
                }
            }

            if (onNewMessage != null) {
                ComposeButton(
                    onClick = onNewMessage,
                    focusRequester = composeButtonFocus,
                    // Leaving the compose button: back to the list normally, but
                    // back up to the settings cog when there are no rows.
                    onLeft = {
                        runCatching {
                            if (rooms.isEmpty()) settingsFocus.requestFocus()
                            else entryRowFocus.requestFocus()
                        }
                    },
                    // DPAD-Up: return to the chat you were last on (the
                    // last-opened row) so you can back out of the compose button
                    // to where you came from without opening "new message".
                    // Falls back to the top row, or the settings cog when the
                    // list is empty. Retried across a few frames because the
                    // target row may not be attached the instant we ask.
                    onUp = {
                        scope.launch {
                            if (rooms.isEmpty()) {
                                runCatching { settingsFocus.requestFocus() }
                                return@launch
                            }
                            repeat(8) {
                                withFrameNanos {}
                                if (runCatching { entryRowFocus.requestFocus() }.isSuccess) return@launch
                            }
                            runCatching { firstRowFocus.requestFocus() }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            bottom = innerPadding.calculateBottomPadding() + 16.dp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun RoomList(
    rooms: List<RoomSummary>,
    lastOpenedRoomId: String?,
    senderNameFor: (String) -> String,
    onRoomClick: (String) -> Unit,
    padding: PaddingValues,
    entryRowFocus: FocusRequester,
    firstRowFocus: FocusRequester,
    composeButtonFocus: FocusRequester?,
    savedScroll: Pair<Int, Int>?,
    onScrollChanged: (index: Int, offset: Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    // Which row should be focused on screen entry: the last opened one if it
    // still exists, otherwise the first row.
    val entryRoomId = lastOpenedRoomId?.takeIf { id -> rooms.any { it.room.id == id } }
        ?: rooms.first().room.id
    val firstRoomId = rooms.first().room.id

    // Run ONCE per screen entry — NOT on every list change. Otherwise an
    // incoming message (which re-sorts the list) would re-run this and yank
    // scroll/focus out from under the user (e.g. while they're on the cog).
    LaunchedEffect(Unit) {
        if (rooms.isNotEmpty()) {
            // Restore the exact scroll position from last time (so returning
            // from a chat lands where the user was, not at the top). Fall back
            // to scrolling the entry row into view on first-ever entry.
            if (savedScroll != null) {
                listState.scrollToItem(savedScroll.first, savedScroll.second)
            } else {
                val idx = rooms.indexOfFirst { it.room.id == entryRoomId }
                if (idx >= 0) listState.scrollToItem(idx)
            }
            // Retry focus across a few frames: when returning from a chat the
            // target row isn't attached on the first frame, so a single
            // requestFocus() silently failed and left the list UNFOCUSED — the
            // first OK then just established focus (the "press OK twice" bug).
            repeat(10) {
                if (runCatching { entryRowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos {}
            }
        }
    }

    // Persist scroll position as the user moves, so it's ready to restore when
    // they come back from a chat.
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) -> onScrollChanged(index, offset) }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
        // Scaffold insets as REAL padding (not contentPadding) so the list
        // viewport ends below the top bar. With contentPadding the rows could
        // scroll underneath the bar, and the focus-driven bringIntoView only
        // scrolls until an item touches the viewport edge — leaving the
        // focused top row half-hidden behind the bar and making the settings
        // gear unreachable. A clipped viewport keeps the focused row (and the
        // path up to the gear) fully visible.
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            )
            // DPAD-Right from any focused row jumps to the floating compose
            // button. Preview so it fires before a row consumes the key.
            .onPreviewKeyEvent { event ->
                if (composeButtonFocus != null &&
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                ) {
                    runCatching { composeButtonFocus.requestFocus() }
                    true
                } else false
            },
    ) {
        items(items = rooms, key = { it.room.id }) { summary ->
            val isEntry = summary.room.id == entryRoomId
            val isFirst = summary.room.id == firstRoomId
            RoomListItem(
                summary = summary,
                senderNameFor = senderNameFor,
                onClick = { onRoomClick(summary.room.id) },
                // Primary requester goes to whichever row needs entry focus.
                focusRequester = if (isEntry) entryRowFocus else null,
                // Row 0 also gets the firstRowFocus handle so the settings cog
                // can hand it focus regardless of last-opened. When isEntry &&
                // isFirst, both requesters point at the same row (which is
                // what we want — they're separate handles to the same target).
                extraFocusRequesters = if (isFirst) listOf(firstRowFocus) else emptyList(),
            )
        }
    }
}

/** Floating "new message" compose button — DPAD reachable (Right to enter,
 *  Left to leave, OK to start a conversation). */
@Composable
private fun ComposeButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(52.dp)
            .focusRequester(focusRequester)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .dpadFocusHighlight(
                shape = CircleShape,
                borderColor = MaterialTheme.colorScheme.onPrimary,
                focusedTint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
            )
            .focusable()
            .onDpadAction { onClick(); true }
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                        onLeft()
                        true
                    }
                    // DPAD-Up leaves the compose button back into the list,
                    // landing on the chat you were last on (see onUp at the call
                    // site) rather than trapping focus on the button.
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                        onUp()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Icon(
            imageVector = Icons.Filled.Create,
            contentDescription = "New message",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Red, DPAD-focusable banner pinned above the chat list when the Google
 *  session is near its ~2-week expiry. OK opens Settings (where Re-link lives).
 *  Reachable by DPAD-Up from the top conversation row. */
@Composable
private fun RelinkWarningBanner(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .dpadRow(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Re-link in settings",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "You may be logged out in the next day as your session expires.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun LoadingState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "New conversations will appear here",
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
