package com.offline.dpadmessenger.backend.smarttxt.transport

/**
 * Authoritative SmartTxt protocol constants, modeled on the actual
 * OpenBubbles / BlueBubbles / rustpush implementations (the relay this app
 * talks to is BlueBubbles-shaped — OpenBubbles is a BlueBubbles fork,
 * `com.openbubbles.messaging`, service `…services.rustpush.APNService`).
 *
 * Keeping these in one place means the relay/native transports and the
 * repository all agree on the same wire semantics that the real servers use,
 * rather than ad-hoc strings.
 */

/**
 * SmartTxt **tapbacks**, with BlueBubbles' `associatedMessageType` integer
 * codes (confirmed against BlueBubbles' `parseReactionType`):
 *  - 2000 love, 2001 like, 2002 dislike, 2003 laugh, 2004 emphasize, 2005 question
 *  - 3000..3005 = REMOVAL of the corresponding tapback (add code + 1000)
 *
 * The six classic tapbacks map to fixed emoji for the UI; modern SmartTxt also
 * allows arbitrary-emoji tapbacks (sticker reactions), which carry their own
 * type and pass through as the raw emoji (SMARTTXT_NATIVE_BACKEND_PLAN.md §5).
 */
enum class Tapback(val addCode: Int, val emoji: String) {
    LOVE(2000, "❤️"),
    LIKE(2001, "👍"),
    DISLIKE(2002, "👎"),
    LAUGH(2003, "😂"),
    EMPHASIZE(2004, "‼️"),
    QUESTION(2005, "❓");

    val removeCode: Int get() = addCode + 1000

    companion object {
        /** Map an emoji the UI produced to a BlueBubbles associatedMessageType.
         *  Returns null for arbitrary emoji (sent as an emoji sticker tapback,
         *  which the relay handles out-of-band). */
        fun codeForEmoji(emoji: String, remove: Boolean): Int? {
            val tb = entries.firstOrNull { it.emoji == emoji } ?: return null
            return if (remove) tb.removeCode else tb.addCode
        }

        /** Map a BlueBubbles associatedMessageType to (emoji, isRemoval). Returns
         *  null for non-tapback messages (type 0) or unknown codes. */
        fun fromCode(type: Int): Pair<String, Boolean>? {
            if (type == 0) return null
            val add = entries.firstOrNull { it.addCode == type }
            if (add != null) return add.emoji to false
            val rem = entries.firstOrNull { it.removeCode == type }
            if (rem != null) return rem.emoji to true
            return null
        }
    }
}

/**
 * Apple Cocoa epoch helpers. SmartTxt timestamps in the raw protocol (and in
 * OpenBubbles' ObjectBox store) are **nanoseconds since 2001-01-01 UTC**, not
 * the Unix epoch — a classic source of off-by-31-years bugs. The relay
 * normalizes to Unix-ms on the wire, but the native path deals in raw Cocoa
 * time, so the conversion lives here.
 */
object AppleEpoch {
    /** Seconds between Unix epoch (1970) and Apple Cocoa epoch (2001). */
    const val UNIX_TO_COCOA_SECONDS = 978_307_200L

    /** Cocoa nanoseconds → Unix milliseconds. */
    fun cocoaNanosToUnixMs(cocoaNanos: Long): Long =
        cocoaNanos / 1_000_000L + UNIX_TO_COCOA_SECONDS * 1000L

    /** Unix milliseconds → Cocoa nanoseconds. */
    fun unixMsToCocoaNanos(unixMs: Long): Long =
        (unixMs - UNIX_TO_COCOA_SECONDS * 1000L) * 1_000_000L
}

/**
 * BlueBubbles chat GUID handling. A chat GUID encodes the service and address:
 *  - 1:1: `SmartTxt;-;+15551234567` or `SMS;-;+15551234567`
 *  - group: `SmartTxt;+;chat4827...` (an opaque group id)
 * The middle token is `-` for DMs and `+` for groups.
 */
object ChatGuid {
    fun service(guid: String): String = guid.substringBefore(';', "iMessage").ifBlank { "iMessage" }
    fun isGroup(guid: String): Boolean = guid.split(';').getOrNull(1) == "+"
    /** The address/identifier portion (a handle for DMs, a group id for groups). */
    fun identifier(guid: String): String = guid.substringAfterLast(';', guid)

