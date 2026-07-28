package com.offline.dpadmessenger.backend.signal

import android.util.Log
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Renders Signal `BodyRange` mention metadata into the plain body text we
 * store on a [com.offline.dpadmessenger.data.Message].
 *
 * Signal does NOT put "@Avery" in the message body. It puts a single
 * U+FFFC (OBJECT REPLACEMENT CHARACTER) placeholder there and ships the
 * identity of the mentioned person out-of-band in a `BodyRange`:
 *
 *     body       = "￼ what's a good day for this on your end?"
 *     bodyRanges = [ { start: 0, length: 1, mentionAci: "<uuid>" } ]
 *
 * A client that ignores `bodyRanges` therefore draws the raw placeholder
 * glyph — on Android that's the "[OBJ]" tofu box. Since the shared UI
 * renders `message.body` directly with a Compose `Text`, we resolve the
 * mentions here, at ingest, and store readable text. That keeps the whole
 * fix inside the Signal backend: no model field, no UI change, and
 * persisted/notification copy is correct for free.
 *
 * Offsets are UTF-16 code units per the proto, which is exactly what
 * Kotlin `String` indices are — so no transcoding is needed. Nothing here
 * assumes the covered text IS the placeholder; we replace whatever the
 * range covers, the same way Signal's own clients do.
 *
 * `BodyRange.Style` (bold/italic/spoiler/strikethrough/monospace) rides in
 * the same repeated field but does not insert placeholder characters, so it
 * has no visual bug to fix. Those ranges are deliberately left alone — the
 * body reads correctly without them, and honoring them would mean plumbing
 * an `AnnotatedString` through the shared timeline.
 */
internal object SignalBodyRanges {

    private const val TAG = "SignalBodyRanges"

    /** Unicode OBJECT REPLACEMENT CHARACTER: the mention placeholder. */
    const val PLACEHOLDER = '￼'

    /** Shown when a mention's ACI matches nobody we know. */
    const val UNKNOWN_MENTION = "@Unknown"

    /** Shown when a mention points at our own account. */
    const val SELF_MENTION = "@You"

    /** A mention range reduced to just what rendering needs. Kept free of
     *  proto/Android types so the splicing logic stays trivially testable. */
    data class Mention(val start: Int, val length: Int, val aci: String)

    /**
     * Resolve every mention in [body] to "@Name" text.
     *
     * [resolveName] maps an ACI to a display name (without the leading "@")
     * or null when the id is unknown — see
     * [SignalMessageRepository.knownNameFor]. Unresolvable mentions render
     * as [UNKNOWN_MENTION] rather than leaking the ACI or the placeholder.
     *
     * Safe to call unconditionally: a body with no mentions comes back
     * unchanged apart from a defensive sweep for orphan placeholders (a
     * mention whose range was dropped, truncated, or never sent).
     */
    fun render(
        body: String,
        ranges: List<SignalServiceProtos.BodyRange>,
        resolveName: (String) -> String?,
    ): String = renderMentions(body, mentionsOf(ranges), resolveName)

    /** Proto → [Mention], keeping only ranges that actually name someone. */
    fun mentionsOf(ranges: List<SignalServiceProtos.BodyRange>): List<Mention> =
        ranges.mapNotNull { range ->
            val aci = mentionAciOf(range) ?: return@mapNotNull null
            Mention(
                start = if (range.hasStart()) range.start else 0,
                length = if (range.hasLength()) range.length else 0,
                aci = aci,
            )
        }

    /**
     * The ACI a range mentions, or null if the range is a style (or carries
     * a malformed id). Signal is mid-migration from the string `mentionAci`
     * to the 16-byte `mentionAciBinary`, so both forms have to be read; the
     * binary form is normalized to the same lowercase UUID string our rooms
     * and caches are keyed by.
     */
    fun mentionAciOf(range: SignalServiceProtos.BodyRange): String? {
        if (range.hasMentionAci() && range.mentionAci.isNotBlank()) return range.mentionAci
        if (!range.hasMentionAciBinary()) return null
        val bytes = range.mentionAciBinary.toByteArray()
        if (bytes.size != 16) {
            Log.w(TAG, "mentionAciBinary has ${bytes.size} bytes, expected 16 — skipping")
            return null
        }
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long).toString()
    }

    /**
     * Splice resolved mention text into [body]. Pure string work — no proto,
     * no Android — so it can be reasoned about (and exercised) on its own.
     *
     * Ranges are applied left to right. Anything out of bounds, negative, or
     * overlapping a range we already applied is dropped rather than allowed
     * to corrupt the body: a hostile or buggy sender must not be able to
     * make us throw on the receive path.
     */
    fun renderMentions(
        body: String,
        mentions: List<Mention>,
        resolveName: (String) -> String?,
    ): String {
        if (body.isEmpty()) return body
        if (mentions.isEmpty()) return stripOrphanPlaceholders(body)

        val ordered = mentions.sortedWith(compareBy({ it.start }, { it.length }))
        val out = StringBuilder(body.length + 16)
        var cursor = 0  // next index of `body` not yet copied out

        for (m in ordered) {
            val end = m.start + m.length
            if (m.start < 0 || m.length < 0 || end > body.length) {
                Log.w(TAG, "mention range [${m.start}, $end) outside body of ${body.length} — skipping")
                continue
            }
            if (m.start < cursor) {
                Log.w(TAG, "mention range [${m.start}, $end) overlaps an earlier one — skipping")
                continue
            }
            out.append(body, cursor, m.start)
            out.append(displayFor(m.aci, resolveName))
            cursor = end
        }
        out.append(body, cursor, body.length)
        return stripOrphanPlaceholders(out.toString())
    }

    /** "@" + the best name we have for [aci], else [UNKNOWN_MENTION]. */
    private fun displayFor(aci: String, resolveName: (String) -> String?): String {
        val name = resolveName(aci)?.let(::sanitizeName)
        if (name.isNullOrEmpty()) return UNKNOWN_MENTION
        return if (name.startsWith("@")) name else "@$name"
    }

    /** A profile name is attacker-controlled: flatten newlines/tabs so a
     *  mention can't break the bubble across lines, and cap the length. */
    private fun sanitizeName(raw: String): String {
        val flattened = raw.map { if (it.isWhitespace()) ' ' else it }
            .joinToString("")
            .trim()
            .replace(Regex(" {2,}"), " ")
        return if (flattened.length <= MAX_NAME_CHARS) flattened
        else flattened.take(MAX_NAME_CHARS).trimEnd() + "…"
    }

    /**
     * Drop placeholders no range claimed. Without this a mention whose range
     * we couldn't use (bad offsets, missing ACI, a style-only bodyRanges
     * list) would still draw the "[OBJ]" glyph — the exact symptom this file
     * exists to remove. The surrounding spacing is tidied so removing the
     * character doesn't leave a double space or a leading gap.
     */
    private fun stripOrphanPlaceholders(text: String): String {
        if (text.indexOf(PLACEHOLDER) < 0) return text
        Log.w(TAG, "body carried unmatched mention placeholder(s) — stripping")
        return text.replace(PLACEHOLDER.toString(), "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    /** Longest mention name we'll render before eliding. The target screen is
     *  240x320; anything past this is noise in a bubble regardless. */
    private const val MAX_NAME_CHARS = 40
}
