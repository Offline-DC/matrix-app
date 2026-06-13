package com.offline.dpadmessenger.backend.gmessages

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * On-device refresher for Google's rotating session cookie `__Secure-1PSIDTS`
 * (and `__Secure-3PSIDTS`) — what keeps a Google-account login alive long-term.
 *
 * ## Why this exists
 * Google rotates `__Secure-*PSIDTS` roughly every ~30 minutes, and **only one
 * holder of a login may perform the rotation** — whoever rotates it invalidates
 * every other holder's copy. The flip phone gets a copy of the cookies at
 * pairing, but the Messaging relay endpoints only ever return
 * `SIDCC` / `__Secure-*PSIDCC` via `Set-Cookie` — **never** `__Secure-*PSIDTS`
 * (confirmed in field logcat: every `Set-Cookie on …/Messaging/*` line lists
 * `SIDCC, __Secure-1PSIDCC, __Secure-3PSIDCC` and nothing else). So if the
 * user's browser — or a still-open incognito sign-in window — keeps rotating
 * `1PSIDTS`, the phone's saved set goes stale and `RegisterRefresh` starts
 * returning `401 SESSION_COOKIE_INVALID` after ~1–2h → a needless re-pair.
 *
 * The durable fix is to do what a real browser does: periodically hit Google's
 * cookie-rotation endpoint ourselves, so the **phone** becomes a rotating holder
 * and keeps its `1PSIDTS` fresh independently of any browser.
 *
 * ## Wire format (REVERSE-ENGINEERED — not a documented Google API)
 * Two steps, mirroring what `accounts.google.com` does in-page:
 *
 *  1. `GET https://accounts.google.com/RotateCookiesPage`
 *        `?og_pid=<pid>&rot=1&origin=https://messages.google.com&exp_id=0`
 *      → HTML containing an `init('<initValue>', …)` bootstrap call. `initValue`
 *        is a signed-integer string that seeds the rotation.
 *  2. `POST https://accounts.google.com/RotateCookies`
 *      - body    = JSON array `[og_pid, "<initValue>"]`
 *      - headers = `Cookie` (current cookies), `Content-Type: application/json`,
 *                  `Referer:` the RotateCookiesPage URL
 *      → `200` with `Set-Cookie: __Secure-1PSIDTS=<new>; …` (+ `3PSIDTS`).
 *
 * Shape from community reverse-engineering of the same endpoint the Bard/Gemini
 * web client uses to keep `1PSIDTS` alive
 * (gist.github.com/szv99/f78c032736443fab51075bc45f9faf09).
 *
 * ## ⚠️ NEEDS ON-DEVICE VALIDATION
 * This cannot be unit-tested against Google. Verify with:
 * ```
 * adb logcat -s GMRotate:V GMSession:*
 * ```
 * Expect `rotate OK — refreshed __Secure-1PSIDTS, …` roughly every interval, and
 * the session surviving well past the old ~2h cliff. Failure modes:
 *  - `no init value in RotateCookiesPage` → the page shape changed; re-scrape
 *    [INIT_VALUE_RE] from a fresh capture.
 *  - `RotateCookies HTTP 4xx` → capture a real Chrome `RotateCookies` request
 *    from a logged-in messages.google.com session and reconcile body/params
 *    (notably [OG_PID], which is `0` for the only/primary account but can differ
 *    for multi-account cookie jars).
 */
internal class GMCookieRotator(private val http: OkHttpClient) {

    /**
     * Attempt one rotation. [cookies] is the current cookie set (must include
     * `__Secure-1PSID`). @return the cookie name→value pairs that **changed**
     * (to merge into the live set and persist), or `null` if rotation failed or
     * nothing changed.
     *
     * Blocking — call from an IO dispatcher.
     */
    fun rotate(cookies: Map<String, String>): Map<String, String>? {
        if (cookies["__Secure-1PSID"].isNullOrBlank()) {
            Log.w(TAG, "skip rotate: no __Secure-1PSID in cookie set")
            return null
        }
        val cookieHeader = GMCookieAuth.cookieHeader(cookies)
        val pageUrl = "$BASE/RotateCookiesPage?og_pid=$OG_PID&rot=1&origin=$ORIGIN&exp_id=0"

        // Step 1 — fetch the bootstrap page and extract the init value.
        val initValue = runCatching {
            val pageReq = Request.Builder()
                .url(pageUrl).get()
                .header("Cookie", cookieHeader)
                .header("Referer", "$ORIGIN/")
                .header("User-Agent", GMPairingProto.USER_AGENT)
                .build()
            http.newCall(pageReq).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "RotateCookiesPage HTTP ${resp.code}"); return@use null }
                INIT_VALUE_RE.find(resp.body?.string().orEmpty())?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
        if (initValue.isNullOrBlank()) {
            Log.w(TAG, "no init value in RotateCookiesPage — page shape may have changed")
            return null
        }

        // Step 2 — POST the rotation; read the new *SIDTS cookies from Set-Cookie.
        return runCatching {
            val payload = "[$OG_PID,\"$initValue\"]"
            val postReq = Request.Builder()
                .url("$BASE/RotateCookies")
                .post(payload.toRequestBody(JSON))
                .header("Cookie", cookieHeader)
                .header("Referer", pageUrl)
                .header("Origin", ORIGIN)
                .header("User-Agent", GMPairingProto.USER_AGENT)
                .build()
            http.newCall(postReq).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "RotateCookies HTTP ${resp.code}"); return@use null }
                val rotated = HashMap<String, String>()
                for (sc in resp.headers("Set-Cookie")) {
                    val nameValue = sc.substringBefore(';')
                    val eq = nameValue.indexOf('=')
                    if (eq <= 0) continue
                    val name = nameValue.substring(0, eq).trim()
                    val value = nameValue.substring(eq + 1).trim()
                    if (name.isEmpty() || value.isEmpty() || value.equals("EXPIRED", ignoreCase = true)) continue
                    if (cookies[name] != value) rotated[name] = value
                }
                when {
                    rotated.isEmpty() -> { Log.w(TAG, "RotateCookies 200 but no changed cookies"); null }
                    else -> { Log.i(TAG, "rotate OK — refreshed ${rotated.keys.joinToString()}"); rotated }
                }
            }
        }.getOrElse { Log.w(TAG, "rotate failed", it); null }
    }

    companion object {
        private const val TAG = "GMRotate"
        private const val BASE = "https://accounts.google.com"
        private const val ORIGIN = "https://messages.google.com"
        /** Account ordinal in the cookie jar; `0` for the only/primary account. */
        private const val OG_PID = 0
        private val JSON = "application/json".toMediaType()
        private val INIT_VALUE_RE = Regex("""init\(\s*'(-?\d+)'""")
    }
}
