package com.offline.dpadmessenger.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.RoomSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class RoomListViewModel(
    private val repository: MessageRepository,
) : ViewModel() {

    val rooms: StateFlow<List<RoomSummary>> = repository
        .observeRoomSummaries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The id of the most recently opened conversation. When the user returns
     * from a chat back to the list, this row gets the initial DPAD focus so
     * they can keep navigating from where they left off.
     */
    private val _lastOpenedRoomId = MutableStateFlow<String?>(null)
    val lastOpenedRoomId: StateFlow<String?> = _lastOpenedRoomId.asStateFlow()

    fun setLastOpened(roomId: String) {
        _lastOpenedRoomId.value = roomId
    }

    fun senderName(senderId: String): String = repository.userById(senderId).displayName
}
