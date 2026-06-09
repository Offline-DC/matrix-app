package com.offline.dpadmessenger.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional capability for repositories that keep a local message store and can
 * auto-delete old messages. The settings screen surfaces a toggle when the
 * repository implements this (the mock doesn't, so its toggle stays hidden).
 *
 * Retention is local-only: deleting old messages here never affects copies on
 * the user's other devices.
 */
interface RetentionSettings {
    /** Whether old-message auto-deletion is currently on. Defaults to true. */
    val autoDeleteEnabled: StateFlow<Boolean>

    /** Turn auto-deletion on/off (persisted). Turning it on purges immediately. */
    fun setAutoDeleteEnabled(enabled: Boolean)
}
