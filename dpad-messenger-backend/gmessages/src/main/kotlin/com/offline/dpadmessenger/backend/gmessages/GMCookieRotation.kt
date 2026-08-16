package com.offline.dpadmessenger.backend.gmessages

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the rotating Google session cookie (`__Secure-1PSIDTS`) fresh.
 *
 * WHY THIS EXISTS
 * ---------------
 * `__Secure-1PSID` is the long-lived login; `__Secure-1PSIDTS` is its short-lived
 * "freshness" partner. Google rotates 1PSIDTS on its own cadence (it tells us the
 * interval — empirically 600s). A browser does this automatically, which is why a
 * logged-in tab never dies. Our harvest is a SNAPSHOT: the flip phone holds a
 * 1PSIDTS that nobody refreshes, because the messaging endpoints only ever re-issue
 * the *SIDCC family — never *SIDTS. (Confirmed across 82k lines of field logcat:
 * every `Set-Cookie` on ReceiveMessages / SendMessage / AckMessages / RegisterRefresh
 * is exactly `SIDCC, __Secure-1PSIDCC, __Secure-3PSIDCC`.) So the snapshot goes
 * stale and the link dies — the "~2h SESSION_COOKIE_INVALID kick".
 *
 * The previous attempt at this was to DELETE 1PSIDTS and ride 1PSID alone
 * (GoogleMessagesAccountStore, 2026-06-13). That worked only while Google still
 * honoured the 1PSID-only fallback, and on 2026-08-14 it started failing closed:
 * pairing itself was refused with HTTP 401 SESSION_COOKIE_INVALID. Keeping the
 * cookie and refreshing it is the only direction that can work.
 *
 * STATUS: EXPERIMENT — DEFAULT OFF
 * --------------------------------
 * Upstream mautrix-gmessages does NOT do this. It has no rotation logic at all
 * (`AuthData.UpdateCookiesFromResponse` is its entire cookie story) and instead
 * tells users to sign in from a private window so the browser can't rotate the
 * cookies out from under the bridge. This endpoint is therefore UNVERIFIED against
 * `instantmessaging-pa`; it is well attested elsewhere in the Google-web
 * reverse-engineering ecosystem (NotebookLM / Gemini web clients) but not here.
 *
 * So it ships behind [GoogleMessagesConfig.cookieRotationEnabled], default false.
 * Turn it on for one device, watch whether the link survives past ~2h, and only
 * then consider defaulting it on. Every failure path here is non-destructive: we
 * never clear or downgrade a cookie we already hold.
 *
 * CONCURRENCY: exactly one rotation may be in flight process-wide. Rotation
 * INVALIDATES the previous 1PSIDTS, so two racing rotations can leave both callers
 * holding a dead value; downstream projects hit precisely this and had to add
 * two-layer locking. [inFlight] is that lock. It also means the harvest source
 * must be a throwaway private window that is CLOSED after sign-in — if the user's
 * browser still holds the session and keeps rotating, it and the phone will
 * invalidate each other's token. That guidance already matches what the login
 * extensions do (they harvest from an incognito/private store).
 */
object GMCookieRotation {

    private const val TAG = "GMCookieRot"

    /** Undocumented but stable; the same endpoint the browser's own keepalive uses. */
    private const val ROTATE_URL = "https://accounts.google.com/RotateCookies"

    /** jspb sentinel meaning "no prior rotation id" — what the web client sends on
     *  a cold poke. Google answers with the real next-interval regardless. */
    private const val BODY = "[000,\"-0000000000000000000\"]"

    /** Never poke more often than this, whatever else says otherwise. Guards against
     *  a restart loop hammering accounts.google.com into a 429. */
    private const val MIN_INTERVAL_MS = 60_000L

    /** Used when Google's response doesn't carry an interval. Its own hint is 600s. */
    private const val DEFAULT_INTERVAL_MS = 600_000L

    private const val FAILURE_BACKOFF_MS = 900_000L
    private const val RATE_LIMIT_BACKOFF_MS = 1_800_000L

    /** Cookies we will accept from a rotation response. Deliberately NOT the whole
     *  Set-Cookie set: this is an accounts.google.com call and we only want the two
     *  freshness cookies from it. Anything else stays owned by the messaging
     *  endpoints, so a surprise from this host can't rewrite the live session. */
    private val ACCEPT = setOf("__Secure-1PSIDTS", "__Secure-3PSIDTS")

    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastAttemptMs = 0L
    @Volatile private var nextDueMs = 0L

    /** Support-facing one-liner: what the last rotation did. Surfaced in the
     *  "alive" heartbeat so a capture shows whether rotation is running at all. */
    @Volatile
    var lastResult: String = "never-run"
        private set

    /** Call on sign-in / re-link so a new session isn't held back by an old backoff. */
    fun reset() {
        lastAttemptMs = 0L
        nextDueMs = 0L
        lastResult = "never-run"
    }

