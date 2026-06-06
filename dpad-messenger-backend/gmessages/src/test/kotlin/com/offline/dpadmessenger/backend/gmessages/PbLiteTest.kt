package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64 as JBase64

class PbLiteTest {

    @Test
    fun base64RoundTripsAndMatchesStandardAlphabet() {
        for (len in 0..40) {
            val data = ByteArray(len) { (it * 7 + 1).toByte() }
            val mine = B64.encode(data)
            // Matches java.util.Base64 (standard, padded).
            assertEquals(JBase64.getEncoder().encodeToString(data), mine)
            // Round-trips.
            assertEquals(data.toList(), B64.decode(mine).toList())
        }
    }

    @Test
    fun streamSplitterYieldsPayloadsFromDoubleBracketWrapper() {
        val splitter = PbLite.StreamSplitter()
        // Google frames the stream as [[ payload, payload, … ]]. Feed it in
        // awkward chunks (including a split mid-payload and mid-string) to
        // exercise cross-chunk state.
        val parts = listOf("[[[1,2]", ",[3,[4,5]]", ",[\"a,b\",", "null]", "]]")
        val elements = parts.flatMap { splitter.feed(it) }
        assertEquals(listOf("[1,2]", "[3,[4,5]]", "[\"a,b\",null]"), elements)
    }

    @Test
    fun streamSplitterMatchesRealLongPollFraming() {
        // The exact opening Google sent in Jack's logcat: two config/ack
        // payloads, then the pair payload, then heartbeats.
        val splitter = PbLite.StreamSplitter()
        val stream = "[[[null,null,null,[]],[null,null,null,null,[]]," +
            "[null,[\"rid\",14,\"123\"]],[null,null,[]]"
        val elements = splitter.feed(stream)
        assertEquals(
            listOf("[null,null,null,[]]", "[null,null,null,null,[]]", "[null,[\"rid\",14,\"123\"]]", "[null,null,[]]"),
            elements,
        )
    }

    @Test
    fun jsonParserHandlesNestedArraysStringsNumbersNull() {
        val n = PbLite.parse("""["a",null,14,["x",123]]""")
        assertEquals("a", n[0].asStringOrNull())
        assertEquals(14, n[2].asIntOrNull())
        assertEquals("x", n[3][0].asStringOrNull())
        assertEquals(123, n[3][1].asIntOrNull())
        // Out-of-range and wrong-type access is null-safe.
        assertNull(n[99].asStringOrNull())
        assertNull(n[0].asIntOrNull())
    }

    @Test
    fun extractPairedResultDecodesRealFrame() {
        // RPCPairData produced by protoc (see session notes): paired mobile
        // sourceID="mobile-src-id", browser="browser-src-id",
        // tachyonAuthToken="\x11\x22\x33\x44longtoken".
        val rpcPairDataB64 =
            "IksKGAhvEg1tb2JpbGUtc3JjLWlkGgVCdWdsZRITCg0RIjNEbG9uZ3Rva2VuEICjBRoaCN4BEg5icm93c2VyLXNyYy1pZBoFQnVnbGU="
        // Wrap it in a LongPollingPayload pblite envelope:
        //   payload[1] = IncomingRPCMessage; msg[1]=bugleRoute=14; msg[11]=messageData.
        val msg = buildString {
            append("[null,14")          // idx0 responseID null, idx1 bugleRoute 14
            repeat(9) { append(",null") } // idx2..idx10
            append(",\"").append(rpcPairDataB64).append("\"") // idx11 messageData
            append("]")
        }
        val element = "[null,$msg]" // payload idx1 = msg
        val result = PbLite.extractPairedResult(element)
            ?: error("expected a paired result")
        assertEquals("mobile-src-id", result.mobileSourceId)
        assertEquals("browser-src-id", result.browserSourceId)
        // Token is 4 binary bytes (0x11 0x22 0x33 0x44) + "longtoken".
        assertEquals("longtoken", String(result.tachyonAuthToken.copyOfRange(4, result.tachyonAuthToken.size)))
        assertEquals(
            listOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte()),
            result.tachyonAuthToken.take(4),
        )
    }

    @Test
    fun extractPairedResultIgnoresNonPairFrames() {
        // bugleRoute 19 (DataEvent), not a pair.
        assertNull(PbLite.extractPairedResult("[null,[null,19,null]]"))
        // heartbeat-style payload with no data element.
        assertNull(PbLite.extractPairedResult("[null,null,[]]"))
    }

    @Test
    fun receiveMessagesRequestIsWellFormedPblite() {
        val body = PbLite.receiveMessagesRequest("req-123", byteArrayOf(1, 2, 3, 4))
        val n = PbLite.parse(body)
        val auth = n[0]
        assertEquals("req-123", auth[0].asStringOrNull())        // AuthMessage.requestID
        assertNull(auth[2].asStringOrNull())                     // AuthMessage.network MUST be empty
        assertEquals(B64.encode(byteArrayOf(1, 2, 3, 4)), auth[5].asStringOrNull()) // token
        assertEquals(2026, auth[6][2].asIntOrNull())             // ConfigVersion.Year
        assertEquals(6, auth[6][8].asIntOrNull())                // ConfigVersion.V2
    }
}
