package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Wire-format tests for the authenticated session protos.
 *
 * The binary golden vectors (CONV/MSG/RPC/UE/LCR) were produced by an
 * independent Python protobuf encoder against the field numbers in
 * mautrix-gmessages `conversations.proto` / `rpc.proto` / `events.proto`.
 * If our readers decode them correctly, our field numbering matches what
 * Google's phone actually sends.
 *
 * The pblite envelopes are asserted as exact JSON, since that's literally
 * the bytes that go on the wire.
 */
class GMSessionProtoTest {

    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    // --- incoming binary protos --------------------------------------------

    @Test
    fun parsesConversationFromProtocBytes() {
        val conv = "CgVDT05WMRIAIg0KC2hlbGxvIHRoZXJlKICA+cDBxIIDMAFQAFoHUEFSVF9NRXoHIzExMjIzM6IBMQ" +
            "oWEgwrMTU1NTExMTIyMjIaBlBBUlRfQRIFQWxpY2UaB0FsaWNlIEEqByNEODFCNjCqAQZQQVJUX0E="
        val c = GMSessionProto.parseConversation(unb64(conv))
        assertEquals("CONV1", c.conversationId)
        assertEquals("hello there", c.latestMessageText)
        assertEquals(1700000000000000L, c.lastMessageTimestampMicros)
        assertTrue(c.unread)
        assertFalse(c.isGroupChat)
        assertEquals("PART_ME", c.defaultOutgoingId)      // who we send as
        assertEquals("#112233", c.avatarHexColor)
        assertEquals(listOf("PART_A"), c.otherParticipantIds)
        assertEquals(1, c.participants.size)
        val p = c.participants[0]
        assertEquals("PART_A", p.participantId)
        assertEquals("Alice A", p.fullName)
        assertEquals("+15551112222", p.number)
        assertFalse(p.isMe)
    }

    @Test
    fun parsesIncomingMessageFromProtocBytes() {
        val msg = "CgRNU0cxIgIQbCigwpfBwcSCAzoFQ09OVjFKBlBBUlRfQVIREg8KDUhpIGZyb20gQWxpY2VYBGIA"
        val m = GMSessionProto.parseMessage(unb64(msg))
        assertEquals("MSG1", m.messageId)
        assertEquals(108, m.statusCode)          // INCOMING_DELIVERED
        assertFalse(m.isOutgoing)
        assertEquals(1700000000500000L, m.timestampMicros)
        assertEquals("CONV1", m.conversationId)
        assertEquals("PART_A", m.participantId)
        assertEquals("Hi from Alice", m.text)
        assertFalse(m.hasMedia)
    }

    @Test
    fun parsesMessageReactions() {
        // Message with three ReactionEntry values (field 19), protoc-encoded:
        // 👍 LIKE by PART_A+PART_ME, 🎉 CUSTOM by PART_A, and a type-only
        // RED_HEART (no unicode — must resolve via the EmojiType table).
        val msg = "CgRNU0dSIgIQZCjAhLbBwcSCAzoFQ09OVjFKBlBBUlRfQVIPEg0KC3JlYWN0IHRvIG1lmgEbCggKBPCf" +
            "kY0QARIGUEFSVF9BEgdQQVJUX01FmgESCggKBPCfjokQCBIGUEFSVF9BmgEMCgIQDBIGUEFSVF9C"
        val m = GMSessionProto.parseMessage(unb64(msg))
        assertEquals(3, m.reactions.size)
        assertEquals("👍", m.reactions[0].emoji)
        assertEquals(listOf("PART_A", "PART_ME"), m.reactions[0].participantIds)
        assertEquals("🎉", m.reactions[1].emoji)              // CUSTOM keeps its unicode
        assertEquals("❤️", m.reactions[2].emoji)              // type-only → table lookup
        assertEquals(listOf("PART_B"), m.reactions[2].participantIds)
    }

    @Test
    fun sendReactionRequestRoundTrips() {
        val bytes = GMSessionProto.sendReactionRequest("MSG1", "👍", GMSessionProto.REACTION_ADD)
        val f = ProtoReader.fields(bytes)
        assertEquals("MSG1", f[1]!!.bytes!!.toString(Charsets.UTF_8))
        assertEquals(GMSessionProto.REACTION_ADD.toLong(), f[3]!!.value)
        val rd = ProtoReader.fields(f[2]!!.bytes!!)
        assertEquals("👍", rd[1]!!.bytes!!.toString(Charsets.UTF_8))
        assertEquals(1L, rd[2]!!.value) // EmojiType LIKE

        // Unknown emoji → CUSTOM (8), unicode carried verbatim.
        val custom = ProtoReader.fields(
            ProtoReader.fields(
                GMSessionProto.sendReactionRequest("M", "🎉", GMSessionProto.REACTION_REMOVE)
            )[2]!!.bytes!!
        )
        assertEquals(8L, custom[2]!!.value)
    }

