package com.offline.dpadmessenger.backend.gmessages

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Ask GOOGLE — not the phone — whether this device's registration still exists.
 *
 * ## Why this file exists
 *
 * Every unpair detector we had before this reasons from an ABSENCE: the paired phone
 * stops echoing `BROWSER_ACTIVE`, and after long enough we conclude we are unpaired.
 * That is sound but slow and imprecise, for two measured reasons:
 *
 *  - **MEASURED (Ben, 53 healthy re-asserts):** 2 echoed LATE, at +768 s and +884 s. So a
 *    missing echo cannot be trusted for ~15 min, which is why
 *    `UNPAIRED_CONFIDENCE_MS` is 20 min.
 *  - **MEASURED (two customers, 32 of 32 attempts):** `setActiveSession` returns HTTP 200
 *    throughout a total outage, so no status code tells us anything.
 *
 * A *positive* check has none of those problems. And Google's own web client has one.
 *
 * ## What it is
 *
 * `Registration/SignInGaia` takes a mode in **request field 3** (our `sparse` index 2):
 *
 *  - `mode = 0` (what [GMGaiaClient] sends, as `null`) — mint a tachyon token AND return
 *    the account's device list. This is the one with side effects: it upserts a
 *    `messages-web-*` registration, which is why a fresh UUID per attempt made the
 *    account's device list grow (see [GMGaiaClient] and OQ-10).
 *  - `mode = 1` — **list only.** No token minted, nothing registered, phone not involved,
 *    cookie/SAPISIDHASH auth only. On gRPC NOT_FOUND it returns an empty list.
 *
 * **MEASURED-FROM-SOURCE (26 Aug 2026, Google's live `messagesweb` JS bundle):** the real
 * web client calls `mode = 1` on **every warm start**, both to re-check that its stored
 * dest phone is still the primary and — in the routine the bundle calls `La()` — to find
 * **its own** registration in the returned list by id, throwing if it is absent. So this
 * is not an exotic call; it is what a browser tab does when you open it. mautrix has it
 * implemented as `signInGaiaInitial` and never calls it.
 *
 * ## Response shape
 *
 * ```
 * SignInGaiaResponse
 *   1: header
 *   2: bytes   -> OUR OWN registration id, ASCII inside base64 (root[1])
 *   3: AccountInfo                                              (root[2])
 *        2: repeated Item   <- mautrix's unknownItems2           (root[2][1])
 *        3: repeated Item   <- mautrix's unknownItems3           (root[2][2])
 *                              ** the list the real client uses **
 *   4: TokenData            <- mode 0 only; never read here
 * ```
 *
 * Item (pblite index = protoField − 1):
 *
 * | idx | field | meaning |
 * |---|---|---|
 * | 0 | 1 | registration id, ASCII inside base64 |
 * | 2 | 3 | platform: **1 and 4 = Android surfaces, 6 = web surface** |
 * | 6 | 7 | last-updated, **MICROseconds** — the bundle logs it as
 * |   |   |  `lastUpdatedTime` and reads it as "last refreshed auth token" |
 * | 7 | 8 | `[enabled: bool, time: int64 MILLIseconds, key: bytes,
 * |   |   |  isPreWarmSupported: bool]` |
 *
 * Two corrections to the field map in [GMGaiaClient] are folded in above and are
 * **MEASURED-FROM-SOURCE**, not guesses: index 6 is last-updated (not "maybe device
 * creation"), and index 7's second slot is int64 **ms** (not int32).
 *
 * ## What this class does NOT do
 *
 * It does not mint, persist, pair, or touch the live session. It logs. **The verdict must
 * not drive any UI until the paired/unpaired diff below has actually been captured** —
 * this project has retracted four mechanisms that were invented on top of a sound
 * measurement, and `00_START_HERE.md` rule 2.1b exists because of them.
 *
 * ## The one thing that is UNKNOWN, and the experiment that settles it
 *
 * A deliberate unpair revokes the **GAIA pairing**. It is **UNKNOWN** whether it also
 * removes or disables the **registration** this list contains. Evidence it might not:
 * `RegisterRefresh` returned **HTTP 200 with a fresh token at 11:30:28 on 26 Aug while the
 * device was unpaired** (doc §27), so the registration plainly outlived that unpair.
 * Evidence it might: each item carries an explicit `enabled` bool, and the real client
 * filters on it.
 *
 * So there are three possible outcomes, and all three are useful:
 *
 *  1. our entry **disappears** → instant, definitive detection; promote it.
 *  2. our entry stays but `enabled` flips **false** → same, on a different field.
 *  3. nothing changes → this cannot see a pairing revoke. Say so, keep the probe purely
 *     as the OQ-20 instrument (see below), and rely on the faster echo cadence instead.
 *
 * Either way `lastUpdated` is the **OQ-20 / mechanism-B instrument we have been missing**:
 * if our own registration's last-updated stamp does not advance while we stream traffic
 * all day, then Google does not count what we do as "use", which is the last surviving
 * explanation for entries vanishing at 6–7 days — and it reads that answer in minutes
 * instead of a 7-day wait.
 */
internal object GMDeviceListProbe {

    /**
     * **Deliberately `GMSession`, not a tag of its own.**
     *
     * The rolling logcat that customers mail to support is tag-filtered — `GMGaia` is
     * explicitly kept out of it because it carries the tachyon token. A brand-new tag
     * risks landing outside whatever that filter allows, and probe output that never
     * reaches support is worth nothing: this is the one line that tells us whether a
     * reporting customer's own registration (and their PHONE's, `platform=1`) is still
     * there. `GMSession` appears throughout every customer capture we have, so it is
     * known-captured.
     *
     * Safe to share the tag: nothing here is a credential. Mode 1 returns no token, we
     * never read response field 4, and registration ids are random UUIDs.
     */
    private const val TAG = "GMSession"

    /** One entry in the account's registration list. */
    internal data class Entry(
        val regId: String,
        val platform: Long?,
        val enabled: Boolean?,
        val timeMs: Long?,
        val lastUpdatedMicros: Long?,
    ) {
        val isWebSurface: Boolean get() = platform == 6L
        val isAndroidSurface: Boolean get() = platform == 1L || platform == 4L
    }

    internal enum class Verdict {
        /** Our registration is in the list and not disabled. */
        PRESENT,

        /** Our registration is in the list but `enabled` is explicitly false. */
        PRESENT_DISABLED,

        /** The list came back and our registration is not in it. */
        ABSENT,

        /** We could not tell — HTTP failure, unparseable body, or no id to match on. */
        INCONCLUSIVE,
    }

    internal data class ProbeResult(
        val httpCode: Int,
        val verdict: Verdict,
        val ourRegId: String?,
        val ours: Entry?,
        val entries: List<Entry>,
        val note: String? = null,
    )

    /**
     * Run the list-only probe. Blocking; call from a dispatcher that tolerates I/O.
     *
     * [webDeviceUuid] is the persisted UUID behind our `messages-web-<hex>` identity
     * ([GoogleMessagesAccountStore.getOrCreateDeviceSessionId]). [knownOwnRegId], when we
     * have it, is the registration id Google told us was ours (response field 2 of a
     * previous call) — the id the real client matches on. We match on either, and log
     * which one hit, because it is UNKNOWN which of the two Google lists us under and
     * guessing is how this project has gone wrong before.
     */
    fun probe(
        http: OkHttpClient,
        cookies: Map<String, String>,
        webDeviceUuid: String,
        knownOwnRegId: String?,
        reason: String,
    ): ProbeResult {
        val requestId = UUID.randomUUID().toString()
        val deviceIdStr = "messages-web-" + uuidToHex(webDeviceUuid)

        // Same envelope GMGaiaClient builds, with two deliberate differences:
        //   - request field 3 (index 2) = 1  -> LIST ONLY, no token, no registration
        //   - Inner.someData carries no key  -> we are not establishing an identity
        // AuthMessage { requestID=1, network=3, configVersion=7 }
        val authMessage = sparse(
            7,
            0 to PbLite.jsonString(requestId),
            2 to PbLite.jsonString(GDITTO),
            6 to GMSessionProto.CONFIG_VERSION_PBLITE,
        )
        // Inner { deviceID=1 } ; Inner.DeviceID { unknownInt1=1 -> 3, deviceID=2 }
        //
        // Deliberately WITHOUT Inner.someData (field 36, the key wrapper GMGaiaClient
        // sends). Rationale: the wrapper is what a registration stores as its key
        // material, and the real client reads it back out of this very list. Sending one
        // on a probe risks overwriting the key our live pairing depends on — a far worse
        // outcome than the probe failing. So: minimal request first. If Google answers
        // 400 / INVALID_ARGUMENT, that is the signal to add the wrapper (with a
        // throwaway key) and re-test — not to assume it is required.
        val deviceId = sparse(2, 0 to "3", 1 to PbLite.jsonString(deviceIdStr))
        val inner = sparse(1, 0 to deviceId)
        val requestPblite = "[$authMessage,$inner,1,${PbLite.jsonString(GDITTO)}]"

        val req = Request.Builder()
            .url(SIGN_IN_GAIA_URL)
            .post(requestPblite.toRequestBody(CONTENT_TYPE_PBLITE.toMediaType()))
            .header("Cookie", GMCookieAuth.cookieHeader(cookies))
            .header("Authorization", GMCookieAuth.sapisidHash(cookies["SAPISID"].orEmpty()))
            .header("x-goog-api-key", GMPairingProto.GOOGLE_API_KEY)
            .header("x-user-agent", GMPairingProto.X_USER_AGENT)
            .header("user-agent", GMPairingProto.USER_AGENT)
            .header("origin", GMCookieAuth.ORIGIN)
            .header("referer", "https://messages.google.com/")
            .header("sec-fetch-site", "cross-site")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty")
            .build()

        Log.i(
            TAG,
            "probe ($reason): asking Google for the account's registration list — mode=1 " +
                "(list only, no token, no registration, phone not involved)",
        )

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // gRPC NOT_FOUND (code 5) is itself a signal: nothing registered.
                    val notFound = body.contains("\"NOT_FOUND\"") ||
                        Regex("^\\[\\s*5\\s*,").containsMatchIn(body)
                    val tail = if (notFound) {
                        " NOT_FOUND — Google says there is no registration here"
                    } else if (resp.code == 400) {
                        " — if this says INVALID_ARGUMENT, the mode-1 request may need " +
                            "Inner.someData (the key wrapper) after all; see the class kdoc"
                    } else {
                        ""
                    }
                    Log.w(TAG, "probe HTTP ${resp.code}$tail: ${body.take(300)}")
                    return@use ProbeResult(
                        httpCode = resp.code,
                        verdict = if (notFound) Verdict.ABSENT else Verdict.INCONCLUSIVE,
                        ourRegId = knownOwnRegId,
                        ours = null,
                        entries = emptyList(),
                        note = "http ${resp.code}",
                    )
                }
                parse(body, resp.code, deviceIdStr, webDeviceUuid, knownOwnRegId, reason)
            }
        }.getOrElse {
            Log.w(TAG, "probe threw — inconclusive, changing nothing", it)
            ProbeResult(0, Verdict.INCONCLUSIVE, knownOwnRegId, null, emptyList(), it.message)
        }
    }

    private fun parse(
        body: String,
        code: Int,
        deviceIdStr: String,
        webDeviceUuid: String,
        knownOwnRegId: String?,
        reason: String,
    ): ProbeResult {
        val root = PbLite.parse(body)

        // root[1] = response field 2 = OUR OWN registration id, ASCII inside base64.
        val ownFromResponse = root[1].asStringOrNull()?.let { decodeAscii(it) }

        // AccountInfo field 3 (index 2) is the list the real client reads. Field 2
        // (index 1) is the one GMGaiaClient reads for the primary phone. Read BOTH and
        // prefer 3, because they are not documented to be the same and this probe is
        // partly here to find out.
        val accountInfo = root[2]
        val list3 = (accountInfo[2] as? PbLite.Node.Arr)?.items.orEmpty()
        val list2 = (accountInfo[1] as? PbLite.Node.Arr)?.items.orEmpty()
        val chosen = if (list3.isNotEmpty()) list3 else list2
        val which = if (list3.isNotEmpty()) "field3" else "field2"

        val entries = chosen.mapNotNull { item ->
            val id = item[0].asStringOrNull()?.let { decodeAscii(it) } ?: return@mapNotNull null
            val eight = item[7]
            Entry(
                regId = id,
                platform = item[2].asLongOrNull(),
                enabled = asBool(eight[0]),
                timeMs = eight[1].asLongOrNull(),
                lastUpdatedMicros = item[6].asLongOrNull(),
            )
        }

        // Match on either identity, and say which one hit. hyphenated(webDeviceUuid) is
        // the same 16 bytes as the hex in `messages-web-<hex>`, just formatted the way
        // the list formats ids.
        val hyphenated = runCatching { UUID.fromString(webDeviceUuid).toString() }
            .getOrDefault(webDeviceUuid)
        val byOwnId = knownOwnRegId?.let { k -> entries.firstOrNull { it.regId == k } }
            ?: ownFromResponse?.let { k -> entries.firstOrNull { it.regId == k } }
        val byWebUuid = entries.firstOrNull {
            it.regId.equals(hyphenated, ignoreCase = true) ||
                it.regId.equals(deviceIdStr, ignoreCase = true)
        }
        val ours = byOwnId ?: byWebUuid
        val matchedOn = when {
            byOwnId != null -> "own-registration-id"
            byWebUuid != null -> "web-device-uuid"
            else -> "no-match"
        }

        val verdict = when {
            entries.isEmpty() -> Verdict.INCONCLUSIVE
            ours == null -> Verdict.ABSENT
            ours.enabled == false -> Verdict.PRESENT_DISABLED
            else -> Verdict.PRESENT
        }

        val web = entries.count { it.isWebSurface }
        val android = entries.count { it.isAndroidSurface }
        Log.i(
            TAG,
            "probe ($reason) HTTP $code list=$which n=${entries.size} " +
                "(android=$android web=$web) ourRegId=${knownOwnRegId ?: ownFromResponse ?: "unknown"} " +
                "webDeviceUuid=$hyphenated matchedOn=$matchedOn VERDICT=$verdict",
        )
        if (ours != null) {
            Log.i(
                TAG,
                "probe ($reason) OUR ENTRY: id=${ours.regId} platform=${ours.platform} " +
                    "enabled=${ours.enabled} time=${ours.timeMs} " +
                    "lastUpdatedAgo=${agoFromMicros(ours.lastUpdatedMicros)} " +
                    "— lastUpdatedAgo is the OQ-20 readout: if it does not advance while we " +
                    "stream, Google is not counting our traffic as use",
            )
        } else {
            Log.w(
                TAG,
                "probe ($reason) OUR ENTRY IS NOT IN THE LIST — either we are deregistered, or " +
                    "Google lists us under an id we do not hold. Do NOT act on this until the " +
                    "paired-vs-unpaired diff has been captured (doc §28)",
            )
        }
        // The whole list, one line per entry. Registration ids are not credentials and
        // this is the diff Jack needs; it is deliberately verbose and deliberately
        // NOT under the GMGaia tag, which is excluded from the support snapshot because
        // it carries the tachyon token. Nothing secret is printed here.
        entries.forEachIndexed { i, e ->
            val mark = if (e === ours) " <<< US" else ""
            Log.d(
                TAG,
                "  [$i] id=${e.regId} platform=${e.platform} enabled=${e.enabled} " +
                    "time=${e.timeMs} lastUpdatedAgo=${agoFromMicros(e.lastUpdatedMicros)}$mark",
            )
        }
        return ProbeResult(code, verdict, knownOwnRegId ?: ownFromResponse, ours, entries)
    }

    // ---- helpers -----------------------------------------------------------

    /** pblite bools arrive as `true`/`false` OR as `1`/`0`; accept both. */
    private fun asBool(n: PbLite.Node): Boolean? = when (n) {
        is PbLite.Node.Bool -> n.value
        else -> n.asLongOrNull()?.let { it != 0L }
    }

    /** A "pblite_binary" field: base64 whose bytes are ASCII text. */
    private fun decodeAscii(b64: String): String? =
        runCatching { String(B64.decode(b64), Charsets.US_ASCII) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7E } }

    private fun agoFromMicros(micros: Long?): String {
        if (micros == null || micros <= 0L) return "n/a"
        val ms = micros / 1000
        val ago = System.currentTimeMillis() - ms
        if (ago < 0) return "in-the-future(${ms})"
        val mins = ago / 60_000
        return if (mins < 120) "${mins}m" else "${mins / 60}h"
    }

    private fun sparse(size: Int, vararg entries: Pair<Int, String>): String {
        val map = entries.toMap()
        return (0 until size).joinToString(prefix = "[", postfix = "]", separator = ",") {
            map[it] ?: "null"
        }
    }

    /** Lowercase 32-char hex, accepting both Google's dash-free shape and a canonical
     *  dashed UUID. Returns the input unchanged if it is neither — this is a READ-ONLY
     *  probe, so a wrong id yields `matchedOn=no-match` (already handled) rather than
     *  registering anything. It must NOT mint a random UUID the way the pairing path's
     *  copy of this helper used to; see [GMGaiaClient.webDeviceHexOrNull]. */
    private fun uuidToHex(uuidStr: String): String {
        val s = uuidStr.trim()
        if (s.length == 32 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return s.lowercase()
        }
        val u = runCatching { java.util.UUID.fromString(s) }.getOrNull() ?: return s
        val bytes = java.nio.ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val GDITTO = "GDitto"
    private const val CONTENT_TYPE_PBLITE = "application/json+protobuf"
    private const val SIGN_IN_GAIA_URL =
        "https://instantmessaging-pa.clients6.google.com/\$rpc/" +
            "google.internal.communications.instantmessaging.v1.Registration/SignInGaia"
}
