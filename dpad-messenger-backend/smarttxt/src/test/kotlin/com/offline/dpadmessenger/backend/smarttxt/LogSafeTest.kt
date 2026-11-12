package com.offline.dpadmessenger.backend.smarttxt

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Smart Txt log never holds an email address in full (owner, 2026-11-06 / 9 Nov). */
class LogSafeTest {

    private fun mask(s: String) = LogSafe.maskEmails(s)

    @Test fun `the lines that leaked in the device test come out masked`() {
        assertEquals(
            "I/SmartTxtRust( 1): nativeSendText: is_sms=false from_handle=mailto:s*****@dumb.co has_reply=false",
            mask("I/SmartTxtRust( 1): nativeSendText: is_sms=false from_handle=mailto:someone@dumb.co has_reply=false"),
        )
        assertEquals(
            "IDS TARGETS mailto:s*****@dumb.co → [tel:+14045550123:7 mailto:s*****@dumb.co:3]",
            mask("IDS TARGETS mailto:someone@dumb.co → [tel:+14045550123:7 mailto:someone@dumb.co:3]"),
        )
        assertEquals(
            "  handles=[tel:+14045550123, mailto:s*****@dumb.co] appleId=s*****@dumb.co",
            mask("  handles=[tel:+14045550123, mailto:someone@dumb.co] appleId=someone@dumb.co"),
        )
    }

    @Test fun `several addresses and odd edges`() {
        assertEquals("a*****@x.io,b*****@sub.example.com.", mask("a@x.io,b.c+tag@sub.example.com."))
        assertEquals("(f*****@icloud.com)", mask("(first.last@icloud.com)"))
        assertEquals("\"x*****@me.com\":1", mask("\"x@me.com\":1"))
    }

    // The Rust twin trims a trailing '-' off the domain, so an address with a '-'
    // straight after it is masked there; it is masked here too (10 Nov review).
    @Test fun `an address followed by a dash is masked`() {
        assertEquals("s*****@dumb.co-", mask("someone@dumb.co-"))
        assertEquals("s*****@dumb.co--", mask("someone@dumb.co--"))
        assertEquals("to=s*****%40dumb.co-&x=1", mask("to=someone%40dumb.co-&x=1"))
        assertEquals("s*****@dumb.co-", mask(mask("someone@dumb.co-")))
        // A dash inside the domain is still part of it.
        assertEquals("s*****@dumb-x.co.uk", mask("someone@dumb-x.co.uk"))
        // A TLD running on into a '-' is masked here too; the Rust twin leaves it.
        assertEquals("x*****@dumb.co-op", mask("xy@dumb.co-op"))
    }

    @Test fun `masking twice changes nothing`() {
        val once = mask("to=mailto:someone@dumb.co")
        assertEquals(once, mask(once))
        assertEquals("handles=[\"mailto:d*****@dumb.co\"]", mask("handles=[\"mailto:d*****@dumb.co\"]"))
    }

    @Test fun `things that are not addresses are left alone`() {
        for (s in listOf(
            "no at sign here", "scale@2x", "user@localhost", "v@1.2.3", "@dumb.co",
            "trailing@", "tel:+14045550123", "bar@foo.c0m", "émoji 😀 and ünïcode, no address",
        )) assertEquals(s, s, mask(s))
    }

    @Test fun `text around an address is kept intact`() {
        assertEquals("ünï s*****@dumb.co 😀", mask("ünï someone@dumb.co 😀"))
    }

    @Test fun `a URL-encoded at sign is masked too`() {
        assertEquals("accountName=s*****%40dumb.co&x=1", mask("accountName=someone%40dumb.co&x=1"))
        assertEquals("50%40 off", mask("50%40 off"))
    }

    @Test fun `a URL-encoded plus address is masked from its first letter`() {
        assertEquals("x=f*****%40dumb.co", mask("x=foo%2Bbar%40dumb.co"))
        assertEquals("f*****@dumb.co", mask("foo%2Bbar@dumb.co"))
        assertEquals("prs_account_nm=s*****%40dumb.co&x=1", mask("prs_account_nm=someone%2Btag%40dumb.co&x=1"))
        assertEquals("x=f*****%40dumb.co", mask(mask("x=foo%2Bbar%40dumb.co")))
        assertEquals("50%40 off", mask("50%40 off"))
    }

    // A second address joined to the first by an escape ("%2C", an encoded comma)
    // starts right where the first one's mask ends; it is masked too, as in the
    // Rust twin (10 Nov review).
    @Test fun `addresses joined by an escape are all masked`() {
        val cases = listOf(
            "to=a%40dumb.co%2Cb%40dumb.co" to "to=a*****%40dumb.co%*****%40dumb.co",
            "to=a@dumb.co%2Cb@dumb.co" to "to=a*****@dumb.co%*****@dumb.co",
            // A URL-encoded JSON list: ["someone@dumb.co","other@dumb.co"].
            "q=%5B%22someone%40dumb.co%22%2C%22other%40dumb.co%22%5D" to "q=%*****%40dumb.co%*****%40dumb.co%22%5D",
            "x=a%40b%2Bc%40dumb.co" to "x=a*****%40dumb.co",
        )
        for ((raw, masked) in cases) {
            assertEquals(raw, masked, mask(raw))
            assertEquals(raw, masked, mask(masked))
        }
    }

