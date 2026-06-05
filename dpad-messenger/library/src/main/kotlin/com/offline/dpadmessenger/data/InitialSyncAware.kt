package com.offline.dpadmessenger.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional capability a [MessageRepository] can implement when its data isn't
 * available synchronously — e.g. a network-backed repo that has to fetch the
 * conversation list before it knows whether the list is genuinely empty or
 * still loading.
 *
 * The room list uses this to show a spinner during the very first sync instead
 * of flashing the empty-state ("New conversations will appear here") and then
 * popping conversations in a moment later. Repositories whose data is already
 * in memory (the mock) simply don't implement this and are treated as loaded.
 */
interface InitialSyncAware {
    /** False until the first sync with the backing source completes. */
    val isInitialSyncComplete: StateFlow<Boolean>
}
