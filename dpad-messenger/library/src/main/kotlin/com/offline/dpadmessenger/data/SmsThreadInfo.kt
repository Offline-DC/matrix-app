package com.offline.dpadmessenger.data

/**
 * Optional capability: a repository that can tell whether a conversation sends as
 * green SMS vs blue iMessage. Lets the composer show the right color BEFORE the
 * first message is sent (iMessage-style), instead of only after a message lands.
 */
interface SmsThreadInfo {
    /** True if this thread would send as SMS (green): the recipient isn't on
     *  iMessage, or the thread already has SMS history. */
    suspend fun isSmsThread(roomId: String): Boolean
}
