package com.offline.dpadmessenger.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.offline.dpadmessenger.focus.DpadFireGate
import com.offline.dpadmessenger.focus.OkKeys
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.TimelineItem
import com.offline.dpadmessenger.ui.components.BannerKind
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.components.DateDivider
import com.offline.dpadmessenger.ui.components.DpadComposer
import com.offline.dpadmessenger.ui.components.LoadingOlderRow
import com.offline.dpadmessenger.ui.components.MessageBubble
import com.offline.dpadmessenger.ui.components.MessageContextSheet
import com.offline.dpadmessenger.ui.components.ReplyOrEditBanner
import com.offline.dpadmessenger.ui.components.ReplyParentSnippet

/**
 * Conversation screen.
 *
 * DPAD layout:
 *   - On entry: focus lands on the composer text field.
 *   - DPAD-Up from the composer focuses the last (most recent) bubble.
 *   - Up/Down between bubbles moves between bubbles.
 *   - DPAD-Right in the composer moves the cursor; at end-of-text it jumps
 *     to the Send button. DPAD-Left from Send returns to the field.
 *   - When the top loading row scrolls into view, older messages page in.
 *   - OK on a bubble opens the context sheet.
 *   - Back returns to the room list (or cancels an open sheet first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Host-provided re-link (re-pair the phone, keeping history). When set, a
     *  failed message offers a "Re-link phone" action. */
    onRelink: (() -> Unit)? = null,
) {
    // Mark this thread "active" (suppress + clear its notifications) ONLY while
    // its chat screen is actually the resumed, on-screen UI. Tied to the screen
    // RESUME/PAUSE lifecycle — not to back-navigation or the Activity's onStop —
    // so leaving via the call button / a hotkey / the screen turning off
    // correctly resumes this thread's notifications, while sitting on the thread
    // keeps them suppressed. (Returning to the thread re-clears anything posted
    // while away.)
    LifecycleResumeEffect(viewModel) {
        viewModel.markActive()
        onPauseOrDispose { viewModel.markInactive() }
    }

    val room by viewModel.room.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val editTarget by viewModel.editTarget.collectAsState()
    val isSms by viewModel.isSms.collectAsState()
    val selected by viewModel.selectedMessage.collectAsState()

    val downloadingMedia by viewModel.downloadingMedia.collectAsState()
    val failedMedia by viewModel.failedMedia.collectAsState()
    // Fullscreen media overlay: (file path, kind). Null = closed.
    var mediaViewer by remember {
        mutableStateOf<Pair<String, com.offline.dpadmessenger.data.AttachmentKind>?>(null)
    }

    val composerFr = remember { FocusRequester() }
    val lastBubbleFr = remember { FocusRequester() }
    val backBtnFr = remember { FocusRequester() }
    // The reply/edit banner's Cancel X — first DPAD-Up stop from the composer
    // while a banner is showing.
    val bannerCancelFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Focus the most-recent bubble, retrying across a few frames: a bubble
    // composed THIS frame isn't in the focus tree yet, so an immediate
    // requestFocus silently no-ops (see onUpFromField below).
    fun focusLastBubble() {
        scope.launch {
            repeat(8) {
                withFrameNanos {}
                val ok = runCatching { lastBubbleFr.requestFocus() }.isSuccess
                if (ok) return@launch
            }
        }
    }

    // Which bubble to refocus when the fullscreen media viewer closes — the one
    // whose photo/video was opened — so DPAD focus lands back where the user
    // was, not on the composer. The requester is attached to that bubble in
    // the timeline below.
    val mediaReturnFr = remember { FocusRequester() }
    var mediaReturnId by remember { mutableStateOf<String?>(null) }

    // In-app, DPAD-navigable photo/video picker for the composer "+" button.
    // Replaces the system Photos picker (not DPAD-friendly on flip phones).
    // When true, the picker overlay is shown; picking sends via the repository.
    var showMediaPicker by remember { mutableStateOf(false) }

    // Tap/OK on a media bubble: first load it, then (once cached) view it.
    val onMediaActivate: (Message) -> Unit = activate@{ msg ->
        // A failed outgoing media has nothing to download — open the context
        // sheet (Retry/Reply) instead of attempting a pointless fetch.
        if (msg.isOutgoing && msg.status == com.offline.dpadmessenger.data.MessageStatus.FAILED) {
            viewModel.openMessageSheet(msg)
            return@activate
        }
        val att = msg.attachment ?: return@activate
        val path = att.localPath
        // Voice memos play inline via the bubble's own player — never open the
        // image/video viewer. The bubble tap only kicks off a download when the
        // file isn't here yet.
        if (att.kind == com.offline.dpadmessenger.data.AttachmentKind.AUDIO) {
            val have = path != null && java.io.File(path).exists()
            if (!have && att.downloadToken.isNotBlank() && msg.id !in downloadingMedia) {
                viewModel.downloadMedia(msg.id)
            }
            return@activate
        }
        if (path != null && java.io.File(path).exists()) {
            mediaReturnId = msg.id
            mediaViewer = path to att.kind
        } else if (att.downloadToken.isBlank()) {
            // Pre-download placeholder — there's nothing to fetch yet (the full
            // copy with a media id arrives on its own). Don't kick off a fetch
            // that can only fail.
            return@activate
        } else if (msg.id !in downloadingMedia) {
            viewModel.downloadMedia(msg.id)
        }
    }

    // No parent-side focus retry — the DpadComposer handles initial focus
    // itself via onGloballyPositioned, which guarantees the inner field is
    // in the focus tree before requestFocus is called. See autoFocusOnAttach
    // wired below.

    // When the user picks Reply or Edit in the context sheet, focus the
    // composer so they can immediately start typing. Keyed on the target's
    // id so a NEW reply/edit (different message) re-fires this. The small
    // delay lets the ModalBottomSheet's dismiss animation complete first —
    // requesting focus while the sheet is still up is a no-op.
    LaunchedEffect(replyTarget?.id, editTarget?.id) {
        if (replyTarget != null || editTarget != null) {
            delay(150)
            runCatching { composerFr.requestFocus() }
        }
    }

    // Explicit Back handling. The default NavController back works most of
    // the time, but when an open IME consumes the first Back the user can be
    // left in a confusing state — this handler covers any path that survives
    // to reach Compose.
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            CompactTopBar(
                title = room?.name ?: "",
                navigationIcon = {
                    CompactBarButton(onClick = onBack, focusRequester = backBtnFr) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            // The OK press on a room row that opened this chat loses its KeyUp to
            // the screen transition, so DpadFireGate stays held (~2s) and swallows
            // the user's FIRST OK on a message (they had to press twice). Release
            // the gate on any OK key-up here so the first deliberate press fires.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key in OkKeys) {
                    DpadFireGate.release(event.key)
                }
                false
            },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Timeline(
                timeline = timeline,
                loading = loading,
                isGroup = room?.isGroup == true,
                senderNameFor = viewModel::senderName,
                resolveParent = viewModel::parentSnippet,
                onBubbleClick = viewModel::openMessageSheet,
                onLoadOlderVisible = viewModel::requestLoadOlder,
                lastBubbleFocusRequester = lastBubbleFr,
                returnFocusId = mediaReturnId,
                returnFocusRequester = mediaReturnFr,
                downloadingMedia = downloadingMedia,
                failedMedia = failedMedia,
                onMediaActivate = onMediaActivate,
                modifier = Modifier.weight(1f),
            )
            BannerRegion(
                replyTarget = replyTarget,
                editTarget = editTarget,
                senderNameFor = viewModel::senderName,
                // Cancelling removes the banner (and the focused X with it) —
                // hand focus back to the composer so it isn't dropped.
                onCancelReply = {
                    viewModel.clearReply()
                    runCatching { composerFr.requestFocus() }
                },
                onCancelEdit = {
                    viewModel.clearEdit()
                    runCatching { composerFr.requestFocus() }
                },
                cancelFocusRequester = bannerCancelFr,
                onUpFromCancel = { focusLastBubble() },
                onDownFromCancel = { runCatching { composerFr.requestFocus() } },
            )
            DpadComposer(
                onSend = viewModel::send,
                isSms = isSms,
                prefill = editTarget?.body,
                prefillKey = editTarget?.id,
                textFieldFocusRequester = composerFr,
                onUpFromField = {
                    // With a reply/edit banner up, DPAD-Up stops on its Cancel
                    // X first (Up again continues to the timeline). Otherwise
                    // go straight to the most-recent bubble.
                    if (replyTarget != null || editTarget != null) {
                        runCatching { bannerCancelFr.requestFocus() }
                    } else {
                        focusLastBubble()
                    }
                },
                onLeftFromField = { runCatching { backBtnFr.requestFocus() } },
                autoFocusOnAttach = true,
                hint = when {
                    editTarget != null -> "Edit your message"
                    replyTarget != null -> "Reply…"
                    else -> "Type a message"
                },
                // "+" attach button: only when the repo can send media. Opens
                // the in-app DPAD picker overlay rather than the system Photos
                // UI.
                onAttach = if (viewModel.canSendAttachments) {
                    { showMediaPicker = true }
                } else null,
                // Empty field shows a record (mic) button → record → preview →
                // send a voice memo. Only when the repo can send attachments.
                onSendVoiceMemo = if (viewModel.canSendAttachments) {
                    { path -> viewModel.sendVoiceMemo(path) }
                } else null,
            )
        }
    }

    val sel = selected
    if (sel != null) {
        MessageContextSheet(
            message = sel,
            // Texting (SMS/RCS) has no "edit sent message" operation — it
            // would silently do nothing, so don't offer it.
            canEdit = false,
            // "Delete for everyone" — only for repos that actually implement
            // it (Signal), only on our OWN non-deleted messages, and only
            // within Signal's 24h delete-for-everyone window.
            canDelete = viewModel.canDeleteForEveryone &&
                sel.isOutgoing &&
                !sel.isDeleted &&
                (System.currentTimeMillis() - sel.timestampMs) < 24 * 60 * 60 * 1000L,
            onReact = { emoji -> viewModel.react(sel.id, emoji) },
            onReply = { viewModel.startReply(sel) },
            onEdit = { viewModel.startEdit(sel) },
            onDelete = { viewModel.delete(sel.id) },
            onRetry = { viewModel.resend(sel.id) },
            // Only SmartTxt keeps the outgoing bytes around to re-upload; Signal /
            // Google Messages bail on attachment resend, so they get no Retry row
            // on a failed photo / video / voice memo.
            canRetryAttachment = viewModel.canResendAttachments,
            onRelink = onRelink,
            onDismiss = viewModel::closeMessageSheet,
            senderNameFor = viewModel::senderName,
        )
    }

    // In-app photo/video picker overlay (composer "+").
    if (showMediaPicker) {
        MediaPickerScreen(
            onPick = { uri ->
                viewModel.sendAttachment(uri)
                showMediaPicker = false
                scope.launch {
                    withFrameNanos {}
                    runCatching { composerFr.requestFocus() }
                }
            },
            onClose = {
                showMediaPicker = false
                scope.launch {
                    withFrameNanos {}
                    runCatching { composerFr.requestFocus() }
                }
            },
        )
    }

    // Fullscreen image/video viewer overlay.
    mediaViewer?.let { (path, kind) ->
        FullscreenMediaViewer(
            path = path,
            kind = kind,
            onClose = {
                mediaViewer = null
                // Return focus to the bubble whose media was opened. If that
                // bubble is no longer composed (scrolled far off), fall back to
                // the composer so focus is never lost.
                scope.launch {
                    withFrameNanos {}
                    val landed = runCatching { mediaReturnFr.requestFocus() }.isSuccess
                    if (!landed) runCatching { composerFr.requestFocus() }
                }
            },
        )
    }
}

