package com.offline.dpadmessenger.backend.gmessages

import com.offline.dpadmessenger.data.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * matrix-app#30: a Google Messages room with a blank id reached the room list
 * (absent proto string fields decode to ""), and pressing it crashed the
 * messenger with `navigate("chat/")`. The rows were also persisted in the
 * cache, so the crash repeated on every visit.
 */
class BlankConversationIdTest {

    @Test
    fun blankConversationIdIsNotUsable() {
        assertFalse(GoogleMessagesMessageRepository.isUsableConversationId(""))
        assertFalse(GoogleMessagesMessageRepository.isUsableConversationId(" "))
        assertTrue(GoogleMessagesMessageRepository.isUsableConversationId("42"))
    }

    @Test
    fun cachedBlankRoomIsDroppedOnRestore() {
        val cached = listOf(
            Room(id = "12", name = "Mom"),
            Room(id = "", name = "Unknown sender"),
            Room(id = "34", name = "Sam"),
        )
        val restored = GoogleMessagesMessageRepository.withoutBlankRoomIds(cached)
        assertEquals(listOf("12", "34"), restored.map { it.id })
    }

    @Test
    fun messageWithoutConversationIdDecodesToBlank() {
        // The wire shape behind the bug: a Message proto with no field 7
        // (conversation id). GMSessionProto must decode it to "" (not throw),
        // and that "" must be rejected before it can become a room.
        val bytes = ProtoWriter().string(1, "m1").toByteArray()
        val msg = GMSessionProto.parseMessage(bytes)
        assertEquals("", msg.conversationId)
        assertFalse(GoogleMessagesMessageRepository.isUsableConversationId(msg.conversationId))
    }

    @Test
    fun messagesWithoutConversationIdNeverReachTheRepository() {
        // onMessages / onConversations iterate withUsableIds(...), so this is the
        // exact list they file into rooms.
        val blank = GMSessionProto.parseMessage(ProtoWriter().string(1, "m1").toByteArray())
        val real = GMSessionProto.parseMessage(ProtoWriter().string(1, "m2").string(7, "42").toByteArray())
        val kept = GoogleMessagesMessageRepository.withUsableIds(listOf(blank, real)) { it.conversationId }
        assertEquals(listOf("m2"), kept.map { it.messageId })
    }
}