    /** Canonicalise the address so a DM's guid matches the one the native side
     *  emits for the same person (phone/email/format variants collapse). */
    fun forDm(address: String, service: String = "iMessage"): String = "$service;-;${Handles.canon(address)}"

    /** Group guid from Apple's stable group id (gid): "iMessage;+;<gid>". A group is
     *  identified by this opaque id — NOT its member set — so two groups with the same
     *  people (e.g. a named "sibs & sav" and an unnamed thread) stay distinct and a
     *  reply threads into the exact conversation. Matches BlueBubbles/OpenBubbles chat
     *  GUIDs, and the native side (which keys inbound groups by the same gid). The gid
     *  is lowercased so it keys identically however it's cased. */
    fun forGroup(groupId: String, service: String = "iMessage"): String =
        "$service;+;${groupId.trim().lowercase()}"
}

/**
 * Canonical handle key. MUST match the Rust `canon` in `smarttxt-ffi/src/lib.rs`
 * byte-for-byte, so a person's inbound thread, outbound thread, and contact entry
 * all key identically: scheme stripped, email lowercased, phone reduced to
 * "+<digits>" (a US 10-digit number gets a +1). This is what fixes the thread
 * splitting and the contact-name resolution.
 */
object Handles {
    fun canon(handle: String): String {
        var s = handle.trim()
        s = when {
            s.startsWith("tel:") -> s.removePrefix("tel:")
            s.startsWith("mailto:") -> s.removePrefix("mailto:")
            else -> s
        }.trim()
        if (s.contains('@')) return s.lowercase()
        val digits = s.filter { it.isDigit() }
        return when {
            s.startsWith('+') -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith('1') -> "+$digits"
            digits.isEmpty() -> s.lowercase()
            else -> "+$digits"
        }
    }

    // ---- relayed-SMS handle repair -------------------------------------------
    //
    // MUST match `smarttxt-ffi/src/group_identity.rs` (is_nanp_e164 / bare_plus_form /
    // requalify_national) for the same reason `canon` must: both sides key rooms.
    //
    // rustpush's `normalize_sms_handle` makes E.164 out of a bare all-digit SMS handle
    // by prefixing '+' and stopping there, so a carrier that delivers a national-format
    // number ("4097821402") yields the bogus "+4097821402" — country code 40, Romania.
    // `canon` cannot undo it: its "10 bare digits get a +1" rule only fires on a handle
    // with no '+' yet. See SMARTTXT_GREEN_SPLIT_THREAD_COUNTRY_CODE_20260830.md.
    //
    // The native side now repairs this at ingest, so these exist for one job: the
    // one-time repair of rooms already written to disk by an older build.

    /** True for a canon NANP number: "+1" followed by exactly ten digits. */
    fun isNanpE164(canon: String): Boolean =
        canon.startsWith("+1") && canon.length == 12 && canon.drop(2).all { it.isDigit() }

    /** The bogus form `normalize_sms_handle` produces for a known-good NANP number:
     *  "+18048334449" -> "+8048334449". Computing the damage forward from a number we
     *  trust lets us match a mangled handle by equality instead of guessing backward. */
    fun barePlusForm(canonE164: String): String? =
        if (isNanpE164(canonE164)) "+" + canonE164.drop(2) else null

    /** Re-qualify a handle that arrived as '+' plus a bare national number.
     *
     *  Deliberately narrow: fires only for a NANP account, on '+' plus exactly ten
     *  digits in NANP shape (area and exchange codes start 2-9) — never a valid NANP
     *  number, since those are '+1' plus ten. A legitimate ten-digit E.164 exists
     *  elsewhere (Norway "+47…", Denmark "+45…") and cannot be told apart from a
     *  dropped country code without a real libphonenumber, so everything else is
     *  returned untouched. */
    fun requalifyNational(canon: String, myCanon: String): String {
        if (!isNanpE164(myCanon)) return canon
        if (!canon.startsWith("+")) return canon
        val digits = canon.drop(1)
        if (digits.length != 10 || !digits.all { it.isDigit() }) return canon
        if (digits[0] !in '2'..'9' || digits[3] !in '2'..'9') return canon
        return "+1$digits"
    }
}

/** SmartTxt services: blue (SmartTxt) vs green (SMS/RCS relayed). */
object SmartTxtService {
    const val SMARTTXT = "iMessage"
    const val SMS = "SMS"
}
