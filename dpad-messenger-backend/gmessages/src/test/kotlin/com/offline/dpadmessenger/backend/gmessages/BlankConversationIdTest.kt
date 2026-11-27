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

    // Google sometimes leaves the top-level ids unset and only sends them
    // nested (mautrix/gmessages b580a1a). These must not decode to blank.

    @Test
    fun messageFallsBackToNestedConversationAndMessageIds() {
        val group = ProtoWriter().string(1, "42").toByteArray()
        val another = ProtoWriter().string(1, "m9").toByteArray()
        val msg = GMSessionProto.parseMessage(
            ProtoWriter().bytes(23, group).bytes(24, another).toByteArray()
        )
        assertEquals("42", msg.conversationId)
        assertEquals("m9", msg.messageId)
        assertEquals(1, GoogleMessagesMessageRepository.withUsableIds(listOf(msg)) { it.conversationId }.size)
    }

    @Test
    fun topLevelIdsStillWinOverNestedOnes() {
        val group = ProtoWriter().string(1, "nested").toByteArray()
        val msg = GMSessionProto.parseMessage(
            ProtoWriter().string(1, "m1").string(7, "top").bytes(23, group).toByteArray()
        )
        assertEquals("top", msg.conversationId)
        assertEquals("m1", msg.messageId)
    }

    @Test
    fun conversationFallsBackToNestedConversationId() {
        val group = ProtoWriter().string(1, "77").toByteArray()
        val conv = GMSessionProto.parseConversation(ProtoWriter().string(2, "Mom").bytes(31, group).toByteArray())
        assertEquals("77", conv.conversationId)
        val top = GMSessionProto.parseConversation(ProtoWriter().string(1, "5").bytes(31, group).toByteArray())
        assertEquals("5", top.conversationId)
    }

    @Test
    fun getOrCreateConversationResponseUsesNestedId() {
        // "Couldn't start a conversation" when only the nested id came back.
        val group = ProtoWriter().string(1, "88").toByteArray()
        val conv = ProtoWriter().bytes(31, group).toByteArray()
        val resp = ProtoWriter().bytes(2, conv).toByteArray()
        assertEquals("88", GMSessionProto.parseGetOrCreateConversationResponse(resp)?.conversationId)
    }
}
