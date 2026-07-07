package com.offline.dpadmessenger.backend.smarttxt

import com.offline.dpadmessenger.backend.smarttxt.transport.MockRelayTransport
import com.offline.dpadmessenger.backend.smarttxt.transport.RegisterResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the in-process relay simulator end-to-end (the path the app runs
 * against today): register → connect → seeded chats → send → echoed message.
 */
class MockRelayTransportTest {

    @Test
    fun `register succeeds with handles`() = runTest {
        val t = MockRelayTransport()
        val r = t.register(MacOSConfig.placeholder(), "demo@icloud.com")
        assertTrue(r is RegisterResult.Success)
        assertTrue((r as RegisterResult.Success).handles.any { it.contains("demo@icloud.com") })
        t.shutdown()
    }

    @Test
    fun `connect seeds demo chats and messages`() = runTest {
        val t = MockRelayTransport()
        t.connect()
        val chats = t.getChats()
        assertTrue("expected demo chats", chats.isNotEmpty())
        val withMsgs = chats.firstOrNull { t.getMessages(it.guid, 50, null).isNotEmpty() }
        assertNotNull("at least one chat should have messages", withMsgs)
        t.shutdown()
    }

    @Test
    fun `sendText echoes the message into the chat`() = runTest {
        val t = MockRelayTransport()
        t.connect()
        val chat = t.getChats().first()
        val ack = t.sendText(chat.guid, "Hello from a test", "tmp_1", null)
        assertTrue(ack.ok)
        assertNotNull(ack.guid)
        val msgs = t.getMessages(chat.guid, 50, null)
        assertTrue("sent text should be present", msgs.any { it.text == "Hello from a test" && it.isFromMe })
        t.shutdown()
    }

    @Test
    fun `createChat adds a conversation`() = runTest {
        val t = MockRelayTransport()
        t.connect()
        val before = t.getChats().size
        val chat = t.createChat(listOf("mailto:new@icloud.com"), null)
        assertNotNull(chat)
        assertEquals(before + 1, t.getChats().size)
        t.shutdown()
    }
}
