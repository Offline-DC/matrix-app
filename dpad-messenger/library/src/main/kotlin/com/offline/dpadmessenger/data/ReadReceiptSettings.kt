package com.offline.dpadmessenger.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Optional capability for repositories that can send read receipts (iMessage).
 * The settings screen surfaces a toggle when the repository implements this (the
 * mock doesn't, so its toggle stays hidden).
 *
 * Off by default: reading a chat always clears the notification on the user's OWN
 * other Apple devices, but the person who SENT the message is not told "Read"
 * unless this is turned on.
 */
interface ReadReceiptSettings {
    /** Whether outgoing read receipts (the sender sees "Read") are on. Defaults to false. */
    val sendReadReceipts: StateFlow<Boolean>

    /** Turn read receipts on/off (persisted). */
    fun setSendReadReceipts(enabled: Boolean)
}
