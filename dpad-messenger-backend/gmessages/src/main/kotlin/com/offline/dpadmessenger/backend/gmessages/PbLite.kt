package com.offline.dpadmessenger.backend.gmessages

/**
 * Google's "pblite" wire format — a protobuf message rendered as a JSON
 * array where array index `i` holds proto field number `i + 1`. Absent
 * fields are `null`; nested messages are nested arrays; `bytes` fields are
 * base64 (standard alphabet, padded) strings.
 *
 * The ReceiveMessages long-poll endpoint speaks pblite (content-type
 * `application/json+protobuf`), not binary protobuf — sending binary there
 * is what produced the HTTP 400. This file builds the request and parses the
 * streamed response.
 *
 * Reference: mautrix go-util `pblite/serialize.go` + `deserialize.go`, and
 * mautrix-gmessages `pkg/libgm/longpoll.go` for the streaming framing.
 *
 * Everything here is pure-JVM (no Android APIs, own base64) so it's covered
 * by unit tests; the only Android touch points stay in the pairing client.
 */
internal object PbLite {

    /**
     * Build the pblite JSON body for ReceiveMessagesRequest.
     *
     *   ReceiveMessagesRequest { auth=1, unknown=4 { unknown=2 {} } }
     *   AuthMessage            { requestID=1, network=3, tachyonAuthToken=6, configVersion=7 }
     *   ConfigVersion          { Year=3, Month=4, Day=5, V1=7, V2=9 }
     *
     * Field number N → array index N-1; trailing nulls omitted.
     */
    fun receiveMessagesRequest(
        requestId: String,
        tachyonAuthToken: ByteArray,
        network: String? = null,
    ): String {
        val tokenB64 = jsonString(B64.encode(tachyonAuthToken))
        // ConfigVersion: indices 2,3,4,6,8 set.
        val config = "[null,null,2026,3,18,null,4,null,6]"
        // AuthMessage: idx0 reqID, idx2 network, idx5 token, idx6 config.
        // NOTE: network MUST be empty for the QR/Bugle long-poll — mautrix's
        // AuthData.AuthNetwork() returns "" for non-Google-account pairing
        // (only RegisterPhoneRelay sends "Bugle"). Sending "Bugle" here makes
        // Google hold the connection open but never route the pair event to it.
        // For Google-account (GAIA) mode it MUST be "GDitto".
        val networkJson = if (network != null) jsonString(network) else "null"
        val auth = "[${jsonString(requestId)},null,$networkJson," +
            "null,null,$tokenB64,$config]"
        // ReceiveMessagesRequest: idx0 auth, idx3 unknown=[null,[]].
        return "[$auth,null,null,[null,[]]]"
    }

    /**
     * Splits the streamed long-poll body into its individual
     * LongPollingPayload arrays as they arrive.
     *
     * Google frames the stream with a DOUBLE-bracket wrapper:
     *   `[[ payload0 , payload1 , payload2 , … ]]`
     * (mautrix's reader discards the leading two bytes `[[` for the same
     * reason). So the payloads we want live at nesting depth 2 — emit a
     * payload each time the depth returns to 2, treat commas at depth 2 as
     * separators, and stop when the wrapper closes (`]` at depth 2).
     *
     * Feed socket chunks as they come; state persists across calls so
     * payloads split across chunk boundaries are handled.
     */
    class StreamSplitter {
        private val sb = StringBuilder()
        private var depth = 0
        private var inString = false
        private var escaped = false
        private var wrapperBrackets = 0 // leading '[' consumed as the [[ wrapper
        private var closed = false

        fun feed(chunk: String): List<String> {
            val out = ArrayList<String>()
            for (c in chunk) {
                if (closed) break
                // Defend against a stream whose payload bracket never closes:
                // bound the buffer so a misbehaving/hostile relay can't grow it
                // without limit (OOM). Real frames are far smaller than this.
                if (sb.length > MAX_PAYLOAD_CHARS) { closed = true; break }
                // Consume the two leading wrapper brackets first.
                if (wrapperBrackets < 2) {
                    if (c == '[') { wrapperBrackets++; depth = wrapperBrackets }
                    continue // ignore any whitespace before/between them
                }
                if (inString) {
                    sb.append(c)
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> { sb.append(c); inString = true }
                    '[', '{' -> { depth++; sb.append(c) }
                    ']', '}' -> {
                        if (c == ']' && depth == 2) {
                            closed = true // the [[ … ]] wrapper closed
                        } else {
                            depth--
                            sb.append(c)
                            if (depth == 2) { // one payload finished
                                out.add(sb.toString())
                                sb.setLength(0)
                            }
                        }
                    }
                    ',' -> if (depth > 2) sb.append(c) // commas between payloads are separators
                    else -> if (depth > 2) sb.append(c) // ignore whitespace between payloads
                }
            }
            return out
        }

        companion object {
            /** ~8 MB cap on a single un-closed payload before we abort the stream. */
            private const val MAX_PAYLOAD_CHARS = 8 * 1024 * 1024
        }
    }

    // --- Minimal JSON value model + parser (arrays/strings/numbers/null/bool)

    sealed class Node {
        data class Arr(val items: List<Node>) : Node()
        data class Str(val value: String) : Node()
        data class Num(val value: Double) : Node()
        data object Null : Node()
        data class Bool(val value: Boolean) : Node()