    @Test
    fun outgoingMessageStatusIsDetected() {
        // status 1 = OUTGOING_COMPLETE -> isOutgoing
        val m = GMSessionProto.parseMessage(
            ProtoWriter().string(1, "X").message(4, ProtoWriter().int32(2, 1)).toByteArray()
        )
        assertTrue(m.isOutgoing)
        assertFalse(m.isTombstone)
    }

    @Test
    fun parsesRpcMessageData() {
        val rpc = "CgdyZXEtMTIzIBBCA6q7zA=="
        val d = GMSessionProto.parseRpcMessageData(unb64(rpc))
        assertEquals("req-123", d.sessionId)
        assertEquals(GMSessionProto.ACTION_GET_UPDATES, d.action) // 16
        assertEquals(listOf(0xAA, 0xBB, 0xCC), d.encryptedData!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun parsesUpdateEventsMessageEvent() {
        val ue = "GjsSOQoETVNHMSICEGwooMKXwcHEggM6BUNPTlYxSgZQQVJUX0FSERIPCg1IaSBmcm9tIEFsaWNlWARiAA=="
        val updates = GMSessionProto.parseUpdateEvents(unb64(ue))
        assertEquals(1, updates.messages.size)
        assertEquals("Hi from Alice", updates.messages[0].text)
        assertTrue(updates.conversations.isEmpty())
        assertFalse(updates.isBrowserPresenceCheck)
    }

    @Test
    fun parsesListConversationsResponseRepeated() {
        val lcr = "EnQKBUNPTlYxEgAiDQoLaGVsbG8gdGhlcmUogID5wMHEggMwAVAAWgdQQVJUX01FegcjMTEyMjMzog" +
            "ExChYSDCsxNTU1MTExMjIyMhoGUEFSVF9BEgVBbGljZRoHQWxpY2UgQSoHI0Q4MUI2MKoBBlBBUlRfQRJ0" +
            "CgVDT05WMRIAIg0KC2hlbGxvIHRoZXJlKICA+cDBxIIDMAFQAFoHUEFSVF9NRXoHIzExMjIzM6IBMQoWEg" +
            "wrMTU1NTExMTIyMjIaBlBBUlRfQRIFQWxpY2UaB0FsaWNlIEEqByNEODFCNjCqAQZQQVJUX0E="
        val convs = GMSessionProto.parseListConversationsResponse(unb64(lcr))
        assertEquals(2, convs.size)
        assertEquals("CONV1", convs[0].conversationId)
    }

    @Test
    fun parsesListContactsResponse() {
        // Two protoc-encoded contacts; first has formattedNumber + color,
        // second only the raw number.
        val resp = "EjgKAlAxEgdBbGljZSBBGiAIAhIMKzE0MDQ5ODAxNzg1Ig4oNDA0KSA5ODAtMTc4NToHI0Q4MUI2MB" +
            "IbCgJQMhIDQm9iGhAIAhIMKzEyMDI1NTUwMTIz"
        val contacts = GMSessionProto.parseListContactsResponse(unb64(resp))
        assertEquals(2, contacts.size)
        assertEquals("Alice A", contacts[0].name)
        assertEquals("(404) 980-1785", contacts[0].number) // prefers formattedNumber
        assertEquals("#D81B60", contacts[0].avatarHexColor)
        assertEquals("Bob", contacts[1].name)
        assertEquals("+12025550123", contacts[1].number)   // falls back to raw number
    }

    // --- long-poll element routing -----------------------------------------

    @Test
    fun longPollDataElementParsed() {
        // LongPollingPayload { data=2: IncomingRPCMessage{responseID=1, route=2=19, messageData=12} }
        val md = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val element = "[null,[\"resp-1\",19,null,null,null,null,null,null,null,null,null,\"$md\"]]"
        val evt = GMSessionProto.parseLongPollElement(element)
        assertTrue(evt is GMSessionProto.LongPollEvent.Data)
        val data = (evt as GMSessionProto.LongPollEvent.Data).rpc
        assertEquals("resp-1", data.responseId)
        assertEquals(GMSessionProto.ROUTE_DATA_EVENT, data.bugleRoute)
        assertEquals(listOf(1, 2, 3), data.messageData!!.map { it.toInt() })
    }

    @Test
    fun longPollAckAndHeartbeatParsed() {
        // field 4 = StartAckMessage { count=1 }
        val ack = GMSessionProto.parseLongPollElement("[null,null,null,[7]]")
        assertEquals(GMSessionProto.LongPollEvent.AckCount(7), ack)
        // field 3 = heartbeat
        assertEquals(
            GMSessionProto.LongPollEvent.Heartbeat,
            GMSessionProto.parseLongPollElement("[null,null,[]]"),
        )
    }

    // --- outgoing round-trips ----------------------------------------------

    @Test
    fun sendMessageRequestRoundTripsThroughReader() {
        val bytes = GMSessionProto.sendMessageRequest(
            conversationId = "CONV1",
            text = "yo",
            tmpId = "tmp_42",
            participantId = "PART_ME",
            replyToMessageId = "MSG1",
        )
        val top = ProtoReader.fields(bytes)
        assertEquals("CONV1", top[2]!!.bytes!!.toString(Charsets.UTF_8))
        assertEquals("tmp_42", top[5]!!.bytes!!.toString(Charsets.UTF_8))
        // reply payload field 8 { messageID=1 }
        assertEquals("MSG1", ProtoReader.fields(top[8]!!.bytes!!)[1]!!.bytes!!.toString(Charsets.UTF_8))
        // payload field 3: participantID=9, messageInfo=10{messageContent=2{content=1}}
        val payload = ProtoReader.fields(top[3]!!.bytes!!)
        assertEquals("PART_ME", payload[9]!!.bytes!!.toString(Charsets.UTF_8))
        val info = ProtoReader.fields(payload[10]!!.bytes!!)
        val content = ProtoReader.fields(info[2]!!.bytes!!)
        assertEquals("yo", content[1]!!.bytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun outgoingRpcDataRoundTrips() {
        val data = GMSessionProto.outgoingRpcData(
            requestId = "req-9", action = GMSessionProto.ACTION_SEND_MESSAGE,
            encryptedProtoData = byteArrayOf(9, 8, 7), sessionId = "sess-1",
        )
        val f = ProtoReader.fields(data)
        assertEquals("req-9", f[1]!!.bytes!!.toString(Charsets.UTF_8))
        assertEquals(GMSessionProto.ACTION_SEND_MESSAGE.toLong(), f[2]!!.value)
        assertEquals("sess-1", f[6]!!.bytes!!.toString(Charsets.UTF_8))
        assertEquals(listOf(9, 8, 7), f[5]!!.bytes!!.map { it.toInt() })
    }

    @Test
    fun outgoingRpcMessageEnvelopeIsWellFormedPblite() {
        val mobile = GMDeviceInfo(userId = 42, sourceId = "mobile-src", network = "")
        val env = GMSessionProto.outgoingRpcMessage(
            mobile = mobile, requestId = "rid-1",
            messageData = byteArrayOf(1, 2), messageType = GMSessionProto.MSGTYPE_BUGLE_MESSAGE,
            tachyonAuthToken = byteArrayOf(5, 6), ttl = 1234L,
        )
        val n = PbLite.parse(env)
        // mobile=idx0 Device [userID, sourceID, network]
        assertEquals(42, n[0][0].asIntOrNull())
        assertEquals("mobile-src", n[0][1].asStringOrNull())
        // data=idx1: requestID=idx0, bugleRoute=idx1(19), messageData=idx11
        assertEquals("rid-1", n[1][0].asStringOrNull())
        assertEquals(GMSessionProto.ROUTE_DATA_EVENT, n[1][1].asIntOrNull())
        assertEquals(B64.encode(byteArrayOf(1, 2)), n[1][11].asStringOrNull())
        // messageTypeData=idx22 -> [[], messageType]
        assertEquals(GMSessionProto.MSGTYPE_BUGLE_MESSAGE, n[1][22][1].asIntOrNull())
        // auth=idx2: token at idx5
        assertEquals(B64.encode(byteArrayOf(5, 6)), n[2][5].asStringOrNull())
        // TTL=idx4
        assertEquals(1234, n[4].asIntOrNull())
    }

    @Test
    fun ackMessageRequestIsWellFormedPblite() {
        val browser = GMDeviceInfo(userId = 1, sourceId = "browser-src", network = "Bugle")
        val body = GMSessionProto.ackMessageRequest(
            requestId = "ack-req", tachyonAuthToken = byteArrayOf(1),
            browser = browser, ackIds = listOf("m1", "m2"),
        )
        val n = PbLite.parse(body)
        assertEquals("ack-req", n[0][0].asStringOrNull())       // authData.requestID
        // acks=idx3: [[m1,[dev]],[m2,[dev]]]
        assertEquals("m1", n[3][0][0].asStringOrNull())
        assertEquals("browser-src", n[3][0][1][1].asStringOrNull())
        assertEquals("m2", n[3][1][0].asStringOrNull())
    }

    @Test
    fun parsesRegisterRefreshResponse() {
        // [null,[ tokenB64, ttl ]]  (RegisterRefreshResponse{ tokenData=2 })
        val tokenB64 = B64.encode(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        val refreshed = GMSessionProto.parseRegisterRefreshResponse("[null,[\"$tokenB64\",86400000000]]")!!
        assertEquals(listOf(0xAB, 0xCD), refreshed.tachyonAuthToken.map { it.toInt() and 0xFF })
        assertEquals(86400000000L, refreshed.ttl)
    }

    @Test
    fun nonDataNonAckElementYieldsNull() {
        assertNull(GMSessionProto.parseLongPollElement("[]"))
    }
}
