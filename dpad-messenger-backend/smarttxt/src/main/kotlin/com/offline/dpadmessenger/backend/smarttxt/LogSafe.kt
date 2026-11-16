package com.offline.dpadmessenger.backend.smarttxt

/**
 * Log-safe text, the Kotlin twin of smarttxt-ffi's `log_safe.rs`.
 *
 * The Smart Txt log is emailed to the team on "report". The owner's rule
 * (2026-11-06, confirmed after the 9 Nov device test): it never holds an email
 * address in full — the Apple ID is never needed to debug — and never message
 * text. Phone numbers are kept. Only for log text, never for anything sent.
 */
internal object LogSafe {
    /** A local-part character, as the Rust twin's `is_local`. '%' counts, so a
     *  URL-encoded plus address ("someone%2Btag%40dumb.co") is masked from its
     *  first letter, not from the middle of the "%2B" (10 Nov review). ASCII only. */
    private fun isLocal(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '%' || c == '+' || c == '-'

    private fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * Where the domain starting at [from] (just after an at sign) ends, or -1 if
     * there is none: the longest run of letters, digits, '.' and '-' that has a
     * '.' in it, ends in a label of two or more letters and has no letter or
     * digit straight after it. Anything the Rust twin takes as a domain is one
     * here too, with the same end, "dumb..co" and ".dumb.co" included. A '-' may
     * follow the last label: the Rust twin trims a trailing '-', so
     * "someone@dumb.co-" is masked on both sides (10 Nov review). That also masks
     * "x@dumb.co-op", which the Rust twin leaves; masking more is the safe way
     * round.
     *
     * Walked by hand, not with a regex: java.util.regex went one stack level
     * deeper per label, so an '@' followed by a couple of thousand "a." labels
     * ran out of stack on a 1 MB thread (10 Nov review).
     */
    private fun domainEnd(text: String, from: Int): Int {
        var end = -1
        var dot = false // a '.' seen since [from]
        var tld = 0 // letters since the last '.', or -1 once a digit or '-' is among them
        var j = from
        while (true) {
            val c = if (j < text.length) text[j] else ' '
            if (dot && tld >= 2 && !isLetter(c) && c !in '0'..'9') end = j
            when {
                c == '.' -> { dot = true; tld = 0 }
                isLetter(c) -> if (tld >= 0) tld++
                c in '0'..'9' || c == '-' -> tld = -1
                else -> return end
            }
            j++
        }
    }

    /** Hex / base64 runs this long are payloads (message plists, auth replies), not
     *  diagnostics; UUIDs (32) and push tokens or SHA-256 (64) stay readable. */
    private val BLOB = Regex("""[A-Za-z0-9+/=]{96,}+""")

    /**
     * "someone@dumb.co" → "s*****@dumb.co" (and "%40" kept as written). Already
     * masked text is left alone ('*' isn't a local-part character), so masking
     * twice changes nothing.
     *
     * The local part is the run of local-part characters right before the at
     * sign, but it never reaches back into the address masked before it. In
     * "a%40dumb.co%2Cb%40dumb.co" (two addresses joined by an encoded comma) the
     * second one starts right after the first, at the "%2C"; a regex that needed
     * the local part to start at a token boundary left it in full (10 Nov
     * review). Bounded that way, no character is walked over twice, so a long
     * run of escapes stays fast. A domain walk stops at the next '@' or '%', so
     * those don't overlap either.
     */
    fun maskEmails(text: String): String {
        if (text.indexOf('@') < 0 && !text.contains("%40")) return text
        val out = StringBuilder(text.length)
        var copied = 0 // chars of [text] already in out
        var i = 0
        while (i < text.length) {
            val atLen = if (text[i] == '@') 1 else if (text.startsWith("%40", i)) 3 else 0
            val end = if (atLen == 0) -1 else domainEnd(text, i + atLen)
            if (end < 0) { i++; continue }
            var start = i
            while (start > copied && isLocal(text[start - 1])) start--
            if (start == i) { i++; continue } // no local part: "@dumb.co", or already masked
            // Keep the first character of the local part, drop the rest.
            out.append(text, copied, start + 1).append("*****").append(text, i, end)
            copied = end
            i = end
        }
        if (copied == 0) return text
        return out.append(text, copied, text.length).toString()
    }

    /** Long hex / base64 runs → "<N chars redacted>": rustpush logs every outgoing
     *  message as `raw: body <hex>`, which is the message's plist (text and all). */
    fun collapseBlobs(text: String): String =
        if (text.length < 96) text
        else BLOB.replace(text) { m -> "<${m.value.length} chars redacted>" }

    /** What every line kept for "report" goes through: emails masked, blobs collapsed. */
    fun scrub(line: String): String = collapseBlobs(maskEmails(line))

    /**
     * For a line already written to the ring by an older build, at export: null to
     * drop it entirely, else [scrub]bed. Builds before 9 Nov ran rustpush's message
     * module at debug, which logged message text ("sending: Ok(Dictionary({"t": …",
     * "xml: …", "ati: …"). The native ceiling is Info now, so a debug or verbose
     * SmartTxtRust line can only come from such a build, and every one is dropped.
     * Matching the level, not the module path, also drops the continuation chunks
     * of a dump over 4000 bytes: logcat gives each chunk the tag but not the
     * module path (10 Nov review). The ring writes `-v threadtime`, which puts the
     * level as one letter before the tag. A build that raises the ceiling to chase
     * something at debug gets those lines in adb logcat, not in the report.
     */
    fun scrubForExport(line: String): String? =
        if (line.contains(" D SmartTxtRust: ") || line.contains(" V SmartTxtRust: ")) null
        else scrub(line)
}