        /** Array element by index, or [Null] if out of range / not an array. */
        operator fun get(i: Int): Node =
            (this as? Arr)?.items?.getOrNull(i) ?: Null

        fun asIntOrNull(): Int? = (this as? Num)?.value?.toInt()
        /**
         * JSPB encodes 64-bit ints as STRINGS, because a double cannot hold them
         * losslessly. Accepting only [Num] meant every token TTL Google ever sent us
         * parsed as 0 — verified in the field 17 Aug 2026:
         * `signInGaia: TTL parsed as 0 — raw node=Str(value=86400000000)`. The 24h
         * fallback happened to match, so this hid for months while making the
         * proactive token refresh schedule pure guesswork.
         */
        fun asLongOrNull(): Long? = when (this) {
            is Num -> value.toLong()
            is Str -> value.trim().toLongOrNull()
            else -> null
        }
        fun asStringOrNull(): String? = (this as? Str)?.value
    }

    fun parse(json: String): Node = Parser(json).parseValue()

    /** JSON-escape a string and wrap it in quotes. */
    fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0
        // The input is network data; cap recursion so a deeply-nested frame
        // can't blow the stack (StackOverflowError isn't cleanly catchable).
        private var depth = 0

        fun parseValue(): Node {
            skipWs()
            if (i >= s.length) error("unexpected end of input")
            return when (val c = s[i]) {
                '[' -> parseArray()
                '{' -> { parseObjectSkip(); Node.Null }
                '"' -> Node.Str(parseString())
                'n' -> { expect("null"); Node.Null }
                't' -> { expect("true"); Node.Bool(true) }
                'f' -> { expect("false"); Node.Bool(false) }
                else -> if (c == '-' || c.isDigit()) Node.Num(parseNumber())
                else error("unexpected char '$c' at $i")
            }
        }

        private fun parseArray(): Node.Arr {
            if (++depth > MAX_DEPTH) error("json nesting too deep at $i")
            try {
                expectChar('[')
                val items = ArrayList<Node>()
                skipWs()
                if (peek() == ']') { i++; return Node.Arr(items) }
                while (true) {
                    items.add(parseValue())
                    skipWs()
                    when (peek()) {
                        ',' -> { i++; skipWs() }
                        ']' -> { i++; break }
                        else -> error("expected , or ] at $i")
                    }
                }
                return Node.Arr(items)
            } finally {
                depth--
            }
        }

        private fun parseObjectSkip() {
            if (++depth > MAX_DEPTH) error("json nesting too deep at $i")
            try {
                expectChar('{')
                skipWs()
                if (peek() == '}') { i++; return }
                while (true) {
                    skipWs(); parseString(); skipWs(); expectChar(':'); parseValue(); skipWs()
                    when (peek()) {
                        ',' -> i++
                        '}' -> { i++; break }
                        else -> error("expected , or } at $i")
                    }
                }
            } finally {
                depth--
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) error("unterminated string at $i")
                val c = s[i++]
                when (c) {
                    '"' -> break
                    '\\' -> {
                        if (i >= s.length) error("truncated escape at $i")
                        when (val e = s[i++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) error("truncated \\u escape at $i")
                                out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun peek(): Char = if (i < s.length) s[i] else error("unexpected end of input at $i")
        private fun expectChar(c: Char) {
            skipWs()
            if (i >= s.length || s[i] != c) error("expected '$c' at $i")
            i++
        }
        private fun expect(word: String) { require(s.startsWith(word, i)) { "expected '$word' at $i" }; i += word.length }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        companion object {
            private const val MAX_DEPTH = 200
        }
    }

    /**
     * Inspect one LongPollingPayload element and, if it carries a pair
     * confirmation, return the decoded [GMPairingProto.PairedResult].
     *
     *   LongPollingPayload { data=2 }
     *   IncomingRPCMessage { bugleRoute=2, messageData=12 (RPCPairData bytes) }
     *
     * bugleRoute PairEvent = 14.
     */
    fun extractPairedResult(element: String): GMPairingProto.PairedResult? {
        val node = parse(element) as? Node.Arr ?: return null
        val data = node[1] as? Node.Arr ?: return null // field 2: IncomingRPCMessage
        if (data[1].asIntOrNull() != 14) return null     // field 2: bugleRoute == PairEvent
        val md = data[11].asStringOrNull() ?: return null // field 12: messageData (base64)
        return GMPairingProto.parsePairedData(B64.decode(md))
    }
}

/**
 * Minimal standard-alphabet Base64 (RFC 4648, padded). Hand-rolled because
 * `java.util.Base64` is API 26+ and :gmessages targets minSdk 24, and
 * because keeping it dependency-free lets [PbLite] stay unit-tested on the
 * JVM. Encodes without line wraps; decoding ignores whitespace.
 */
internal object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { for (idx in ALPHABET.indices) it[ALPHABET[idx].code] = idx }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F])
                .append(ALPHABET[n ushr 6 and 0x3F]).append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].toInt() and 0xFF shl 16
                sb.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F]).append("==")
            }
            2 -> {
                val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
                sb.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F])
                    .append(ALPHABET[n ushr 6 and 0x3F]).append('=')
            }
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in s) {
            if (c == '=' || c.code >= 128) continue
            val v = DECODE[c.code]
            if (v < 0) continue // skip whitespace / non-alphabet
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
