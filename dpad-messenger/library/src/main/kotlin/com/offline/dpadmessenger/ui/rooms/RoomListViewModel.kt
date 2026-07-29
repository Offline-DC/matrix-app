package com.offline.dpadmessenger.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.dpadmessenger.data.InitialSyncAware
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.RoomSummary
import com.offline.dpadmessenger.data.SyncActivityAware
import com.offline.dpadmessenger.data.ThreadActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoomListViewModel(
    private val repository: MessageRepository,
) : ViewModel() {

    val rooms: StateFlow<List<RoomSummary>> = repository
        .observeRoomSummaries()
        // DIAGNOSTIC (UIFLOW): step 3 of 3. Steps 1+2 firing without this one means the
        // stateIn boundary conflated it; all three firing while the screen stays stale
        // means the flow layer is fine and Compose never recomposed - which is the
        // expected result if the heap is pinned (watch for back-to-back GCs reporting
        // "0% free" around the same timestamps). Remove all three once diagnosed.
        .onEach { android.util.Log.i("IMsgUiFlow", "UIFLOW vm: rooms=${it.size}") }
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
     * Whether the repository is currently pulling in a backlog — the replay that
     * arrives after the phone has been off for a while.
     *
     * Deliberately separate from [isInitialLoading]: that one means "I can't show
     * you a trustworthy list yet" and takes over the whole screen, whereas this
     * means "the list is real, there's just more coming". The header shows a small
     * spinner for it so the user isn't left wondering whether a half-restored
     * conversation list is all they have.
     *
     * Repositories that don't ingest in bulk report false forever.
     */
    val isCatchingUp: StateFlow<Boolean> =
        (repository as? SyncActivityAware)?.isCatchingUp ?: MutableStateFlow(false)

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

    /** Muted conversations (no notifications). Drives the row's muted indicator
     *  and the Mute/Unmute label in the press-and-hold sheet. */
    val mutedRooms: StateFlow<Set<String>> = repository
        .observeMutedRooms()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Whether the backing repository supports delete/mute — gates the
     *  press-and-hold conversation menu (mirrors the new-message gating). */
    val supportsThreadActions: Boolean = repository is ThreadActions

    /** Delete a conversation (remove it from the list + drop its messages). */
    fun deleteRoom(roomId: String) {
        viewModelScope.launch { repository.deleteRoom(roomId) }
    }

    /** Mute or unmute a conversation. */
    fun setMuted(roomId: String, muted: Boolean) {
        viewModelScope.launch { repository.setMuted(roomId, muted) }
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
