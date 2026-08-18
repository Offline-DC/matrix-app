package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ---- asLongOrNull: JSPB encodes 64-bit ints as STRINGS -------------------
    //
    // These guard the change that fixed `signInGaia: TTL parsed as 0 — raw
    // node=Str(value=86400000000)` (field, 17 Aug 2026). The 24h fallback happened
    // to equal the real TTL, so a Num-only reader hid for months while leaving the
    // proactive token refresh scheduled off a guess.

    @Test
    fun asLongOrNullReadsANumberNode() {
        assertEquals(600L, PbLite.Node.Num(600.0).asLongOrNull())
        assertEquals(0L, PbLite.Node.Num(0.0).asLongOrNull())
        assertEquals(-1L, PbLite.Node.Num(-1.0).asLongOrNull())
    }

    @Test
    fun asLongOrNullReadsTheStringFormGoogleActuallySends() {
        // The exact node from the field log.
        assertEquals(86_400_000_000L, PbLite.Node.Str("86400000000").asLongOrNull())
        // And end-to-end through the parser, in the wire shape it arrives in.
        assertEquals(86_400_000_000L, PbLite.parse("[\"86400000000\"]")[0].asLongOrNull())
    }

    @Test
    fun asLongOrNullKeepsFullPrecisionOnTheStringPath() {
        // This is WHY JSPB string-encodes 64-bit fields: a Double holds integers
        // exactly only up to 2^53. Going through Num would silently round; the Str
        // path must not. If someone "simplifies" this to value.toDouble().toLong(),
        // this test is what catches it.
        val beyondDouble = 9_007_199_254_740_993L // 2^53 + 1
        assertEquals(beyondDouble, PbLite.Node.Str(beyondDouble.toString()).asLongOrNull())
        assertNotEquals(beyondDouble, beyondDouble.toDouble().toLong())
    }

    @Test
    fun asLongOrNullTrimsWhitespaceAndRejectsNonNumericStrings() {
        assertEquals(42L, PbLite.Node.Str(" 42 ").asLongOrNull())
        assertNull(PbLite.Node.Str("").asLongOrNull())
        assertNull(PbLite.Node.Str("abc").asLongOrNull())
        assertNull(PbLite.Node.Str("1.5").asLongOrNull())        // not an integer
        assertNull(PbLite.Node.Str("99999999999999999999").asLongOrNull()) // overflows Long
    }

    @Test
    fun asLongOrNullRejectsEveryOtherNodeType() {
        assertNull(PbLite.Node.Null.asLongOrNull())
        assertNull(PbLite.Node.Bool(true).asLongOrNull())
        assertNull(PbLite.Node.Arr(listOf(PbLite.Node.Num(1.0))).asLongOrNull())
        // Out-of-range index yields Null, not an exception.
        assertNull(PbLite.parse("[1]")[9].asLongOrNull())
    }

    @Test
    fun asIntOrNullStaysNumOnlyOnPurpose() {
        // Deliberate asymmetry, not an oversight. JSPB string-encodes only the
        // 64-bit scalar types (int64/uint64/sint64/fixed64); int32 fields arrive as
        // JSON numbers. Widening asIntOrNull too would let a genuinely malformed
        // payload parse as valid, so it stays strict.
        assertEquals(3, PbLite.Node.Num(3.0).asIntOrNull())
        assertNull(PbLite.Node.Str("3").asIntOrNull())
    }

    // ---- the two load-bearing call sites the widening also changed ------------
    //
    // asLongOrNull has 8 callers, and two of them decide which phone this device
    // pairs to (GMGaiaClient.signInGaia). Widening was correct, but it is a
    // BEHAVIOUR CHANGE on any account whose device list arrives string-encoded,
    // and these two tests are here so that is on the record rather than a surprise.

    @Test
    fun stringEncodedPrimaryFlagNowSelectsThePhone() {
        // signInGaia: `if (item[3].asLongOrNull() != 1L) return@mapNotNull null`.
        // Pre-fix, a Str("1") returned null and the device was skipped — an account
        // could report "no primary phone (UnknownInt4==1)" and send the user to the
        // phone's Device-pairing screen for nothing.
        assertEquals(1L, PbLite.parse("[\"uuid\",null,null,\"1\"]")[3].asLongOrNull())
        assertEquals(1L, PbLite.parse("[\"uuid\",null,null,1]")[3].asLongOrNull())
    }

    @Test
    fun stringEncodedLastSeenNowOrdersNewestFirst() {
        // signInGaia picks the primary by `maxByOrNull { lastSeen }`. Pre-fix every
        // string-encoded timestamp read as 0, so on a multi-phone account the "newest"
        // pick was really just the first one. Microsecond epochs are far beyond 2^53's
        // safety margin for arithmetic, which is exactly why they are strings.
        val older = PbLite.Node.Str("1755432000000000").asLongOrNull()!!
        val newer = PbLite.Node.Str("1755435600000000").asLongOrNull()!!
        assertEquals(newer, maxOf(older, newer))
        assertNotEquals(0L, older)
    }
}
