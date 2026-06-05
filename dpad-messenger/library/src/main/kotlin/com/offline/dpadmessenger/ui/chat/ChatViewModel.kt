package com.offline.dpadmessenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.data.Message
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.Room
import com.offline.dpadmessenger.data.TimelineItem
import com.offline.dpadmessenger.ui.util.buildTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    val timeline: StateFlow<List<TimelineItem>> = combine(
        repository.observeMessages(roomId),
        repository.observeHasMoreOlder(roomId),
    ) { messages, hasMore ->
        buildTimeline(roomId, messages, hasMore)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            _room.value = repository.getRoom(roomId)
            repository.markRoomRead(roomId)
        }
    }

    fun senderName(senderId: String): String = repository.userById(senderId).displayName

    /** Project a message into a render-ready reply-quote snippet. */
    suspend fun parentSnippet(replyToId: String?): com.offline.dpadmessenger.ui.components.ReplyParentSnippet? {
        if (replyToId == null) return null
        val parent = repository.getMessage(roomId, replyToId) ?: return null
        return com.offline.dpadmessenger.ui.components.ReplyParentSnippet(
            senderName = senderName(parent.senderId),
            bodyPreview = if (parent.isDeleted) "Message deleted" else parent.body,
        )
    }

    fun openMessageSheet(message: Message) { _selectedMessage.value = message }
    fun closeMessageSheet() { _selectedMessage.value = null }

    fun startReply(message: Message) {
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
        viewModelScope.launch {
            if (edit != null) {
                repository.editMessage(roomId, edit.id, text)
                _editTarget.value = null
            } else {
                repository.sendMessage(roomId, text, replyId)
                _replyTarget.value = null
            }
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { repository.toggleReaction(roomId, messageId, emoji) }
    }

    fun delete(messageId: String) {
        viewModelScope.launch { repository.deleteMessage(roomId, messageId) }
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
