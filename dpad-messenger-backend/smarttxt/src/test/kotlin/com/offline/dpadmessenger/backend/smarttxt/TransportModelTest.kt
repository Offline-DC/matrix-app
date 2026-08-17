package com.offline.dpadmessenger.backend.smarttxt

import com.offline.dpadmessenger.backend.smarttxt.transport.AppleEpoch
import com.offline.dpadmessenger.backend.smarttxt.transport.ChatGuid
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayMessage
import com.offline.dpadmessenger.backend.smarttxt.transport.RelayProtocol
import com.offline.dpadmessenger.backend.smarttxt.transport.Tapback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the BlueBubbles/OpenBubbles-modeled protocol constants. */
class TransportModelTest {

    @Test
    fun `tapback emoji to associatedMessageType matches BlueBubbles codes`() {
        assertEquals(2000, Tapback.codeForEmoji("❤️", remove = false))
        assertEquals(2001, Tapback.codeForEmoji("👍", remove = false))
        assertEquals(3001, Tapback.codeForEmoji("👍", remove = true))
        assertEquals(2003, Tapback.codeForEmoji("😂", remove = false))
        // Arbitrary emoji → no classic code (sent as a sticker tapback).
        assertNull(Tapback.codeForEmoji("🦆", remove = false))
    }

    @Test
    fun `associatedMessageType decodes to emoji and removal flag`() {
        assertEquals("❤️" to false, Tapback.fromCode(2000))
        assertEquals("👍" to true, Tapback.fromCode(3001))
        assertNull(Tapback.fromCode(0))       // not a tapback
        assertNull(Tapback.fromCode(12345))   // unknown
    }

    @Test
    fun `apple cocoa epoch converts correctly`() {
        // 2001-01-01T00:00:00Z is Unix ms 978307200000 and Cocoa nanos 0.
        assertEquals(0L, AppleEpoch.unixMsToCocoaNanos(978_307_200_000L))
        assertEquals(978_307_200_000L, AppleEpoch.cocoaNanosToUnixMs(0L))
        // Round-trip a recent timestamp.
        val now = 1_736_000_000_000L
        assertEquals(now, AppleEpoch.cocoaNanosToUnixMs(AppleEpoch.unixMsToCocoaNanos(now)))
    }

    @Test
    fun `chat guid encodes service and group-ness`() {
        val dm = ChatGuid.forDm("+15551234567")
        assertEquals("iMessage;-;+15551234567", dm)
        assertEquals("iMessage", ChatGuid.service(dm))
        assertFalse(ChatGuid.isGroup(dm))
        assertTrue(ChatGuid.isGroup("SmartTxt;+;chat4827"))
        assertEquals("SMS", ChatGuid.service("SMS;-;+15551234567"))
    }

    @Test
    fun `relay message wire round-trips with BlueBubbles fields`() {
        val original = RelayMessage(
            guid = "abc", chatGuid = "SmartTxt;-;+1555", senderAddress = "tel:+1555",
            text = "hi", timestampMs = 1_736_000_000_000L, dateDelivered = 1_736_000_001_000L,
            associatedMessageType = 2000, associatedMessageGuid = "target",
            expressiveSendStyleId = "com.apple.MobileSMS.expressivesend.impact",
        )
        val text = RelayProtocol.json.encodeToString(RelayMessage.serializer(), original)
        val back = RelayProtocol.json.decodeFromString(RelayMessage.serializer(), text)
        assertEquals(original, back)
        assertEquals(2000, back.associatedMessageType)
    }
}
