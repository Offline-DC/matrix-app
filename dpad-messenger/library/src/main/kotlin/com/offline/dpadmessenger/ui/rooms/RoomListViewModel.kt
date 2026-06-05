package com.offline.dpadmessenger.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.data.InitialSyncAware
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.RoomSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoomListViewModel(
    private val repository: MessageRepository,
) : ViewModel() {

    val rooms: StateFlow<List<RoomSummary>> = repository
        .observeRoomSummaries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether the first sync is still in flight. Drives the room list's
     * loading spinner so an empty list during startup isn't mistaken for
     * "no conversations". Repos that don't track sync (the mock) report
     * loaded immediately.
     */
    val isInitialLoading: StateFlow<Boolean> =
        (repository as? InitialSyncAware)?.isInitialSyncComplete?.let { complete ->
            kotlinx.coroutines.flow.MutableStateFlow(!complete.value).also { loading ->
                viewModelScope.launch {
                    complete.collect { loading.value = !it }
                }
            }
        } ?: MutableStateFlow(false)

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

    /**
     * Remembered scroll position of the room list (first-visible index +
     * pixel offset), so returning from a chat restores the exact same view
     * instead of snapping to the top. Survives the chat navigation because the
     * ViewModel outlives the RoomList composable.
     */
    var savedScroll: Pair<Int, Int>? = null
        private set

    fun saveScroll(index: Int, offset: Int) { savedScroll = index to offset }

    fun senderName(senderId: String): String = repository.userById(senderId).displayName
}