    fun status(): String =
        if (!GoogleMessagesConfig.cookieRotationEnabled) "disabled"
        else "last=$lastResult nextDueIn=${((nextDueMs - System.currentTimeMillis()) / 1000L)}s"

    /**
     * Rotate if enabled, due, and we actually hold a 1PSIDTS to refresh.
     *
     * Mutates [cookies] in place on success. Blocking — call on IO.
     *
     * @return true if a cookie value changed and the caller should persist.
     */
    fun rotateIfDue(http: OkHttpClient, cookies: MutableMap<String, String>): Boolean {
        if (!GoogleMessagesConfig.cookieRotationEnabled) return false
        // Nothing to refresh. Note this is also why the old strip made this fix
        // impossible: with 1PSIDTS deleted there is no rotation to perform.
        if (cookies["__Secure-1PSIDTS"].isNullOrBlank()) return false

        val now = System.currentTimeMillis()
        if (now < nextDueMs) return false
        if (lastAttemptMs != 0L && now - lastAttemptMs < MIN_INTERVAL_MS) return false
        // Single-flight: a racing caller skips rather than queues.
        if (!inFlight.compareAndSet(false, true)) return false

        return try {
            lastAttemptMs = now
            rotate(http, cookies)
        } catch (t: Throwable) {
            // Transport failure says nothing about the credentials. Keep what we
            // have and try again later — never clear a cookie on a network error.
            Log.w(TAG, "rotate threw — keeping existing cookies", t)
            lastResult = "threw:${t.javaClass.simpleName}"
            nextDueMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            false
        } finally {
            inFlight.set(false)
        }
    }

    private fun rotate(http: OkHttpClient, cookies: MutableMap<String, String>): Boolean {
        val req = Request.Builder()
            .url(ROTATE_URL)
            .post(BODY.toRequestBody("application/json".toMediaType()))
            .header("Cookie", GMCookieAuth.cookieHeader(cookies))
            .header("Origin", "https://accounts.google.com")
            .header("Referer", "https://accounts.google.com/")
            .header("user-agent", GMPairingProto.USER_AGENT)
            .build()

        return http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()

            if (resp.code == 429) {
                Log.w(TAG, "rotate rate-limited (429) — backing off ${RATE_LIMIT_BACKOFF_MS / 60_000}min")
                lastResult = "429"
                nextDueMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                return false
            }
            if (!resp.isSuccessful) {
                // A 401 here means the whole Google session is gone, not just the
                // freshness cookie — but that is the long-poll's call to make, not
                // ours. We only report it; we do not touch stored state.
                Log.w(TAG, "rotate HTTP ${resp.code} (${body.length}B) — cookies untouched: ${body.take(160)}")
                lastResult = "http${resp.code}"
                nextDueMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                return false
            }

            val rotated = extractRotatedCookies(resp.headers("Set-Cookie"))
            var changed = false
            for ((name, value) in rotated) {
                if (cookies[name] != value) {
                    cookies[name] = value
                    changed = true
                }
            }

            val nextMs = (parseNextIntervalMs(body) ?: DEFAULT_INTERVAL_MS)
                .coerceAtLeast(MIN_INTERVAL_MS)
            nextDueMs = System.currentTimeMillis() + nextMs

            lastResult = if (changed) "rotated" else "no-change"
            Log.i(
                TAG,
                "rotate OK: accepted=${rotated.keys} changed=$changed nextIn=${nextMs / 1000}s",
            )
            changed
        }
    }

    // ---- Pure helpers -------------------------------------------------------
    // No Android, no network: this module's unit tests run on plain JVM (see
    // GMCookieAuth's note), and the response parsing is the part most likely to
    // be wrong, so it lives here where a test can reach it.

    /**
     * Google's stated next-rotation interval, in ms, or null if absent.
     *
     * The body is XSSI-guarded pblite, e.g.
     * ```
     * )]}'
     * [["identity.hfcr",600],["di",1234567]]
     * ```
     */
    internal fun parseNextIntervalMs(body: String): Long? =
        Regex("\"identity\\.hfcr\"\\s*,\\s*(\\d+)")
            .find(body)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)

    /**
     * The cookies we are willing to take from a RotateCookies response.
     *
     * Restricted to [ACCEPT] so this accounts.google.com call can never rewrite
     * the live messaging session. Drops cleared/EXPIRED values, and anything
     * OkHttp would reject as a header value — persisting one of those would wedge
     * every later request, including after a reboot.
     */
    internal fun extractRotatedCookies(setCookieHeaders: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (sc in setCookieHeaders) {
            val nameValue = sc.substringBefore(';')
            val eq = nameValue.indexOf('=')
            if (eq <= 0) continue
            val name = nameValue.substring(0, eq).trim()
            if (name !in ACCEPT) continue
            val value = nameValue.substring(eq + 1).trim()
            if (value.isEmpty() || value.equals("EXPIRED", ignoreCase = true)) continue
            if (!value.all { it == '\t' || (it.code in 0x20..0x7e) }) continue
            out[name] = value
        }
        return out
    }
}
