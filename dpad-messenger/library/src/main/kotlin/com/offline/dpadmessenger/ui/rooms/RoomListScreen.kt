package com.offline.dpadmessenger.ui.rooms

import android.util.Log
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Job
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.dpadmessenger.data.RoomSummary
import androidx.compose.ui.platform.LocalFocusManager
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.focus.handOffFocus
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.components.RoomListItem
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors
import com.offline.dpadmessenger.ui.theme.SmartTxtAvatarGray
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys

/**
 * Top-level room list. DPAD Up/Down moves between rooms; OK opens the chat.
 * Settings cog in the top bar; DPAD-Down from the cog returns to the chat
 * list. The most recently opened conversation gets the initial focus on
 * return, not the first row, so the user lands where they left off.
 *
 * "new" on the right soft key opens the new-conversation flow. It replaced a
 * floating compose button that sat bottom-right and had to be reached with
 * DPAD-Right — the action is the same, it just isn't in the way any more. From any list
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
    topBarTitle: String = "Messages",
    /** Open the new-conversation flow. When null, the "new" soft key is hidden
     *  (repository can't start conversations — e.g. the mock). */
    onNewMessage: (() -> Unit)? = null,
) {
    val rooms by viewModel.rooms.collectAsState()
    val isLoading by viewModel.isInitialLoading.collectAsState()
    // True while the backend is pulling in a backlog (the phone was off for a day
    // and the service is replaying what it stored). Shows a small spinner in the
    // header's leading slot — see [CatchUpIndicator].
    val isCatchingUp by viewModel.isCatchingUp.collectAsState()
    val lastOpenedRoomId by viewModel.lastOpenedRoomId.collectAsState()
    val mutedRooms by viewModel.mutedRooms.collectAsState()
    // Set when the user presses-and-holds a conversation: drives the context
    // sheet (mute / delete). Null = no sheet. Only enabled when the repository
    // supports thread actions.
    val threadActionsEnabled = viewModel.supportsThreadActions
    var contextRoom by remember { mutableStateOf<RoomSummary?>(null) }
    // Two distinct focus targets:
    //  - entryRowFocus is attached to the last-opened row so screen entry
    //    lands the user where they left off.
    //  - firstRowFocus is ALWAYS attached to row 0 so the settings cog can
    //    hand focus to the top of the list regardless of last-opened.
    // When the last-opened IS row 0, both requesters point at the same row.
    val entryRowFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    // Hoisted so the settings-cog Down handler can scroll the top row back into
    // composition before focusing it.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Attached to the Box that WRAPS the list rather than to a row inside it: a
    // row's requester unbinds the moment the row scrolls out of composition or the
    // list re-sorts. Kept as a stable handle on the group; nothing requests it
    // today, because the banner being a list row means traversal needs no aiming.
    val listFocus = remember { FocusRequester() }
    // Whether focus is anywhere inside that group. The screen-level rescue below is
    // gated on this so it only ever fires when focus is NOT in the list — pressing
    // Down on the last row must keep doing nothing, not jump you back to the top.
    var listHasFocus by remember { mutableStateOf(false) }
    // Holds the in-flight focus recovery so a repeated Down cancels the previous
    // attempt instead of stacking one moveFocus per press. See handOffFocus.
    val handOffJob = remember { mutableStateOf<Job?>(null) }

    // Tell an onboarding host that the room list is the screen on top, so it
    // may treat OK as "continue" — and stop the moment a thread or settings
    // replaces it (the NavHost disposes this destination). See
    // LocalOnboardingContinue.
    val onboardingContinue = com.offline.dpadmessenger.ui.components.LocalOnboardingContinue.current
    if (onboardingContinue != null) {
        androidx.compose.runtime.DisposableEffect(onboardingContinue) {
            onboardingContinue.showing = true
            onDispose { onboardingContinue.showing = false }
        }
    }

    Scaffold(
        // Soft keys: the two hardware buttons under the screen. "settings" is the
        // profile/cog in the header, "new" is what the floating compose button
        // used to be — both actions now live in exactly one place. No centre key;
        // DPAD_CENTER opens the highlighted conversation, which is the list's own.
        //
        // In the launcher this publishes to the phone's real bar and lays out
        // nothing (so calculateBottomPadding() below is 0); elsewhere it draws the
        // in-app row here, which is why it belongs in bottomBar rather than
        // floating over the content.
        bottomBar = {
            // End of onboarding (see LocalOnboardingContinue): one centre
            // "continue" instead of "settings" / "new". Label only — the host
            // Activity turns OK into continue, because on the empty welcome
            // page there may be no focused node for a Compose handler to hear it.
            if (onboardingContinue != null) {
                SoftKeys(center = SoftKey("continue"))
            } else {
                SoftKeys(
                    left = SoftKey("settings") { onSettingsClick() },
                    right = onNewMessage?.let { SoftKey("new", it) },
                )
            }
        },
        topBar = {
            CompactTopBar(
                title = topBarTitle,
                // iOS/OpenBubbles header: "Messages" centered, profile button
                // floated at the trailing edge, on the same white as the list
                // below it with no rule between the two.
                centerTitle = true,
                seamless = LocalDpadMessengerColors.current.smarttxt,
                // Leading slot: the catch-up spinner, and nothing else. With
                // centerTitle the navigation slot is aligned CenterStart and the
                // title is centered on the BAR, so this appears and disappears
                // without shifting "Messages" — important, because it comes and
                // goes on its own schedule rather than on a tap.
                navigationIcon = if (isCatchingUp) {
                    { CatchUpIndicator() }
                } else {
                    null
                },
                actions = {
                    CompactBarButton(
                        onClick = onSettingsClick,
                        extraModifier = Modifier
                            .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                // Drop into the top of the list — the re-link banner
                                // when it is showing, row 0 otherwise; firstRowFocus is
                                // attached to whichever that is.
                                //
                                // ALWAYS consumes the key. What makes this safe is the
                                // read-back below: handOffFocus verifies focus actually
                                // moved and, when it did not, scrolls the target back
                                // into composition, retries, and finally falls back to
                                // the platform's own focus search. The old code just
                                // requested and returned true, so a request that
                                // silently did nothing stranded the user here.
                                scope.handOffFocus(
                                    target = firstRowFocus,
                                    focusManager = focusManager,
                                    fallback = FocusDirection.Down,
                                    label = "cog->top",
                                    // The read-back. requestFocus() reports nothing,
                                    // so this is the only way to know the hand-off
                                    // actually happened — and the trigger for the
                                    // recovery when it did not.
                                    landed = { listHasFocus },
                                    inFlight = handOffJob,
                                    // Row 0 must be composed for its requester to bind,
                                    // so scroll it back BEFORE retrying rather than
                                    // burning frames on a requester that cannot attach.
                                    prepare = { listState.scrollToItem(0) },
                                )
                            } else false
                        },
                    ) {
                        // OpenBubbles puts a small profile avatar here, not a
                        // settings cog — it still opens Settings (hence the
                        // content description), but reads as "you". The filled
                        // AccountCircle is a person cut OUT of a disc, so tinting
                        // it with the avatar gray gives exactly that: a gray
                        // circle with a white silhouette, matching the initials
                        // avatars in the list below.
                        Icon(
                            Icons.Filled.AccountCircle,
                            contentDescription = "Profile and settings",
                            tint = SmartTxtAvatarGray,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier
            .fillMaxSize()
            // THE RESCUE. onKeyEvent is the POST phase: Compose offers the event to
            // the focused node and its ancestors on the way back up, so this runs
            // only if nothing below consumed it. A Down that reaches here is a Down
            // that did nothing — a dead end, by definition — so it is safe to act on
            // without knowing who dropped it.
            //
            // Gated on !listHasFocus so normal list navigation is untouched: Down on
            // the last row still reaches here unconsumed, and must stay a no-op.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown ||
                    event.key != Key.DirectionDown ||
                    listHasFocus
                ) {
                    return@onKeyEvent false
                }
                val tag = com.offline.dpadmessenger.focus.DPAD_FOCUS_TAG
                // No requester games here. requestFocus() cannot report whether focus
                // MOVED — it does not throw on an unbound requester — so any branch
                // built on `.isSuccess` consumes keys it did not act on. That is the
                // exact trap handOffFocus exists to avoid, and an earlier version of
                // this rescue reintroduced it: it claimed "rescue: row0" while focus
                // sat still, because the requester happened to be unbound.
                //
                // Nothing needs aiming any more. The banner is list item 0, so
                // cog -> banner -> row 0 is ordinary traversal, and anything focused
                // inside the list sets listHasFocus and returns above. What reaches
                // here is a Down that genuinely had nowhere to go.
                // Last resort, and the only branch that reports truthfully: the
                // platform's own focus search. Its result is returned unchanged, so a
                // Down we could not act on stays UNCONSUMED and whatever is below
                // still gets a chance at it.
                val moved = runCatching { focusManager.moveFocus(FocusDirection.Down) }
                    .getOrDefault(false)
                Log.d(tag, "rescue: moveFocus(Down)=$moved rooms=${rooms.size}" +
                    if (moved) "" else "  <-- nothing below to land on")
                moved
            },
    ) { innerPadding ->
        // The top inset is applied once here on the Column; the list below only
        // needs its bottom inset.
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Order matters: both must sit ABOVE the focus target that
                        // focusGroup() installs, or they observe nothing.
                        .onFocusChanged { listHasFocus = it.hasFocus }
                        .focusRequester(listFocus)
                        .focusGroup(),
                ) {
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
                            savedScroll = viewModel.savedScroll,
                            onScrollChanged = viewModel::saveScroll,
                            listState = listState,
                            mutedRooms = mutedRooms,
                            // Press-and-hold a row → open the mute/delete sheet.
                            onRoomLongClick = if (threadActionsEnabled) {
                                { summary -> contextRoom = summary }
                            } else null,
                        )
                    }
                }
            }

            // Press-and-hold context sheet for the selected conversation.
            contextRoom?.let { cr ->
                val roomId = cr.room.id
                val muted = roomId in mutedRooms
                com.offline.dpadmessenger.ui.components.RoomContextSheet(
                    roomName = cr.room.name,
                    isMuted = muted,
                    onToggleMute = { viewModel.setMuted(roomId, !muted) },
                    onDelete = { viewModel.deleteRoom(roomId) },
                    onDismiss = { contextRoom = null },
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
    savedScroll: Pair<Int, Int>?,
    onScrollChanged: (index: Int, offset: Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    mutedRooms: Set<String> = emptySet(),
    onRoomLongClick: ((RoomSummary) -> Unit)? = null,
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
            // Restore the exact scroll position from last time, so returning from
            // a chat lands where the user was rather than at the top.
            if (savedScroll != null) {
                listState.scrollToItem(savedScroll.first, savedScroll.second)
                withFrameNanos {}
            }
            // ...then check the row we're about to focus actually ended up on
            // screen. The saved position goes stale two ways: the list re-sorts
            // when a message arrives, and a chat opened from a NOTIFICATION was
            // never scrolled to in the first place — it is usually at the top
            // while the saved offset is somewhere further down. An off-screen row
            // never composes, so every requestFocus() below fails and the list is
            // left with nothing focused: the "press OK twice" bug, reached by
            // another road. Also covers first-ever entry, where there is no saved
            // position and the entry row may be well down the list.
            // Rooms are now the only list items, so a room's index in `rooms` is its
            // index in the list (the day-13 banner that used to occupy item 0 is gone).
            val entryIdx = rooms.indexOfFirst { it.room.id == entryRoomId }
            if (entryIdx >= 0 &&
                listState.layoutInfo.visibleItemsInfo.none { it.index == entryIdx }
            ) {
                listState.scrollToItem(entryIdx)
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
        // Tight side gutters. The row's DPAD focus border is drawn ON the row's
        // own bounds, so this gutter is the ONLY thing between that border and
        // the screen edge — at 8dp it wasted ~16dp of a 240px-wide screen and
        // pushed the border well inboard. 2dp keeps the halo from bleeding off
        // the display while handing the width back to the row.
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp, start = 2.dp, end = 2.dp),
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
            ),
    ) {
        // The re-link warning is a ROW, not chrome. As a list item it takes part in
        // ordinary d-pad traversal — cog -> row 0 -> row 1 — instead of sitting
        // outside the focus group where Down had to be rescued. It also makes
        // listHasFocus true while focused, so the screen-level rescue correctly
        // stays out of it.
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
                extraFocusRequesters =
                    if (isFirst) listOf(firstRowFocus) else emptyList(),
                onLongClick = onRoomLongClick?.let { handler -> { handler(summary) } },
                isMuted = summary.room.id in mutedRooms,
                // No rule under the final row — it would hang below the list
                // rather than separate two conversations.
                showDivider = summary.room.id != rooms.lastOrNull()?.room?.id,
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

/**
 * The small spinner in the top-left of the Messages header, shown while the backend
 * is ingesting a backlog — the replay that arrives after the phone has been off.
 *
 * Header rather than an overlay or a full-screen state on purpose: by the time this
 * shows, the restored conversation list is already on screen and usable. The user's
 * question at that moment is "is this everything, or is it still coming in", and a
 * spinner beside the title answers it without taking anything away.
 *
 * Sized to fit the 36dp bar on a 240x320 screen — this is a flip phone, and a
 * default 40dp indicator would be taller than the bar containing it.
 */
@Composable
private fun CatchUpIndicator() {
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 1.5.dp,
            // Same gray as the profile button opposite it, so the header reads as
            // one row of quiet chrome rather than an alert.
            color = SmartTxtAvatarGray,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    // This screen is shared by all three apps (Signal, Google Messages, SmartTxt),
    // so a hardcoded "Smart Txt" title leaked into the other two. Brand it only for
    // the SmartTxt skin; the others get a neutral welcome. (smarttxt is the only
    // per-app signal the shared library carries — see DpadMessengerColors.)
    val smarttxt = LocalDpadMessengerColors.current.smarttxt
    if (smarttxt) {
        SmartTxtWelcome(padding)
        return
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                "No conversations yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your messages will show up here as you receive them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Smart Txt's empty inbox — "welcome to smart txt!" (Figma "onboarding - 23",
 * dumb product design node 50:26293). The first thing a new Smart Txt user
 * sees after choosing which number/email new conversations start from.
 *
 * Under the existing "Messages" bar: the title (Helvetica Now Display Extra
 * Bold, at the launcher onboarding's 34sp rather than Figma's 28), the body in
 * Medium 16/17.6 with the voice-to-text tip, and the hand-drawn speech bubble
 * (96×90, ic_smart_txt_bubble — the launcher's asset, copied into this
 * library's drawable-nodpi along with the two Display fonts, since a library
 * can't reach the app's resources). Left-aligned and top-anchored like the
 * onboarding pages, replacing the centred "Welcome to Smart Txt 2.0!" copy.
 *
 * Signal and Google Messages keep their neutral "No conversations yet".
 * The soft keys ("settings" / "new") are the room list's own and unchanged.
 */
@Composable
private fun SmartTxtWelcome(padding: PaddingValues) {
    val extraBold = androidx.compose.ui.text.font.FontFamily(
        androidx.compose.ui.text.font.Font(
            com.offline.dpadmessenger.R.font.helvetica_now_display_extrabold,
            androidx.compose.ui.text.font.FontWeight.ExtraBold,
        )
    )
    val medium = androidx.compose.ui.text.font.FontFamily(
        androidx.compose.ui.text.font.Font(
            com.offline.dpadmessenger.R.font.helvetica_now_display_medium,
            androidx.compose.ui.text.font.FontWeight.Medium,
        )
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Bottom only. The Scaffold's top inset (the "Messages" bar) is
            // already applied once by the Column that hosts this screen (see
            // "The top inset is applied once here"); padding by all of
            // `padding` here added the bar's height a second time and put the
            // title ~36dp lower than Figma's 52dp from the top.
            .padding(bottom = padding.calculateBottomPadding())
            .padding(horizontal = 12.dp),
    ) {
        // Explicit break, as in Figma 50:26294: "welcome to / smart txt!".
        // Left to wrap, the handset's wider screen fit it all on one line.
        Text(
            "welcome to\nsmart txt!",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = extraBold,
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                // 34/34, the same as every other Smart Txt setup title (the
                // launcher onboarding's size). Figma 50:26293 sets this one at
                // 28/28; it was matched for a while, but having the last setup
                // page's title smaller than the rest read as inconsistent.
                fontSize = 34.sp,
                lineHeight = 34.sp,
                color = androidx.compose.ui.graphics.Color(0xFF14140C), // light/text-strong
            ),
            // 14dp under the 36dp "Messages" bar (top 50). Figma has 16dp
            // (top 52), tightened by 2dp on review on the 4058W. 216dp wide in
            // Figma (12dp gutters).
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
        Text(
            "ur messages will show up here as u receive them. " +
                "press and hold the * key (bottom left) for voice-to-text.",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = medium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 17.6.sp,
                color = androidx.compose.ui.graphics.Color(0xFF575757), // light/text-weak
            ),
            // Figma: body top 117 = title bottom (108) + 9dp.
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        )
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.offline.dpadmessenger.R.drawable.ic_smart_txt_bubble),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                // Figma: bubble top 199 = body bottom (117 + 4 × 17.6 ≈ 187) + 12dp.
                .padding(top = 12.dp)
                // 1.3× Figma's 96×90 (125×117dp), the same enlargement as the
                // launcher's "set up smart txt" bubble (BUBBLE_SCALE), so the
                // bubble reads at the same size across onboarding and here; at
                // Figma's size it looked small next to the text on the 4058W.
                // ic_smart_txt_bubble is 288×270px, still more than this needs.
                // size() is clamped by the Column, so on a short screen or a
                // large font setting it shrinks (Fit) rather than overflowing.
                .size(width = 96.dp * 1.3f, height = 90.dp * 1.3f),
        )
    }
}
