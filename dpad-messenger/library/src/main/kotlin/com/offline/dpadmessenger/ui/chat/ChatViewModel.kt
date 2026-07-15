package com.offline.dpadmessenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.SmsThreadInfo
import com.offline.dpadmessenger.data.TimelineItem
import com.offline.dpadmessenger.ui.util.buildTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for [ChatScreen].
 *
 * Surfaces:
 *  - [timeline]: messages + date dividers + loading row, ready to render.
 *  - [room]: the room metadata (for the title bar, group flag, etc).
 *  - [replyTarget]: the message being replied to, if any. Set by
 *    [startReply] and cleared by [clearReply] or after a successful send.
 *  - [selectedMessage]: the message whose context sheet is open. Null when
 *    nothing is selected.
 *  - [editTarget]: the message being edited. Mutually exclusive with
 *    [replyTarget].
 *
 * Pagination is opportunistic: [requestLoadOlder] is debounced to "at most one
 * in-flight load per room" via [isLoadingOlder]. The screen calls it when the
 * top of the timeline becomes visible.
 */
class ChatViewModel(
    private val repository: MessageRepository,
    private val roomId: String,
) : ViewModel() {

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room.asStateFlow()

    private val _replyTarget = MutableStateFlow<Message?>(null)
    val replyTarget: StateFlow<Message?> = _replyTarget.asStateFlow()

    private val _editTarget = MutableStateFlow<Message?>(null)
    val editTarget: StateFlow<Message?> = _editTarget.asStateFlow()

    private val _selectedMessage = MutableStateFlow<Message?>(null)
    val selectedMessage: StateFlow<Message?> = _selectedMessage.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    /** True until the room's messages have first loaded (or a short grace
     *  period elapses). The screen shows a spinner instead of the "say hi"
     *  empty-state while this is true, so history doesn't flash empty first. */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val timeline: StateFlow<List<TimelineItem>> = combine(
        repository.observeMessages(roomId),
        repository.observeHasMoreOlder(roomId),
    ) { messages, hasMore ->
        buildTimeline(roomId, messages, hasMore)
        // Eagerly: this ViewModel is per-open-conversation (created on open,
        // cleared on leave), so eager collection only spans this one chat — it
        // does NOT keep other rooms' timelines warm. Eager also means the
        // timeline is already populated by first render, avoiding an empty
        // flash. (WhileSubscribed's saving here was marginal and started the
        // flow cold.)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isSmsProbe = MutableStateFlow(false)

    /** True when this thread sends as green SMS. Combines any SMS message already
     *  in the thread with a one-shot probe of the recipient, so a brand-new SMS
     *  thread reads green (and colors the send button) BEFORE the first send. */
    val isSms: StateFlow<Boolean> = combine(
        repository.observeMessages(roomId),
        _isSmsProbe,
    ) { messages, probe -> probe || messages.any { it.isSms } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            _room.value = repository.getRoom(roomId)
            repository.markRoomRead(roomId)
        }
        viewModelScope.launch {
            (repository as? SmsThreadInfo)?.let {
                _isSmsProbe.value = runCatching { it.isSmsThread(roomId) }.getOrDefault(false)
            }
        }
        viewModelScope.launch {
            // Consider the room loaded once messages first arrive, or after a
            // grace period (so a genuinely empty conversation still shows the
            // "say hi" empty-state rather than spinning forever).
            kotlinx.coroutines.withTimeoutOrNull(2_000) {
                repository.observeMessages(roomId).first { it.isNotEmpty() }
            }
            _loading.value = false
        }
    }

    /** The chat screen became the foreground, resumed screen — the user is
     *  actively looking at this thread. Suppress + clear its notifications.
     *  Driven by the screen's RESUME lifecycle (see ChatScreen), so it is true
     *  ONLY while the thread is actually on screen — not after the user leaves
     *  via the call button / a hotkey / the screen sleeping. */
    fun markActive() {
        repository.onRoomOpened(roomId)
    }

    /** The chat screen was paused or left (call button, hotkey, back, app
     *  backgrounded, screen off). Resume notifications for this thread. */
    fun markInactive() {
        repository.onRoomClosed(roomId)
    }

    fun senderName(senderId: String): String = repository.userById(senderId).displayName

    /** Project a message into a render-ready reply-quote snippet. */
    suspend fun parentSnippet(replyToId: String?): com.offline.dpadmessenger.ui.components.ReplyParentSnippet? {
        if (replyToId == null) return null
        val parent = repository.getMessage(roomId, replyToId) ?: return null
        // A quote of your own message must read "You" — senderName() resolves
        // an own senderId to the raw ACI/E.164 since there's no self contact.
        val resolved = if (parent.isOutgoing) "You" else senderName(parent.senderId)
        // Quoted image already downloaded → the quote shows a small thumbnail.
        val imagePath = parent.attachment
            ?.takeIf { it.kind == com.offline.dpadmessenger.data.AttachmentKind.IMAGE }
            ?.localPath?.takeIf { java.io.File(it).exists() }
        android.util.Log.d(
            "ChatUI",
            "reply-quote parent=$replyToId sender=${parent.senderId} " +
                "outgoing=${parent.isOutgoing} resolved=$resolved thumb=${imagePath != null}",
        )
        return com.offline.dpadmessenger.ui.components.ReplyParentSnippet(
            senderName = resolved,
            bodyPreview = when {
                parent.isDeleted -> "Message deleted"
                // Media-only parent has a blank body — show a typed label
                // ("Photo" etc.) like Signal instead of an empty line.
                parent.body.isBlank() ->
                    com.offline.dpadmessenger.ui.components.mediaQuoteLabel(parent.attachment)
                else -> parent.body
            },
            imagePath = if (parent.isDeleted) null else imagePath,
            isOutgoing = parent.isOutgoing,
        )
    }

    fun openMessageSheet(message: Message) { _selectedMessage.value = message }
    fun closeMessageSheet() { _selectedMessage.value = null }

    fun startReply(message: Message) {
        android.util.Log.d(
            "ChatUI",
            "reply-banner target=${message.id} sender=${message.senderId} " +
                "outgoing=${message.isOutgoing} " +
                "resolved=${if (message.isOutgoing) "You" else senderName(message.senderId)}",
        )
        _editTarget.value = null
        _replyTarget.value = message
    }
    fun clearReply() { _replyTarget.value = null }

    fun startEdit(message: Message) {
        _replyTarget.value = null
        _editTarget.value = message
    }
    fun clearEdit() { _editTarget.value = null }

    fun send(body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        val replyId = _replyTarget.value?.id
        val edit = _editTarget.value
        // Dismiss the reply/edit banner the moment send is pressed — clearing
        // it after the repository call returns leaves the banner up for the
        // whole network round-trip.
        _replyTarget.value = null
        _editTarget.value = null
        viewModelScope.launch {
            if (edit != null) {
                repository.editMessage(roomId, edit.id, text)
            } else {
                repository.sendMessage(roomId, text, replyId)
            }
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { repository.toggleReaction(roomId, messageId, emoji) }
    }

    /** Message ids currently downloading their attachment. */
    private val _downloadingMedia = MutableStateFlow<Set<String>>(emptySet())
    val downloadingMedia: StateFlow<Set<String>> = _downloadingMedia.asStateFlow()

    /** Message ids whose last download attempt failed (→ show "tap to retry"). */
    private val _failedMedia = MutableStateFlow<Set<String>>(emptySet())
    val failedMedia: StateFlow<Set<String>> = _failedMedia.asStateFlow()

    /** Download an attachment (first tap on a media bubble). The repository
     *  updates the message's localPath in the flow when it finishes. */
    fun downloadMedia(messageId: String) {
        val downloader = repository as? com.offline.dpadmessenger.data.MediaDownloader
        if (downloader == null) {
            android.util.Log.w("ChatVM", "downloadMedia: repository is not a MediaDownloader — can't fetch $messageId")
            return
        }
        if (messageId in _downloadingMedia.value) {
            android.util.Log.d("ChatVM", "downloadMedia: already downloading $messageId — ignoring retry")
            return
        }
        viewModelScope.launch {
            android.util.Log.i("ChatVM", "downloadMedia: start $messageId")
            _failedMedia.value = _failedMedia.value - messageId
            _downloadingMedia.value = _downloadingMedia.value + messageId
            val path = runCatching { downloader.downloadMedia(roomId, messageId) }
                .onFailure { android.util.Log.e("ChatVM", "downloadMedia: threw for $messageId", it) }
                .getOrNull()
            _downloadingMedia.value = _downloadingMedia.value - messageId
            if (path == null) {
                android.util.Log.w("ChatVM", "downloadMedia: FAILED $messageId (path null)")
                _failedMedia.value = _failedMedia.value + messageId
            } else {
                android.util.Log.i("ChatVM", "downloadMedia: OK $messageId -> $path")
            }
        }
    }

    fun delete(messageId: String) {
        viewModelScope.launch { repository.deleteMessage(roomId, messageId) }
    }

    /** Re-send a failed message. */
    fun resend(messageId: String) {
        viewModelScope.launch { repository.resendMessage(roomId, messageId) }
    }

    /** True if this repo can send attachments (drives the composer "+" button). */
    val canSendAttachments: Boolean =
        repository is com.offline.dpadmessenger.data.AttachmentSender

    /** True if this repo's delete is a real "delete for everyone" (drives the
     *  context sheet's Delete action). */
    val canDeleteForEveryone: Boolean =
        repository is com.offline.dpadmessenger.data.RemoteDeleteCapable

    /** True if this repo can RE-UPLOAD a failed attachment, not just resend text
     *  (drives whether the context sheet offers "Retry send" on a failed photo /
     *  video / voice memo). */
    val canResendAttachments: Boolean =
        repository is com.offline.dpadmessenger.data.AttachmentResendCapable

    /** Send a picked photo/video (content:// uri) to this room. */
    fun sendAttachment(contentUri: String) {
        val sender = repository as? com.offline.dpadmessenger.data.AttachmentSender ?: return
        viewModelScope.launch { runCatching { sender.sendAttachment(roomId, contentUri) } }
    }

    /** Send a recorded voice memo (local .m4a file path) as an audio attachment. */
    fun sendVoiceMemo(filePath: String) {
        val sender = repository as? com.offline.dpadmessenger.data.AttachmentSender ?: return
        val uri = android.net.Uri.fromFile(java.io.File(filePath)).toString()
        viewModelScope.launch { runCatching { sender.sendAttachment(roomId, uri) } }
    }

    fun requestLoadOlder() {
        if (_isLoadingOlder.value) return
        viewModelScope.launch {
            _isLoadingOlder.value = true
            try {
                repository.loadOlder(roomId)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }
}
