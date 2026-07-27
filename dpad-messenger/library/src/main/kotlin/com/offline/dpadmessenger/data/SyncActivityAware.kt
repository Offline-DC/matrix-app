package com.offline.dpadmessenger.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional capability a [MessageRepository] can implement when it can be busy
 * ingesting a backlog *after* the first sync has already completed — e.g. the
 * phone was switched off for a day or two and the service replays everything it
 * stored while we were away.
 *
 * Distinct from [InitialSyncAware] on purpose. That one answers "is the list I'm
 * showing you trustworthy yet", and its consumer replaces the whole screen with a
 * spinner. This one answers "am I still pulling things in", at a point where the
 * list IS trustworthy — it just isn't finished growing. The room list shows a small
 * indicator in the header for this rather than covering the conversations the user
 * can already see and open.
 *
 * Repositories that never ingest in bulk simply don't implement it, and the room
 * list shows nothing.
 */
interface SyncActivityAware {
    /**
     * True while a bulk catch-up is in progress. Expected to be false in ordinary
     * operation — a single arriving message is not a catch-up, and flickering the
     * indicator on every message would be worse than not having it.
     */
    val isCatchingUp: StateFlow<Boolean>
}
