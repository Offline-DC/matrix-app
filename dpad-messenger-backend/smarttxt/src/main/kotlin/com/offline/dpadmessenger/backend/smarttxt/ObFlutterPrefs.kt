package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import java.io.File

/**
 * Reads a single string setting out of OpenBubbles' Flutter preferences.
 *
 * The one we want is `defaultHandle` — the address OpenBubbles sends new messages
 * from ("start messages from" in its UI), e.g. `tel:+14049801785` or
 * `mailto:you@icloud.com`. Same scheme-prefixed shape Smart Txt uses, so it maps
 * across without conversion.
 *
 * Why it matters: [OpenBubblesMigrator] marks the handle selection CONFIGURED as
 * part of the migration, which is what skips the post-login picker and drops the
 * user straight into their conversations. Without this the migration was picking
 * for them — "first tel:, else whatever's first" — so anyone who had deliberately
 * chosen their email would silently start sending from their phone number and
 * never be asked. Carrying the real value is what makes skipping the picker
 * honest rather than a guess.
 *
 * Two storage shapes, because Flutter changed its backing store and OpenBubbles
 * builds in the field straddle the change:
 *  - `files/datastore/FlutterSharedPreferences.preferences_pb` — a DataStore
 *    protobuf; keys are bare (`defaultHandle`).
 *  - `shared_prefs/FlutterSharedPreferences.xml` — the older plain XML; keys
 *    carry Flutter's `flutter.` prefix.
 *
 * Everything here is best-effort and bounds-checked: a setting we can't read
 * falls back to the previous behaviour, it never fails a migration.
 */
internal object ObFlutterPrefs {

    private const val TAG = "ObMigrator"

    /** Read [key] from either preferences shape, or null if absent/unreadable. */
    fun string(file: File, key: String): String? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            val bytes = file.readBytes()
            if (looksLikeXml(bytes)) xmlString(String(bytes, Charsets.UTF_8), key)
            else protoString(bytes, key)
        }.getOrElse {
            Log.w(TAG, "  prefs: ${file.name} unreadable: ${it.message}")
            null
        }
    }

    private fun looksLikeXml(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(64, b.size), Charsets.ISO_8859_1).trimStart()
        return head.startsWith("<?xml") || head.startsWith("<map")
    }

    // ── the XML shape ───────────────────────────────────────────────────

    /** `<string name="flutter.defaultHandle">tel:+1…</string>`. Flutter prefixes
     *  every key with `flutter.`, so try that first and the bare key second. */
    private fun xmlString(xml: String, key: String): String? {
        for (k in listOf("flutter.$key", key)) {
            val open = "<string name=\"$k\">"
            val i = xml.indexOf(open)
            if (i < 0) continue
            val j = xml.indexOf("</string>", i + open.length)
            if (j < 0) continue
            return unescapeXml(xml.substring(i + open.length, j))
        }
        return null
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")   // last: an escaped ampersand must not re-expand

    // ── the protobuf shape ──────────────────────────────────────────────

    /**
     * `PreferenceMap { map<string, Value> preferences = 1; }`, where a map entry is
     * `{1: key, 2: Value}` and `Value` is a oneof whose field `5` is the string case.
     * Only string values are of interest, so anything else is skipped rather than
     * modelled.
     */
    private fun protoString(b: ByteArray, key: String): String? {
        forEachField(b, 0, b.size) { field, wire, start, end ->
            // Top level: every map entry is field 1, length-delimited.
            if (field != 1 || wire != WIRE_LEN) return@forEachField true
            var k: String? = null
            var v: String? = null
            forEachField(b, start, end) { f2, w2, s2, e2 ->
                when {
                    f2 == 1 && w2 == WIRE_LEN -> k = String(b, s2, e2 - s2, Charsets.UTF_8)
                    f2 == 2 && w2 == WIRE_LEN -> forEachField(b, s2, e2) { f3, w3, s3, e3 ->
                        if (f3 == 5 && w3 == WIRE_LEN) v = String(b, s3, e3 - s3, Charsets.UTF_8)
                        true
                    }
                }
                true
            }
            if (k == key && v != null) { found = v; return@forEachField false }
            true
        }
        return found.also { found = null }
    }

    /** Set by [protoString]'s walk; read and cleared immediately, single-threaded
     *  (the migration runs one import at a time on its own IO thread). */
    private var found: String? = null

    private const val WIRE_VARINT = 0
    private const val WIRE_64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_32 = 5

    /**
     * Walk protobuf fields in `b[from, to)`, calling [block] with
     * `(fieldNumber, wireType, payloadStart, payloadEnd)`. Return false from
     * [block] to stop early. Anything malformed simply ends the walk — this parses
     * a file copied off another app, so it must not throw.
     */
    private inline fun forEachField(
        b: ByteArray,
        from: Int,
        to: Int,
        block: (field: Int, wire: Int, start: Int, end: Int) -> Boolean,
    ) {
        var i = from
        while (i < to) {
            val (tag, afterTag) = varint(b, i, to) ?: return
            i = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (wire) {
                WIRE_VARINT -> {
                    val (_, next) = varint(b, i, to) ?: return
                    if (!block(field, wire, i, next)) return
                    i = next
                }
                WIRE_64 -> { if (i + 8 > to) return; if (!block(field, wire, i, i + 8)) return; i += 8 }
                WIRE_32 -> { if (i + 4 > to) return; if (!block(field, wire, i, i + 4)) return; i += 4 }
                WIRE_LEN -> {
                    val (len, afterLen) = varint(b, i, to) ?: return
                    val end = afterLen + len.toInt()
                    if (len < 0 || end > to || end < afterLen) return
                    if (!block(field, wire, afterLen, end)) return
                    i = end
                }
                else -> return   // groups (3/4) — not emitted by this schema
            }
        }
    }

    /** Base-128 varint at [i], or null if it runs off [to] / is absurdly long. */
    private fun varint(b: ByteArray, i: Int, to: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var p = i
        while (p < to && shift <= 63) {
            val x = b[p].toInt() and 0xFF
            p++
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result to p
            shift += 7
        }
        return null
    }
}
