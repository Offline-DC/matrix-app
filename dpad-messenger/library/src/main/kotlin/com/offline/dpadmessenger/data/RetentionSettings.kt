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
    /** How many days of messages this device keeps. One of [RETENTION_DAY_OPTIONS],
     *  or [RETENTION_NEVER_DAYS] for a user migrated off the old OFF switch.
     *  Defaults to [DEFAULT_RETENTION_DAYS]. */
    val autoDeleteDays: StateFlow<Int>

    /** Choose how long to keep messages (persisted). Prunes immediately, so
     *  picking a shorter window takes effect without waiting for a restart. */
    fun setAutoDeleteDays(days: Int)

    companion object {
        /**
         * Keep everything, stored as 0 days and shown as "Ever".
         *
         * Deliberately NOT in [RETENTION_DAY_OPTIONS]: it is a migration
         * destination, not a choice. Users who had auto-delete switched OFF land
         * here and keep it for as long as they leave the setting alone, but the
         * picker cannot reach it — so cycling onto a real window is one-way, and
         * a fresh install can never get here at all.
         *
         * That is enforced for free by the `days in RETENTION_DAY_OPTIONS` clamp
         * every setAutoDeleteDays already does: 0 is not in the list, so any path
         * that tried to set it falls back to [DEFAULT_RETENTION_DAYS].
         */
        const val RETENTION_NEVER_DAYS = 0

        /** The choices the settings screen offers, in the order it cycles them. */
        val RETENTION_DAY_OPTIONS = listOf(1, 3, 7, 28)

        /** What a fresh install keeps. */
        const val DEFAULT_RETENTION_DAYS = 3

        /** Where users who had auto-delete switched OFF land: Never, which is what
         *  OFF already meant. No behaviour change for them, and nothing to explain. */
        const val LEGACY_OFF_RETENTION_DAYS = RETENTION_NEVER_DAYS
    }
}
