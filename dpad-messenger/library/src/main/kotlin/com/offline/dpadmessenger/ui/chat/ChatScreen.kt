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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
) {
    val room by viewModel.room.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val editTarget by viewModel.editTarget.collectAsState()
    val selected by viewModel.selectedMessage.collectAsState()

    val composerFr = remember { FocusRequester() }
    val lastBubbleFr = remember { FocusRequester() }
    val backBtnFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

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
        modifier = modifier.fillMaxSize().imePadding(),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Timeline(
                timeline = timeline,
                isGroup = room?.isGroup == true,
                senderNameFor = viewModel::senderName,
                resolveParent = viewModel::parentSnippet,
                onBubbleClick = viewModel::openMessageSheet,
                onLoadOlderVisible = viewModel::requestLoadOlder,
                lastBubbleFocusRequester = lastBubbleFr,
                modifier = Modifier.weight(1f),
            )
            BannerRegion(
                replyTarget = replyTarget,
                editTarget = editTarget,
                senderNameFor = viewModel::senderName,
                onCancelReply = viewModel::clearReply,
                onCancelEdit = viewModel::clearEdit,
            )
            DpadComposer(
                onSend = viewModel::send,
                prefill = editTarget?.body,
                prefillKey = editTarget?.id,
                textFieldFocusRequester = composerFr,
                onUpFromField = {
                    // Yield one frame before requesting focus. If the user
                    // just sent a message, the new bubble has been composed
                    // but not yet placed in the focus tree — requesting
                    // focus on the same frame silently no-ops. Waiting a
                    // frame lets layout complete first.
                    scope.launch {
                        withFrameNanos {}
                        runCatching { lastBubbleFr.requestFocus() }
                    }
                },
                onLeftFromField = { runCatching { backBtnFr.requestFocus() } },
                autoFocusOnAttach = true,
                hint = when {
                    editTarget != null -> "Edit your message"
                    replyTarget != null -> "Reply…"
                    else -> "Type a message"
                },
            )
        }
    }

    val sel = selected
    if (sel != null) {
        MessageContextSheet(
            message = sel,
            canEdit = sel.isOutgoing && !sel.isDeleted,
            canDelete = sel.isOutgoing && !sel.isDeleted,
            onReact = { emoji -> viewModel.react(sel.id, emoji) },
            onReply = { viewModel.startReply(sel) },
            onEdit = { viewModel.startEdit(sel) },
            onDelete = { viewModel.delete(sel.id) },
            onDismiss = viewModel::closeMessageSheet,
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
) {
    when {
        editTarget != null -> ReplyOrEditBanner(
            kind = BannerKind.Edit,
            senderName = senderNameFor(editTarget.senderId),
            bodyPreview = editTarget.body,
            onCancel = onCancelEdit,
        )
        replyTarget != null -> ReplyOrEditBanner(
            kind = BannerKind.Reply,
            senderName = senderNameFor(replyTarget.senderId),
            bodyPreview = if (replyTarget.isDeleted) "Message deleted" else replyTarget.body,
            onCancel = onCancelReply,
        )
    }
}

@Composable
private fun Timeline(
    timeline: List<TimelineItem>,
    isGroup: Boolean,
    senderNameFor: (String) -> String,
    resolveParent: suspend (String?) -> ReplyParentSnippet?,
    onBubbleClick: (Message) -> Unit,
    onLoadOlderVisible: () -> Unit,
    lastBubbleFocusRequester: FocusRequester,
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
            Text("No messages yet — say hi.")
        }
        return
    }

    // Most-recent message is at the visual bottom (= reversed index 0).
    // Gets the FocusRequester so the composer's DPAD-Up lands on it.
    val lastMessageId = (timeline.lastOrNull { it is TimelineItem.MessageItem }
        as? TimelineItem.MessageItem)?.message?.id

    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 6.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(items = reversed, key = { it.key }) { item ->
            when (item) {
                is TimelineItem.LoadingOlder -> LoadingOlderRow()
                is TimelineItem.DateDivider -> DateDivider(label = item.label)
                is TimelineItem.MessageItem -> {
                    val msg = item.message
                    val parent by produceState<ReplyParentSnippet?>(
                        initialValue = null,
                        key1 = msg.replyToId,
                    ) {
                        value = resolveParent(msg.replyToId)
                    }
                    MessageBubble(
                        message = msg,
                        senderName = if (msg.isOutgoing) null else senderNameFor(msg.senderId),
                        showSenderName = isGroup && !msg.isOutgoing,
                        parentSnippet = parent,
                        onClick = { onBubbleClick(msg) },
                        focusRequester = if (msg.id == lastMessageId) lastBubbleFocusRequester else null,
                    )
                }
            }
        }
    }
}
