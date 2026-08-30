package com.offline.dpadmessenger.backend.signal

import android.util.Log

/**
 * Low-volume, PII-safe diagnostic channel for "Signal is showing the wrong
 * name for this contact" reports.
 *
 * Why this is its own tag. Every piece of name-resolution logic lives in
 * [SignalMessageRepository], whose tag (`SigRepo`) is deliberately kept OUT of
 * the launcher's rolling-logcat filterspec because its thread dump printed raw
 * contact names, and a support bundle is not a place to ship someone's address
 * book. The consequence was that the exact class of report those lines exist to
 * explain — "Signal randomly attached another contact's name to this thread"
 * — arrived with literally zero evidence in the capture.
 *
 * `SigNames` splits the difference. It records the *decision*: which source won,
 * how many candidates matched, which recipients merged — with every
 * human-readable value reduced to a non-identifying token:
 *
 *   - service ids   -> first 8 hex chars ([sid]), enough to correlate lines
 *   - phone numbers -> last 4 digits ([num])
 *   - names         -> first character + length ([name]), e.g. `S~7`
 *
 * That answers "did this name come from the address book, the Signal profile,
 * or a persisted cache, and did two recipients get merged" without the bundle
 * carrying one readable name or full number. Support already knows the names
 * from the ticket; what they never have is which code path produced them.
 *
 * Volume budget — this tag has to be cheap enough to leave on permanently:
 *
 *   - one line per inbound message from a peer (the resolve decision)
 *   - one line per address-book lookup, which only runs when there is no
 *     cached name for that recipient
 *   - one line per *rename* or recipient merge — nothing for the hundreds of
 *     first-time contacts a Storage Service sync teaches at process start
 *   - one summary + one THREAD-DUMP block per process start
 *
 * An idle phone logs nothing at all, and a busy day costs a few dozen short
 * lines. INFO on purpose: DEBUG is the level a future `assumenosideeffects`
 * rule would strip, and this has to survive release builds.
 */
internal object SigNames {

    const val TAG = "SigNames"

    fun log(message: String) = Log.i(TAG, message)

    /** First 8 chars of a service id, PNI prefix preserved: `PNI:1e71d4b1`.
     *  Enough to tell two recipients apart and to match a thread across lines,
     *  far too little to address anyone. */
    fun sid(serviceId: String?): String {
        if (serviceId.isNullOrBlank()) return "-"
        val isPni = serviceId.startsWith("PNI:")
        val body = serviceId.removePrefix("PNI:").take(8)
        return if (isPni) "PNI:$body" else body
    }

    /** Last 4 digits of a phone number: `~3681`. */
    fun num(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        val digits = raw.filter { it.isDigit() }
        return if (digits.isEmpty()) "-" else "~" + digits.takeLast(4)
    }

    /** First character + length: `S~7`. Distinguishes the two candidate names
     *  in a report without reproducing either of them. */
    fun name(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        return "${raw.first()}~${raw.length}"
    }
}