@Composable
private fun BannerRegion(
    replyTarget: Message?,
    editTarget: Message?,
    senderNameFor: (String) -> String,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    cancelFocusRequester: FocusRequester? = null,
    onUpFromCancel: (() -> Unit)? = null,
    onDownFromCancel: (() -> Unit)? = null,
) {
    when {
        editTarget != null -> ReplyOrEditBanner(
            kind = BannerKind.Edit,
            senderName = senderNameFor(editTarget.senderId),
            bodyPreview = editTarget.body,
            onCancel = onCancelEdit,
            cancelFocusRequester = cancelFocusRequester,
            onUpFromCancel = onUpFromCancel,
            onDownFromCancel = onDownFromCancel,
        )
        replyTarget != null -> ReplyOrEditBanner(
            kind = BannerKind.Reply,
            // Replying to your own message should read "You", not your phone
            // number (senderNameFor resolves an own-message senderId to the raw
            // E.164 since there's no contact entry for yourself).
            senderName = if (replyTarget.isOutgoing) "You" else senderNameFor(replyTarget.senderId),
            bodyPreview = when {
                replyTarget.isDeleted -> "Message deleted"
                // Media-only parent → typed label instead of an empty line.
                replyTarget.body.isBlank() ->
                    com.offline.dpadmessenger.ui.components.mediaQuoteLabel(replyTarget.attachment)
                else -> replyTarget.body
            },
            onCancel = onCancelReply,
            cancelFocusRequester = cancelFocusRequester,
            onUpFromCancel = onUpFromCancel,
            onDownFromCancel = onDownFromCancel,
        )
    }
}