    @Test fun `a long run of escapes is fast`() {
        for (line in listOf("%2B".repeat(10_000) + "@", "%2B".repeat(10_000) + "@dumb.co", "a%40".repeat(10_000) + "dumb.co")) {
            val t0 = System.nanoTime()
            LogSafe.scrub(line)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assert(ms < 500) { "took ${ms}ms" }
        }
        assertEquals("%*****@dumb.co", mask("%2B".repeat(10_000) + "@dumb.co"))
    }

    // A domain the Rust twin masks is masked here too, odd ones included (10 Nov review).
    @Test fun `a domain with an empty label is masked as in the Rust twin`() {
        assertEquals("s*****@dumb..co", mask("someone@dumb..co"))
        assertEquals("s*****@.dumb.co", mask("someone@.dumb.co"))
        assertEquals("s*****%40dumb..co", mask("someone%40dumb..co"))
    }

    // The old regex went one stack level deeper per domain label and ran out of
    // stack on a 1 MB thread (about what an Android worker thread gets) at about
    // 1,300 labels. The domain is walked by hand now (10 Nov review).
    @Test fun `a domain with thousands of labels doesn't overflow a small stack`() {
        val domain = "a.".repeat(5_000) + "co"
        var result: Any? = null
        val t = Thread(null, {
            result = try {
                listOf(mask("x@$domain"), mask("@$domain"), LogSafe.scrub("x%40$domain"))
            } catch (e: Throwable) { e }
        }, "small-stack", 1L shl 20)
        t.start()
        t.join()
        assertEquals(listOf("x*****@$domain", "@$domain", "x*****%40$domain"), result)
    }

    @Test fun `a message body dumped as hex is collapsed and the sender masked`() {
        val hex = "1f8b0800000000000003".repeat(10) // 200 hex chars
        assertEquals(
            "ID send message IDSSendMessage { sender: \"mailto:s*****@dumb.co\", raw: body <200 chars redacted>, x: 1 }",
            LogSafe.scrub("ID send message IDSSendMessage { sender: \"mailto:someone@dumb.co\", raw: body $hex, x: 1 }"),
        )
    }

    @Test fun `ids, tokens and ordinary text stay readable`() {
        for (s in listOf(
            "uuid=d7c7d74bede24e5ea8fa5e502906a500",
            "token=15b2a216c0ffee15b2a216c0ffee15b2a216c0ffee15b2a216c0ffee1234",
            "a normal sentence with words",
        )) assertEquals(s, s, LogSafe.scrub(s))
    }

    @Test fun `a long run without an at sign is fast`() {
        val line = "x".repeat(20_000) + " " + "a.".repeat(5_000)
        val t0 = System.nanoTime()
        LogSafe.scrub(line)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assert(ms < 500) { "took ${ms}ms" }
    }

    // Ring lines as `logcat -v threadtime` writes them (SmartTxtLogRing).
    private fun ring(level: Char, msg: String) = "11-06 15:00:00.000  1234  5678 $level SmartTxtRust: $msg"

    @Test fun `old message dumps are dropped at export and other old lines cleaned`() {
        assertEquals(null, LogSafe.scrubForExport(ring('D',
            "rustpush::imessage::messages: sending: Ok(Dictionary({\"t\": String(\"Hi\")}))")))
        assertEquals(null, LogSafe.scrubForExport(ring('D', "rustpush::imessage::messages: xml: <html>Hi</html>")))
        assertEquals(
            ring('I', "rustpush::ids::identity_manager: IDS RX to=mailto:s*****@dumb.co"),
            LogSafe.scrubForExport(ring('I', "rustpush::ids::identity_manager: IDS RX to=mailto:someone@dumb.co")),
        )
    }

    @Test fun `every debug or verbose SmartTxtRust line is dropped at export`() {
        // A continuation chunk of a dump over 4000 bytes: the tag, but no module path.
        assertEquals(null, LogSafe.scrubForExport(ring('D', "String(\"see you at 6\"), \"p\": Array([String(\"tel:+14045550123\")])")))
        assertEquals(null, LogSafe.scrubForExport(ring('D', "rustpush::imessage::messages: ati: \"<plist>Hi</plist>\"")))
        assertEquals(null, LogSafe.scrubForExport(ring('V', "rustpush::imessage::messages: anything")))
    }

    @Test fun `info, warning and error lines and other tags are kept at export`() {
        for (level in listOf('I', 'W', 'E')) {
            val line = ring(level, "rustpush::aps: Send timed out (keepalive-pong)")
            assertEquals(line, LogSafe.scrubForExport(line))
        }
        val kotlin = "11-06 15:00:00.000  1234  5678 D IMsgRepo: sync done rooms=3"
        assertEquals(kotlin, LogSafe.scrubForExport(kotlin))
        assertEquals("--- [ring] started", LogSafe.scrubForExport("--- [ring] started"))
    }
}
