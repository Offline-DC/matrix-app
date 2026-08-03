package com.offline.dpadmessenger.backend.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a one-time legacy-blob migration is running right now, so a gate
 * screen can say "Moving your messages…" instead of showing a bare spinner.
 *
 * Process-global on purpose: the migration happens inside
 * [MessageStore.migrateIfNeeded], several layers below any composable, and
 * threading a callback up through three repositories to reach three gate
 * screens would be far more invasive than a flag they can collect.
 *
 * Realistically this is visible for well under a second — 441 messages import
 * in one transaction — so treat it as reassurance, not a progress bar.
 */
object MigrationStatus {

    private val _backendId = MutableStateFlow<String?>(null)

    /** Non-null while a migration is in flight; the value is the backend id. */
    val backendId: StateFlow<String?> = _backendId

    // Counted, not a boolean: two backends can migrate in the same launch (the
    // user opens Smart Txt and Google Messages back to back), and a plain flag
    // would let the first one to finish clear the second one's banner.
    private val depth = AtomicInteger(0)

    internal fun begin(id: String) {
        depth.incrementAndGet()
        _backendId.value = id
    }

    internal fun end() {
        if (depth.decrementAndGet() <= 0) {
            depth.set(0)
            _backendId.value = null
        }
    }
}
