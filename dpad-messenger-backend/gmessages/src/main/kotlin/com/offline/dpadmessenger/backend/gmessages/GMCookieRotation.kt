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
 * (GoogleMessagesAccountStore, 2026-06-13). On 2026-08-14, three of four pairing
 * attempts on that stripped cookie set were refused with HTTP 401
 * SESSION_COOKIE_INVALID.
 *
 * Resist the tempting version of that sentence. It is NOT established that Google
 * changed anything on 2026-08-14, and an earlier draft of this comment said so.
 * `signInGaia` appears in exactly ONE of the sixteen field captures (12 Jun – 14 Aug)
 * — the 14 Aug one — so there is no before/after: we have a single day of pairing
 * data, and it happens to be the last capture in the set. "The date we have data for"
 * is not "the date something changed". The 200 sitting among those four 401s argues
 * against a policy flip too; a hard change would not intermittently succeed.
 *
 * What the data does support, and all this design needs: riding a stripped set is
 * MARGINAL — accepted sometimes, refused most of the time, on byte-identical input.
 * Keeping the cookie and refreshing it removes the coin-flip.
 *
 * STATUS: VERIFIED ON DEVICE — DEFAULT ON
 * ---------------------------------------
 * Upstream mautrix-gmessages does NOT do this. It has no rotation logic at all
 * (`AuthData.UpdateCookiesFromResponse` is its entire cookie story) and instead
 * tells users to sign in from a private window so the browser can't rotate the
 * cookies out from under the bridge. We go further because that advice does not fit a
 * phone: there is no browser left to keep the session warm after the QR is scanned.
 *
 * Verified 2026-08-17 on `jacknugent27@gmail.com`: `POST accounts.google.com/
 * RotateCookies` returns `200` + `[["identity.hfcr",600],["di",N]]` and
 * `Set-Cookie` for BOTH `__Secure-1PSIDTS` and `__Secure-3PSIDTS`, including for a
 * caller that holds neither (the mint / bootstrap case). It also MINTS, not just
 * refreshes — so a 14-cookie harvest with no freshness pair is enough.
 *
 * The one hard requirement is the User-Agent: this endpoint returns `403` +
 * `[["identity.hfcr",2147483647]]` to a client claiming `(Linux; Android 14)`, and
 * `200` to the byte-identical request sent with a desktop platform token. Hence
 * [GMPairingProto.WEB_USER_AGENT] — do NOT reuse [GMPairingProto.USER_AGENT] here.
 *
 * It ships behind [GoogleMessagesConfig.cookieRotationEnabled], default ON. That is
 * safe because a rotation only fires for a device that already holds a
 * `__Secure-1PSIDTS`; the bootstrap path for devices that hold none is separately
 * gated behind [GoogleMessagesConfig.cookieBootstrapOnRecovery]. Every failure path here
 * is non-destructive: we never clear or downgrade a cookie we already hold.
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

    /** Never poke more often than this, whatever else says otherwise.
     *
     *  CAVEAT — this does NOT currently guard against a restart loop, which is what
     *  it was written for. [lastAttemptMs] and [nextDueMs] live in process memory, so
     *  a fresh process starts with `nextDueMs = 0` and rotates unconditionally on the
     *  first maintenance tick however recently the last one ran. Confirmed 17 Aug
     *  2026: a rotation at 10:47:29 parked the next one at 10:57:29, the process was
     *  killed at 10:54:36, and the replacement rotated at 10:54:57 — 152s early. On a
     *  device where the launcher is OOM-killed repeatedly this becomes one rotation
     *  per restart with no floor, against an endpoint that is known to rate-limit,
     *  and every rotation invalidates the previous `__Secure-1PSIDTS`.
     *
     *  Fix is to persist both timestamps alongside the cookies. Not done yet. */
    private const val MIN_INTERVAL_MS = 60_000L

    /** Used when Google's response doesn't carry an interval. Its own hint is 600s. */
    private const val DEFAULT_INTERVAL_MS = 600_000L

    /** Upper clamp on anything Google hands back. Without it the NEVER_ROTATE sentinel
     *  below would push the next attempt ~68 years out and silently kill rotation for
     *  the life of the process while lastResult still read "no-change". */
    private const val MAX_INTERVAL_MS = 24 * 3600_000L

    /** `["identity.hfcr",2147483647]` — Int.MAX_VALUE, i.e. "no rotation scheduled".
     *
     *  This was first read as "the session is NOT ENROLLED in cookie rotation and
     *  never will be". That was WRONG and the retraction matters, because it is the
     *  kind of mistake that makes you stop looking: the sentinel is what Google
     *  returns on a request it is refusing for some OTHER reason — here, our Android
     *  User-Agent — not a verdict about the account. The byte-identical request sent
     *  with [GMPairingProto.WEB_USER_AGENT] returns 200 + `hfcr=600` and mints both
     *  freshness cookies. Both observed 17 Aug 2026 on jacknugent27@gmail.com.
     *
     *  So treat it as "we asked wrongly", never as "this session cannot rotate", and
     *  do NOT latch on it. */
    private const val NEVER_ROTATE = 2_147_483_647L

    /**
     * Backoff after a failed rotation. Deliberately SHORTER than the ~600s rotation
     * cadence: at the old 900s a single failure guaranteed the freshness cookie went
     * stale before the next attempt, which is the ~2h death. Two minutes is long
     * enough not to hammer Google and short enough to stay inside the window.
     */
    private const val FAILURE_BACKOFF_MS = 120_000L
    private const val RATE_LIMIT_BACKOFF_MS = 1_800_000L

    /** Cookies we will accept from a rotation response. Deliberately NOT the whole
     *  Set-Cookie set: this is an accounts.google.com call and we only want the two
     *  freshness cookies from it. Anything else stays owned by the messaging
     *  endpoints, so a surprise from this host can't rewrite the live session. */
    private val ACCEPT = setOf("__Secure-1PSIDTS", "__Secure-3PSIDTS")

    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastAttemptMs = 0L
    @Volatile private var nextDueMs = 0L

    /**
     * Where [lastAttemptMs] and [nextDueMs] survive process death.
     *
     * Without this the two timestamps are process memory, so every fresh process starts
     * at `nextDueMs = 0` and rotates unconditionally on its first maintenance tick —
     * and [MIN_INTERVAL_MS] cannot do the job its own comment claims, because the floor
     * dies with the process too. Measured 17 Aug 2026: a rotation at 10:47:29 parked the
     * next at 10:57:29, the process was killed at 10:54:36, and its replacement rotated
     * at 10:54:57, 152s early. Once per restart is harmless; a device being OOM-killed
     * and restarted repeatedly turns it into a request loop against an endpoint upstream
     * documents as rate-limited, and every rotation invalidates the previous
     * `__Secure-1PSIDTS`, so the loop also churns the credential it is meant to protect.
     *
     * Upstream (notebooklm-py) uses a file mtime for exactly this floor, i.e. a
     * PERSISTENT check, which is the tell that in-memory is not enough.
     *
     * An interface rather than a direct store dependency so this object stays
     * Android-free and unit-testable, and so a caller that has no store (link-time
     * bootstrap, which has just called [reset] anyway) can simply not attach one.
     */
    interface Timestamps {
        /** `[lastAttemptMs, nextDueMs]`, or nulls/zeros if nothing is stored yet. */
        fun load(): LongArray
        fun save(lastAttemptMs: Long, nextDueMs: Long)
    }

    @Volatile private var timestamps: Timestamps? = null
    @Volatile private var hydrated = false

    /** Install (or with `null`, remove) persistence. Idempotent; re-arms the one-time
     *  load. Nullable so a caller with no store — and a test — can prove the
     *  degrade-to-memory path rather than assuming it. */
    fun attachTimestamps(store: Timestamps?) {
        timestamps = store
        hydrated = false
    }

    /** Read the persisted floor once per process, before the first gate check. Failure
     *  is non-fatal: we fall back to in-memory behaviour rather than blocking rotation. */
    private fun hydrate() {
        if (hydrated) return
        hydrated = true
        val t = timestamps ?: return
        runCatching { t.load() }.getOrNull()?.let { v ->
            if (v.size >= 2) {
                // Only ever move the floor FORWARD. A stored value is evidence that a
                // rotation happened; a zero is absence of evidence, not permission.
                if (v[0] > lastAttemptMs) lastAttemptMs = v[0]
                if (v[1] > nextDueMs) nextDueMs = v[1]
                Log.i(
                    TAG,
                    "hydrated rotation floor from disk: lastAttempt=${
                        if (v[0] == 0L) "never" else "${(System.currentTimeMillis() - v[0]) / 1000}s ago"
                    } nextDueIn=${(nextDueMs - System.currentTimeMillis()) / 1000}s",
                )
            }
        }
    }

    private fun persist() {
        val t = timestamps ?: return
        runCatching { t.save(lastAttemptMs, nextDueMs) }
            .onFailure { Log.w(TAG, "could not persist rotation floor (continuing)", it) }
    }

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
        // Clear the persisted floor as well, or a fresh sign-in inherits the old
        // session's backoff from disk — the exact bug reset() exists to prevent, just
        // surviving longer. hydrate() is re-armed so a later attach still works.
        hydrated = true
        persist()
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
        hydrate()
        // A rotation is authenticated by the long-lived login cookie. Without it there
        // is nothing to rotate against and no request worth making.
        if (cookies["__Secure-1PSID"].isNullOrBlank()) return false

        // REFRESH ONLY on the healthy path. A session holding no __Secure-1PSIDTS needs
        // no maintenance at all and survives days-to-weeks untended, so minting one here
        // would trade a stable state for one that must keep rotating or die at ~2h — and
        // a stale freshness cookie is unrecoverable without a re-link, whereas an absent
        // one cannot go stale. Minting is a RECOVERY action instead: see [bootstrapNow].
        if (cookies["__Secure-1PSIDTS"].isNullOrBlank()) return false

        val now = System.currentTimeMillis()
        if (now < nextDueMs) return false
        if (lastAttemptMs != 0L && now - lastAttemptMs < MIN_INTERVAL_MS) return false
        return attempt(http, cookies, bootstrap = false, now = now)
    }

    /**
     * Recovery entry point: MINT a `__Secure-1PSIDTS` for a session that holds none.
     *
     * Only call this when auth has already failed — from [GoogleMessagesSessionClient
     * .reauth], i.e. the reconnect prompt, "Re-link phone", and Settings →
     * "Re-register now". At that point there is no healthy state left to protect, so a
     * bootstrap can only help: it either rescues the link or changes nothing.
     *
     * Verified 17 Aug 2026: from a 14-cookie harvest with no freshness pair, this
     * returns `200 [["identity.hfcr",600]]` and Google sets BOTH
     * `__Secure-1PSIDTS` and `__Secure-3PSIDTS` — but ONLY with a desktop
     * [GMPairingProto.WEB_USER_AGENT]; the Android string gets a flat 403.
     *
     * @return true if a cookie value changed and the caller should persist.
     */
    fun bootstrapNow(http: OkHttpClient, cookies: MutableMap<String, String>): Boolean {
        if (!GoogleMessagesConfig.cookieBootstrapOnRecovery) return false
        if (cookies["__Secure-1PSID"].isNullOrBlank()) return false
        hydrate()

        val now = System.currentTimeMillis()
        // Deliberately ignores nextDueMs: a backoff parked by the healthy path is
        // irrelevant once auth is already broken. The MIN_INTERVAL floor still applies,
        // so a retry loop (or a user leaning on the button) cannot hammer Google.
        if (lastAttemptMs != 0L && now - lastAttemptMs < MIN_INTERVAL_MS) {
            Log.i(
                TAG,
                "bootstrapNow: skipped — last attempt ${(now - lastAttemptMs) / 1000}s ago " +
                    "(floor ${MIN_INTERVAL_MS / 1000}s)",
            )
            return false
        }
        val bootstrap = cookies["__Secure-1PSIDTS"].isNullOrBlank()
        Log.i(TAG, "bootstrapNow: requested (mint=$bootstrap cookies=${cookies.size})")
        return attempt(http, cookies, bootstrap = bootstrap, now = now)
    }

    /** Single-flight + non-destructive error handling, shared by both entry points. */
    private fun attempt(
        http: OkHttpClient,
        cookies: MutableMap<String, String>,
        bootstrap: Boolean,
        now: Long,
    ): Boolean {
        // A racing caller skips rather than queues: rotation INVALIDATES the previous
        // 1PSIDTS, so two racers would leave both holding a stale value.
        if (!inFlight.compareAndSet(false, true)) return false
        return try {
            lastAttemptMs = now
            rotate(http, cookies, bootstrap)
        } catch (t: Throwable) {
            // Transport failure says nothing about the credentials. Keep what we
            // have and try again later — never clear a cookie on a network error.
            Log.w(TAG, "rotate threw — keeping existing cookies", t)
            lastResult = "${if (bootstrap) "bootstrap-" else ""}threw:${t.javaClass.simpleName}"
            nextDueMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            false
        } finally {
            // Every mutation of lastAttemptMs / nextDueMs happens inside this call, so
            // one write here covers all of them — success, 429, HTTP error and throw.
            persist()
            inFlight.set(false)
        }
    }

    private fun rotate(
        http: OkHttpClient,
        cookies: MutableMap<String, String>,
        bootstrap: Boolean,
    ): Boolean {
        val req = Request.Builder()
            .url(ROTATE_URL)
            .post(BODY.toRequestBody("application/json".toMediaType()))
            .header("Cookie", GMCookieAuth.cookieHeader(cookies))
            .header("Origin", "https://accounts.google.com")
            .header("Referer", "https://accounts.google.com/")
            .header("user-agent", GMPairingProto.WEB_USER_AGENT)
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
                Log.w(
                    TAG,
                    "rotate${if (bootstrap) " BOOTSTRAP" else ""} HTTP ${resp.code} " +
                        "(${body.length}B) — cookies untouched: ${body.take(300)}",
                )
                Log.w(
                    TAG,
                    "rotate failure detail: content-type=${resp.header("content-type")} " +
                        "setCookie=${resp.headers("Set-Cookie").map { it.substringBefore("=") }} " +
                        "ua=web cookies=${cookies.size}",
                )
                if (parseNextIntervalMs(body) == NEVER_ROTATE * 1000L) {
                    // hfcr=Int.MAX_VALUE, "never rotate". Do NOT read this as a property
                    // of the account: on 17 Aug 2026 the identical cookie state returned
                    // 403+never for the Android user-agent and 200+600 for a desktop one.
                    // It means "no rotation for THIS request" — i.e. we asked wrongly.
                    Log.w(
                        TAG,
                        "rotate refused with hfcr=never — this is a REQUEST problem, not an " +
                            "account one; check the user-agent and the cookie set",
                    )
                }
                lastResult = "${if (bootstrap) "bootstrap-" else ""}http${resp.code}"
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

            val nextMs = nextIntervalMsFrom(body)
            nextDueMs = System.currentTimeMillis() + nextMs

            lastResult = when {
                bootstrap && changed -> "bootstrapped"
                bootstrap -> "bootstrap-empty"
                changed -> "rotated"
                else -> "no-change"
            }
            // On a bootstrap run `accepted` IS the answer: a non-empty set means Google
            // will mint a freshness cookie for a caller that had none, so the phone stops
            // depending on the harvest carrying one. An empty set means it will not.
            Log.i(
                TAG,
                "rotate${if (bootstrap) " BOOTSTRAP" else ""} OK: accepted=${rotated.keys} " +
                    "changed=$changed nextIn=${nextMs / 1000}s result=$lastResult",
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
    /**
     * How long to wait before the next rotation, given a 200 response body.
     *
     * Extracted from [rotate] purely so it can be unit-tested: the interesting inputs
     * are ones we cannot provoke on a device. In particular `hfcr = 2147483647`
     * ([NEVER_ROTATE]) would otherwise become 2_147_483_647_000 ms and park the next
     * attempt ~68 YEARS out while [lastResult] still read a healthy "no-change" — a
     * dead rotator that logs as fine, which is the worst shape a bug can have here.
     * We never hit it in the field only because the sentinel arrived alongside a 403,
     * which takes the failure-backoff path instead.
     *
     * Clamped at BOTH ends. The floor stops a hostile or garbled small value turning
     * into a request loop; the ceiling ([MAX_INTERVAL_MS], 24h) stops any large value
     * — sentinel, typo or future protocol change — from silently disabling rotation.
     */
    internal fun nextIntervalMsFrom(body: String): Long =
        (parseNextIntervalMs(body) ?: DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

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
