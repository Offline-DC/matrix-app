package com.offline.dpadmessenger.ui.rooms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.components.RoomListItem

/**
 * Top-level room list. DPAD Up/Down moves between rooms; OK opens the chat.
 * Settings cog in the top bar; DPAD-Down from the cog returns to the chat
 * list. The most recently opened conversation gets the initial focus on
 * return, not the first row, so the user lands where they left off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    viewModel: RoomListViewModel,
    onRoomClick: (roomId: String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    topBarTitle: String = "Chats",
) {
    val rooms by viewModel.rooms.collectAsState()
    val lastOpenedRoomId by viewModel.lastOpenedRoomId.collectAsState()
    // Two distinct focus targets:
    //  - entryRowFocus is attached to the last-opened row so screen entry
    //    lands the user where they left off.
    //  - firstRowFocus is ALWAYS attached to row 0 so the settings cog can
    //    hand focus to the top of the list regardless of last-opened.
    // When the last-opened IS row 0, both requesters point at the same row.
    val entryRowFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }

    Scaffold(
        topBar = {
            CompactTopBar(
                title = topBarTitle,
                actions = {
                    CompactBarButton(
                        onClick = onSettingsClick,
                        extraModifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                runCatching { firstRowFocus.requestFocus() }
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
        if (rooms.isEmpty()) {
            EmptyState(innerPadding)
        } else {
            RoomList(
                rooms = rooms,
                lastOpenedRoomId = lastOpenedRoomId,
                senderNameFor = viewModel::senderName,
                onRoomClick = { roomId ->
                    viewModel.setLastOpened(roomId)
                    onRoomClick(roomId)
                },
                padding = innerPadding,
                entryRowFocus = entryRowFocus,
                firstRowFocus = firstRowFocus,
            )
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
) {
    val listState = rememberTvLazyListState()

    // Which row should be focused on screen entry: the last opened one if it
    // still exists, otherwise the first row.
    val entryRoomId = lastOpenedRoomId?.takeIf { id -> rooms.any { it.room.id == id } }
        ?: rooms.first().room.id
    val firstRoomId = rooms.first().room.id

    LaunchedEffect(entryRoomId) {
        if (rooms.isNotEmpty()) {
            // Scroll the entry row into view before focusing — otherwise
            // requestFocus() on an unmeasured row is a no-op.
            val idx = rooms.indexOfFirst { it.room.id == entryRoomId }
            if (idx >= 0) listState.scrollToItem(idx)
            try {
                entryRowFocus.requestFocus()
            } catch (_: IllegalStateException) {
                // Focus target not yet attached; safe to ignore.
            }
        }
    }
    TvLazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
            start = 8.dp,
            end = 8.dp,
        ),
        modifier = Modifier.fillMaxSize(),
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

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text("No conversations yet")
    }
}