@Composable
private fun Timeline(
    timeline: List<TimelineItem>,
    loading: Boolean,
    isGroup: Boolean,
    senderNameFor: (String) -> String,
    resolveParent: suspend (String?) -> ReplyParentSnippet?,
    onBubbleClick: (Message) -> Unit,
    onLoadOlderVisible: () -> Unit,
    lastBubbleFocusRequester: FocusRequester,
    returnFocusId: String?,
    returnFocusRequester: FocusRequester,
    downloadingMedia: Set<String>,
    failedMedia: Set<String>,
    onMediaActivate: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    // reverseLayout = true means item index 0 is anchored at the BOTTOM of
    // the viewport, with higher indices stacking upward. Feed the LazyColumn
    // the timeline reversed (newest at index 0), and the very first render
    // is already at the visual bottom of the conversation. No scroll-to-
    // bottom step, no alpha-fade, no jump. WhatsApp / Signal / Telegram all
    // do this.
    //
    // The LoadingOlder row was added at index 0 of the original timeline,
    // so after reversal it sits at the highest index — visually the top of
    // the viewport. Pagination triggers when the user scrolls within ~3
    // items of that highest index.
    val listState = rememberLazyListState()
    val reversed = remember(timeline) { timeline.asReversed() }

    // The list's viewport bounds in window pixels (top, bottom). A focused
    // bubble taller than this needs the list scrolled to read it fully; the
    // bubble compares its own window bounds against these to decide whether a
    // DPAD Up/Down should scroll-to-read or move focus. Updated on (re)layout.
    var listViewport by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    val hasLoadingRow = timeline.firstOrNull() is TimelineItem.LoadingOlder
    val nearTop by remember {
        derivedStateOf {
            if (!hasLoadingRow) return@derivedStateOf false
            val topIndex = reversed.lastIndex
            listState.layoutInfo.visibleItemsInfo.any { it.index >= topIndex - 3 }
        }
    }
    LaunchedEffect(nearTop) {
        if (nearTop) onLoadOlderVisible()
    }

    if (timeline.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Spinner while the room's history is still loading; only show the
            // empty-state once we're confident the conversation is actually empty.
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator()
            } else {
                Text(
                    "messages will appear as you send/receive them",
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        return
    }

    // Most-recent message is at the visual bottom (= reversed index 0).
    // Gets the FocusRequester so the composer's DPAD-Up lands on it.
    val lastMessageId = (timeline.lastOrNull { it is TimelineItem.MessageItem }
        as? TimelineItem.MessageItem)?.message?.id
    // The newest OUTGOING message — only IT shows the delivery receipt (iMessage
    // style), so "Delivered/Read" doesn't repeat under every prior sent message.
    val lastOutgoingId = (timeline.lastOrNull {
        it is TimelineItem.MessageItem && it.message.isOutgoing
    } as? TimelineItem.MessageItem)?.message?.id

    // Keep the newest message fully on-screen when the timeline grows while
    // the user is already at the bottom — e.g. they just sent a message, or a
    // new one arrived while they were reading the latest. With reverseLayout,
    // index 0 is the visual bottom, and firstVisibleItemIndex == 0 means
    // "anchored at the newest". Only auto-scroll when near the bottom so we
    // never yank the user away while they're scrolled up reading history.
    // This also guarantees the just-sent bubble is measured/visible so the
    // composer's DPAD-Up can focus it (an off-screen row can't take focus).
    LaunchedEffect(lastMessageId) {
        if (lastMessageId != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        // Extra bottom padding (the visual bottom under reverseLayout) keeps
        // the newest bubble's timestamp/✓ row clear of the composer instead of
        // tucked right against it.
        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val top = coords.positionInWindow().y
                listViewport = top to (top + coords.size.height)
            },
    ) {
        itemsIndexed(items = reversed, key = { _, it -> it.key }) { index, item ->
            when (item) {
                is TimelineItem.LoadingOlder -> LoadingOlderRow()
                is TimelineItem.DateDivider -> DateDivider(label = item.label)
                is TimelineItem.MessageItem -> {
                    val msg = item.message
                    // Group consecutive messages from the same sender: show the name
                    // header only on the FIRST of a run (iMessage style). The bubble
                    // visually above (older) is the NEXT entry in this reverse-laid-out
                    // list; if that's a message from the same incoming sender, this one
                    // is a continuation and repeats no name. A date divider or a
                    // different sender above → this starts a new run → show the name.
                    val above = (reversed.getOrNull(index + 1) as? TimelineItem.MessageItem)?.message
                    val firstOfRun = above == null ||
                        above.isOutgoing != msg.isOutgoing ||
                        above.senderId != msg.senderId
                    val parent by produceState<ReplyParentSnippet?>(
                        initialValue = null,
                        key1 = msg.replyToId,
                    ) {
                        value = resolveParent(msg.replyToId)
                    }
                    MessageBubble(
                        message = msg,
                        senderName = if (msg.isOutgoing) null else senderNameFor(msg.senderId),
                        showSenderName = isGroup && !msg.isOutgoing && firstOfRun,
                        showReceipt = msg.id == lastOutgoingId,
                        parentSnippet = parent,
                        onClick = { onBubbleClick(msg) },
                        // Last bubble gets the composer's DPAD-Up requester; the
                        // bubble whose media was just viewed additionally gets
                        // the viewer's return-focus requester (both can be the
                        // same bubble, hence a separate handle).
                        focusRequester = if (msg.id == lastMessageId) lastBubbleFocusRequester else null,
                        extraFocusRequesters = if (msg.id == returnFocusId) listOf(returnFocusRequester) else emptyList(),
                        isDownloadingMedia = msg.id in downloadingMedia,
                        mediaFailed = msg.id in failedMedia,
                        onMediaActivate = { onMediaActivate(msg) },
                        getListViewport = { listViewport },
                    )
                }
            }
        }
    }
}
