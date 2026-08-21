# Google Messages on the flip phone — project brief & handoff

**Written:** 19 August 2026, end of day
**Author:** working session with Jack Nugent (dumb.co / Offline Inc)
**Purpose:** single-document handoff so a fresh session can pick up the Google Messages work without re-deriving anything
**Status of the code described here:** merged to `beta`, compiles, running on Jack's device as `v6.12.0-beta.3`. Not yet in a customer's hands.

---

## 0. How to use this document

This is written for a reader who has never seen the codebase. It is deliberately long and deliberately explicit about the difference between three kinds of statement:

| Marker | Meaning |
|---|---|
| **MEASURED** | Observed directly in a device log or a test run. A timestamp or a count is cited. Trust it. |
| **INFERRED** | A conclusion drawn from measured facts. Reasonable, but it is a conclusion, not an observation. |
| **UNKNOWN** | We do not know. Listed explicitly so nobody re-derives a guess and writes it down as fact. |

That discipline is not stylistic. The single most expensive class of mistake in this project has been **a plausible hypothesis written into a code comment as though it were established**, which then sends the next person down a dead end. Two such claims were removed from the codebase today (§11.4). If you add a claim to this document or to a comment, mark which of the three it is.

**Reading order if you are short on time:** §2 (state of play) → §9 and §10 (the two bugs, because everything else is downstream of them) → §12 (how to read a log) → §14 (open questions) → §20 (what to do first).

---

## 1. Product and platform context

### 1.1 What the feature is

Offline Inc sells a **dumb phone** — a flip phone (TCL hardware, Android underneath, MediaTek SoC) running a custom launcher (`com.offlineinc.dumbdownlauncher`) that replaces the entire Android experience with a D-pad-driven, deliberately-limited interface. No app store, no browser, no infinite scroll.

Customers still need SMS/RCS with the rest of the world. The product answer is **"Smart Txt"**: the flip phone acts as a companion to the customer's *existing* smartphone's Google Messages account, in the same way `messages.google.com` in a desktop browser does. Texts sent to the customer's real number appear on the flip phone; replies go out through the real number.

There is no official API for this. The implementation is a **reverse-engineered port of `mautrix-gmessages`** (Go → Kotlin), specifically its GAIA (Google-account) pairing mode rather than its QR mode.

### 1.2 Why this is hard

- **No supported API.** Everything is a Google-internal endpoint with a protobuf-over-JSON encoding, discovered by reading `mautrix-gmessages` and by observation. Google can change it without notice and owes us nothing.
- **Credentials are browser cookies.** The flip phone authenticates as if it were a signed-in Chrome tab. Those cookies are short-lived and must be actively refreshed forever (§5).
- **The transport is a long-lived HTTP stream** that Google can silently drop, and did (§10).
- **The device is weak.** ~13 MB heap pressure in the logs, `Skipped 200 frames` on cold start, `[ANR Warning] onMeasure time too long`. Anything expensive is felt.
- **Telemetry is one channel only.** There is no crash reporter and no metrics pipeline. The *only* signal is a rolling logcat the customer manually submits through a support flow. Everything about how the code logs is designed around that constraint (§12).
- **The user cannot debug.** A flip-phone customer will not read an error, will not check a device list, and will not know what a cookie is. A failure that is not self-healing or self-explaining is a support ticket and a refund risk.

### 1.3 Security constraints that are non-negotiable

Stated by Jack at the outset and still in force:

- **Cookie values are live session credentials.** The rolling logcat gets emailed by customers to support. Therefore: log cookie **names** and **hashed fingerprints** only. Never values. `valueFp()` in `GoogleMessagesSessionClient` exists for this.
- **`GMGaia`-tagged logs contain the tachyon auth token** and are excluded from the hourly snapshot that goes into the support bundle.
- Test accounts: `jacknugent27@gmail.com` (consumer), `jack@offline.community` (Google Workspace).

---

## 2. State of play (as of end of 19 Aug 2026)

### 2.1 What just happened

Two distinct, independent bugs were diagnosed from customer logs and fixed:

- **Bug A — link-time freshness (§9).** Pairing could declare `GAIA PAIRING COMPLETE` on a session that held no `__Secure-1PSIDTS`. Such a link is dead on arrival; it dies within minutes to hours with no explanation. **Fixed:** pairing now refuses to complete without the cookie, mints it earlier in the flow, retries once on a rate limit, and fails immediately (rather than after a 70-second wait) on an outright rejection.
- **Bug B — receive-stream wedge (§10).** The long-poll that receives inbound messages had no read deadline. When Google's stream went silent, the phone waited on it forever. Sending kept working, auth stayed perfect, and the user's symptom was "I'm synced, I can send, I don't receive." **Fixed:** a 5-minute read deadline, a 10-minute watchdog that tears down and relaunches the stream, and — critically — the health heartbeat moved off the thread that gets stuck, so the failure is now visible in a log instead of manifesting as silence.

### 2.2 Confidence levels, honestly

| Claim | Confidence | Basis |
|---|---|---|
| Bug A's failure mode is real and was shipping | **Certain** | Alex's log, 06:41:14, `bootstrap-empty` immediately followed by `result=PAIRED` |
| Bug A can no longer ship a broken link | **High** | Gate is on cookie presence, not on a return value; 18 unit tests incl. a negative control; validated across 6+ device pairings on 2 accounts |
| Bug B's failure mode is real | **Certain** | Alex's log, stream #14 opened 11:24:27 and produced nothing for 1h55m while rotation and sends continued |
| Bug B is an anomaly, not universal | **High** | 262 healthy stream cycles across 2 other customers, zero wedges (§13) |
| Bug B's *fix* works | **UNVERIFIED** | Nothing has wedged since the fix, so neither the deadline nor the watchdog has ever fired in the wild |
| The 5-minute deadline is correctly sized | **Low** | Derived from screen-on keepalive measurement only. Behaviour under Android Doze is unmeasured (§14.2) |
| We know *why* either bug happens | **No** | Both fixes are containment, not root-cause. See §14.1 and §14.3 |

### 2.3 One-line summary

*The phone used to lie about being connected and used to die silently. Now it either works, heals itself within minutes, or says out loud what went wrong. We still do not know why Google does the two things that trigger either path.*

### 2.4 Deployment state

- Fixes are on branch `beta` (merge of `feature/logging-for-google-messages-cookie` completed 19 Aug, one conflict resolved — §11.5).
- Jack's device: `v6.12.0-beta.3`, fixes present, running clean.
- Two healthy customers: `v6.12.0-beta.1`, **fixes not present**.
- Alex Browning (the reporter): pre-fix, last known wedged at 13:19 on 19 Aug.
- Pending: ship to Alex, have him run 24 hours and resubmit a log either way. A draft email for this exists (§20.1).

---

## 3. System architecture, end to end

There are **three devices and one relay** involved in getting a text onto the flip phone.

```
┌─────────────────────┐         ┌──────────────────────┐        ┌──────────────────┐
│  Customer's         │         │  Offline relay       │        │  Flip phone      │
│  computer (Chrome)  │────────▶│  (Heroku WS)         │───────▶│  (launcher app)  │
│                     │  E2E    │  "Type Sync relay"   │  E2E   │                  │
│  Browser extension  │ encrypt │  phone-slot owned by │ decrypt│  GoogleCookie-   │
│  harvests cookies   │  blob   │  the flip phone      │  blob  │  ReceiveActivity │
└─────────────────────┘         └──────────────────────┘        └────────┬─────────┘
                                                                         │ cookies
                                                                         ▼
                                            ┌────────────────────────────────────────┐
                                            │  :gmessages module                      │
                                            │  1. mint __Secure-1PSIDTS  (RotateCookies)
                                            │  2. fetchConfig            (403, benign)
                                            │  3. SignInGaia             (get token)  │
                                            │  4. UKey2 handshake + emoji ──────────┐ │
                                            │  5. persist keys, start session       │ │
                                            └───────────────────────────────────────┼─┘
                                                                                    │
┌────────────────────────────┐                                                      │
│ Customer's SMARTPHONE      │◀──── confirms the emoji, becomes "dest registration"─┘
│ (real Google Messages app) │
│ owns the actual SIM / RCS   │
└────────────────────────────┘
```

### 3.1 The pieces

**Browser extension (customer's computer).** The customer signs in to Google in a private window and the extension freezes the cookie jar into a blob. **MEASURED:** the blob normally contains **14 cookies** and does *not* include the freshness pair, because the browser mints `__Secure-1PSIDTS` on Google's own schedule and the extension snapshots before that happens. This is the normal case, not an error.

**Type Sync relay (Heroku WebSocket).** Pre-existing infrastructure shared with the launcher's text-sync feature. The flip phone owns the `phone` slot; the companion connects as `android`. The cookie blob is E2E-encrypted with a shared secret established during ordinary device pairing (`PairingStore`), so the relay never sees credentials. Cookies are resent until acked.

**Flip phone launcher** (`launcher` repo). Owns the relay socket (inside `MouseAccessibilityService`, so it survives activity churn), the sign-in UI, and the announcement/entry points.

**`:gmessages` backend module** (`matrix-app/dpad-messenger-backend`). All protocol work: pairing, crypto, the session, the message repository, and the chat UI. This is where ~95% of the interesting code lives.

**Customer's smartphone.** Never talks to us directly. It is the "primary" device that owns the SIM. It confirms the UKey2 emoji, and thereafter it is the `destRegistrationId` we address. If it is off or offline, nothing works — that is by design and matches how `messages.google.com` behaves.

---

## 4. Repository map

Two git repos, both under `~/repos` on Jack's mac mini (`jacks-mac-mini-local`).

### 4.1 `matrix-app/dpad-messenger-backend` — the `:gmessages` Gradle module

`com.android.library`, `compileSdk 36`, `minSdk 24`, JVM target 17, Compose enabled, `isReturnDefaultValues = true` for unit tests (so `android.util.Log` is a no-op rather than throwing). Part of a **composite build** — `com.offline.dpadmessenger:library` is substituted for a sibling `dpad-messenger` project.

`gmessages/src/main/kotlin/com/offline/dpadmessenger/backend/gmessages/`

| Lines | File | Role |
|---:|---|---|
| 2350 | `GoogleMessagesSessionClient.kt` | **The core.** Long-poll receive loop, ack loop, all RPCs, cookie rotation scheduling, token refresh, active-session assertion, unpair. Where Bug B lived. |
| 1547 | `GoogleMessagesMessageRepository.kt` | Message/room model, dedup, echo reconciliation, contentless-"ghost"-row handling |
| 956 | `GMSessionProto.kt` | Session protobuf encode/decode, ACTION codes |
| 654 | `GMCookieRotation.kt` | **`RotateCookies` client.** Mint, refresh, backoffs, floors, link-time bootstrap. Where Bug A lived. |
| 593 | `GMGaiaPairing.kt` | UKey2 handshake (actions 44/45), emoji derivation, key persistence |
| 575 | `GMGaiaClient.kt` | Pairing orchestration: freshness gate → fetchConfig → SignInGaia → hand to `GMGaiaPairing` |
| 451 | `ui/GoogleMessagesApp.kt` | Compose host: pairing gate, relink paths, settings hooks, debug tools |
| 405 | `GoogleMessagesAccountStore.kt` | `SharedPreferences` persistence of cookies, keys, token, dest reg, rotation floor |
| 349 | `PbLite.kt` | `application/json+protobuf` (pblite) codec |
| 242 | `GMPairingProto.kt` | Endpoint URLs, user-agent strings, network names, API key |
| 242 | `GMUkey2.kt` | UKey2 crypto primitives |
| 230 | `GoogleMessagesRepository.kt` | Facade the launcher/UI talks to (`reauth()`, `unpairAndTearDown()`) |
| 211 | `X25519.kt` | Curve25519 |
| 196 | `GoogleMessagesNotifier.kt` | Notification posting/clearing |
| 184 | `GoogleMessagesCache.kt` | Room/message disk cache |
| 160 | `GoogleMessagesConfig.kt` | Feature flags + debug overrides |
| 148 | `Protobuf.kt` | Wire-format helpers |
| 134 | `ui/GoogleAccountLoginScreen.kt` | Sign-in screen |
| 131 | `GMCrypto.kt` | AES/HMAC session crypto |
| 118 | `ui/GoogleMessagesReconnectScreen.kt` | The "reconnect / re-link" screen |
| 96 | `GMGcm.kt` | GCM helper |
| 58 | `GMCookieAuth.kt` | SAPISIDHASH computation |
| 57 | `LocalContacts.kt` | Contact name resolution |
| 52 | `ui/GoogleCompanionSignInPrompt.kt` | Companion prompt |

### 4.2 `launcher` — the flip-phone launcher app

`app/src/main/java/com/offlineinc/dumbdownlauncher/`

| Lines | File | Relevance |
|---:|---|---|
| 2427 | `launcher/MouseAccessibilityService.kt` | Owns the Type Sync relay socket and the gmessages cookie callbacks |
| 1794 | `DumbDownApp.kt` | Application class, boot orchestration |
| 754 | `MainAppsGridActivity.kt` | App grid (also the site of an unrelated crash — §16.4) |
| 343 | `messenger/GoogleCookieReceiveActivity.kt` | The pairing wait/emoji/result screen |
| 220 | `typesync/TypeSyncService.kt` | Relay plumbing |
| 180 | `pairing/PairingStore.kt` | Shared secret + flip phone number |
| 166 | `diagnostics/DiagnosticsConfig.kt` | Rolling-logcat capture config |
| 164 | `messenger/MessengerActivity.kt` | Host activity for the chat UI |
| 92/66 | `messenger/GoogleMessagesAnnouncement*.kt` | First-run announcement |

### 4.3 Build and test commands

```bash
cd ~/repos/matrix-app/dpad-messenger-backend

# unit tests for the module (plain JVM, no device, no network)
./gradlew :gmessages:testDebugUnitTest

# the whole thing
./gradlew assembleDebug
```

Notes for a fresh session:
- `local.properties` pins `sdk.dir=/Users/jacknugent/Library/Android/sdk`. **The build only works on Jack's mac.** It cannot be built in a cloud container (needs SDK 36 + the composite sibling project) and it cannot be built in the Cowork device VM (that VM has no network and only Java 11).
- What *can* be done remotely: syntax-check staged copies with `ktlint` (a parse error shows as `Not a valid Kotlin file`). That catches unbalanced braces and bad string escapes but **not** type errors. Do not report a ktlint pass as "it compiles."
- Do **not** run `git` through the Cowork device bridge. It has repeatedly left an `index.lock` that git then cannot unlink. Read files, grep, and patch with a script; hand Jack the git commands to run himself.
- `device_bash` cannot delete files (`rm` → `Operation not permitted`). Move to a `_to_delete/` folder and tell Jack.

### 4.4 Pre-existing docs (and their staleness)

| Path | Lines | Verdict |
|---|---:|---|
| `launcher/GMESSAGES_GAIA_PORT.md` | 268 | **Still the best protocol reference.** Constants, flow, the two "gotchas". Predates both of today's bugs. |
| `launcher/GMESSAGES_STATUS.md` | 462 | Historical status log, June 2026 era. Its "next steps" are superseded by this document. Its rotation section describes a ~20-min cadence that is now 10 min. |
| `matrix-app/GMESSAGES_DOUBLE_BUBBLE_FIX.md` | 248 | Narrow: the send-echo dedup fix. Still accurate. |
| `GMESSAGES_SEAMLESS_LINK_DESIGN_20260817.md` | — | **Referenced from `GoogleMessagesAccountStore.kt` but DOES NOT EXIST in either repo.** Dangling reference. Either it was never committed or it lives outside these repos. Worth resolving — the comment citing it makes a claim about session-expiry that cannot currently be checked. |

---

## 5. The credential model — read this section twice

Almost every hard bug in this project has been a credential-lifetime bug. The model matters.

### 5.1 The cookies

**MEASURED** — a normal harvest, 14 names:

```
APISID, HSID, OSID, SAPISID, SID, SIDCC, SSID,
__Secure-1PAPISID, __Secure-1PSID, __Secure-1PSIDCC,
__Secure-3PAPISID, __Secure-3PSID, __Secure-3PSIDCC,
__Secure-OSID
```

After a successful mint, **16** — the two additions being `__Secure-1PSIDTS` and `__Secure-3PSIDTS`.

The two that matter:

- **`__Secure-1PSID`** — the long-lived login. This is what authenticates a rotation request. Without it there is nothing to rotate and no request worth making.
- **`__Secure-1PSIDTS`** — the short-lived **freshness** partner. Google mints it, expects it to be rotated on a schedule, and treats its continued rotation as evidence that a live client holds the session. **A session without it will die.** It is not optional and it is not cosmetic. This single fact is the whole of Bug A.

`SIDCC` / `__Secure-1PSIDCC` / `__Secure-3PSIDCC` are re-issued by Google on nearly every relay RPC (`Set-Cookie` on `ReceiveMessages` / `SendMessage` / `AckMessages`) and are absorbed automatically. They are noise in the log and healthy.

`NID` sometimes appears, making a 15-cookie harvest. See §14.4 — there is a correlation with mint failure that is **not** established as causal.

### 5.2 The rotation endpoint

**MEASURED:**

```
POST https://accounts.google.com/RotateCookies
Body:     [000,"-0000000000000000000"]           (jspb sentinel, literal)
Response: )]}'\n[["identity.hfcr",600],["di",981273401]]
```

- The `)]}'`  prefix is Google's XSSI guard; strip it before parsing.
- `600` is Google telling us the next interval in seconds. Honour it.
- On success, `Set-Cookie` carries the new `__Secure-1PSIDTS` / `__Secure-3PSIDTS`.

**The single most valuable protocol discovery in the project (17 Aug 2026):**

> The request **must** carry a **desktop** User-Agent (`GMPairingProto.WEB_USER_AGENT`). A byte-identical request with an Android UA returns **403** with `hfcr = 2147483647` ("never rotate"). This was misread for two months as "this account cannot rotate", when it actually means "you asked wrongly."

This is why `attempt()` logs a specific warning when it sees `hfcr = Int.MAX_VALUE`, telling the reader it is a *request* problem, not an *account* problem.

### 5.3 Timers and floors (all **MEASURED** from `GMCookieRotation.kt`)

| Constant | Value | Meaning |
|---|---|---|
| `MIN_INTERVAL_MS` | 60 s | Hard floor between attempts. Matches Google's observed rate limit (200, then 429, 429 from a signed-in console, 17 Aug). Independently corroborated: `notebooklm-py` uses the same 60-second floor. |
| `DEFAULT_INTERVAL_MS` | 600 s | Fallback when the response carries no interval. Also what Google actually returns. |
| `MAX_INTERVAL_MS` | 24 h | Clamp, to defend against a bogus interval parking rotation for years |
| `NEVER_ROTATE` | 2 147 483 647 | `hfcr` sentinel; see §5.2 |
| `FAILURE_BACKOFF_MS` | 120 s | After a non-2xx that isn't 429 |
| `RATE_LIMIT_BACKOFF_MS` | 30 min | After a 429 **in steady state** — there, a 429 genuinely means "stand down" |
| `LINK_RATE_LIMIT_BACKOFF_MS` | 70 s | After a 429 **at link time**. Added today. Parking a fresh, unprotected link for 30 minutes was strictly worse than asking again in 70 seconds. |
| `LINK_RETRY_DELAY_MS` | 70 s | The one link-time retry wait. Must stay **above** the 60 s floor or the retry is a no-op that still costs the user the wait — there is a test asserting exactly this. |

### 5.4 The token, and the two-week ceiling

`SignInGaia` returns a tachyon auth token with `ttl = 86400000000` — **microseconds**, i.e. **24 hours** (the logs confirm: `ttl=86400000000 → expires in 1439min`). `TOKEN_REFRESH_LEAD_MS = 1 h`, so the session refreshes it with an hour to spare via `RegisterRefresh`.

Separately there is a **Google session ceiling of roughly two weeks**, which token refresh cannot push out. Hence the day-13 proactive re-link banner: a user-initiated re-link is *always* a full re-pair (new QR + emoji) precisely because a token refresh cannot buy a new session.

### 5.5 The three rungs of recovery

Understanding which rung applies to a failure is most of the diagnostic work.

| Rung | Name | What it does | When it works |
|---|---|---|---|
| 1 | **Token refresh** (`refreshTokenIfNeeded` → `refreshToken`) | Renews the 24 h tachyon token via `RegisterRefresh`. Keeps everything else. | The cookies are fine and only the token is aging. The common case. |
| 2 | **`reauth()`** | Re-asserts against Google with the stored cookies. | The cookies are stale-but-alive. |
| 3 | **`adoptFreshCookies()`** | Takes a **newly harvested** cookie set into a **live** session, preserving UKey2 keys, the ECDSA refresh key, `destReg` and the pairing id — so no new registration is minted and no new emoji handshake is needed. | The Google login is genuinely dead but the user is willing to sign in again in the browser. Added because rungs 1 and 2 both fail there. |
| — | **Full re-pair** | New QR, new emoji, new pairing entry. | Everything else has failed. |

**Deliberate product decision (Jack, 19 Aug):** existing users hitting a genuinely dead cookie will re-link once, manually. There is no further automation of that path, and that is accepted. Do not "improve" this without asking.

---

## 6. Protocol reference

All **MEASURED** unless noted. Source of truth is `GMPairingProto.kt` and `GMSessionProto.kt`.

### 6.1 Endpoints

```
Pairing (QR mode, largely unused now):
  https://instantmessaging-pa.googleapis.com/$rpc/.../Pairing/RegisterPhoneRelay
  .../Pairing/RefreshPhoneRelay

Messaging (Bugle / QR network):
  .../v1/Messaging/ReceiveMessages
  .../v1/Messaging/SendMessage
  .../v1/Messaging/AckMessages

Messaging (GDitto / Google-account network) — what we actually use:
  /$rpc/google.internal.communications.instantmessaging.v1.Messaging/ReceiveMessages
  .../Messaging/SendMessage
  .../Messaging/AckMessages

Registration:
  .../Registration/RegisterRefresh

Media:
  https://instantmessaging-pa.googleapis.com/upload

Cookie rotation:
  https://accounts.google.com/RotateCookies
```

### 6.2 Identity constants

| Name | Value |
|---|---|
| `GOOGLE_API_KEY` | `AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8` |
| `QR_NETWORK` | `Bugle` |
| `GOOGLE_NETWORK` | `GDitto` ← the one in use |
| `PAIRED_DEVICE_NAME` | `dumbphone 2` (what the customer sees in their phone's device list) |
| `X_USER_AGENT` | `grpc-web-javascript/0.1` |
| `WEB_USER_AGENT` | desktop Chrome string — **mandatory for `RotateCookies`** (§5.2) |
| `USER_AGENT` | the Android string, for relay calls |
| Content types | `application/x-protobuf`, `application/json+protobuf` (pblite) |

Every relay request carries a **SAPISIDHASH** `Authorization` header, computed in `GMCookieAuth.kt`.

### 6.3 Action codes

| Code | Action |
|---:|---|
| 1 | list conversations |
| 2 | list messages |
| 3 | send message |
| 6 | list contacts |
| 9 | get or create conversation |
| 10 | mark message read |
| 16 | get updates |
| 17 | ack browser presence |
| 22 | notify ditto activity (this is `setActiveSession`) |
| 38 | send reaction |
| 44 | UKey2 CLIENT_INIT |
| 45 | UKey2 CLIENT_FINISH |
| 46 | unpair GAIA pairing (`RevokeGaiaPairing`) |

### 6.4 User alerts (inbound, from Google)

Seen in `onUserAlert`:

- `BROWSER_ACTIVE` — "this device is the receive target." Good news; logged at I level.
- `BROWSER_INACTIVE` — would mean displacement. **MEASURED: zero real occurrences across four captures** (§14.5).
- `MOBILE_BATTERY_RESTORED`, `MOBILE_DATA_CONNECTION` — informational; replayed as backlog on connect and deliberately not acted on.

---

## 7. The pairing flow, step by step

This is the sequence as it exists **after** today's changes. The ordering is load-bearing; §9 explains why.

```
1. User opens Smart Txt sign-in on the flip phone.
   → GoogleCookieReceiveActivity, relay callbacks registered, startRelay()
   → log: [relaydiag] waiting… Ns elapsed; relayConnected=true signingIn=false securingWaitMs=0 emoji=false

2. User signs in to Google in a private window on their computer.
   → relay: companion_connected (role=android)
   → relay: gmessages_cookies {encrypted, iv}
   → log: 🔓 saved 14 gmessages cookies; names=[…]; fp=<8 hex>

3. GMGaiaClient.run() starts.
   → log: run: starting with 14 cookies; … has1PSIDTS=false …; ageOfHarvest=0s

4. ★ FRESHNESS GATE (ensureFreshnessCookie) — added/moved today.
   a. If the harvest already carries __Secure-1PSIDTS (a 16-cookie harvest, i.e. the
      browser rotated before the QR rendered): DO NOTHING. Deliberately not refreshed —
      rotating invalidates the value the browser still holds, and an unexpired cookie we
      already have beats a newer one plus a race.
      → log: freshness: harvest already carries __Secure-1PSIDTS — nothing to mint
   b. Otherwise bootstrapForLink():
        - bootstrapNow(linkTime=true) → RotateCookies
        - on 200 + Set-Cookie: done
          → log: rotate BOOTSTRAP OK: accepted=[__Secure-1PSIDTS, __Secure-3PSIDTS] …
                 result=bootstrapped
        - on 401/403: STOP IMMEDIATELY. Do not wait. (added today)
        - on 429 or 200-with-empty-Set-Cookie: announce a wait via onRetryWait,
          sleep 70s, retry once directly into attempt() (bypassing the 60s floor,
          which has nothing left to protect after a 70s sleep)
   c. Ask the COOKIE, never the return value:
        if (cookies["__Secure-1PSIDTS"].isNullOrBlank()) → ABORT, surface lastError
      → success log: freshness: OK — 16 cookies with __Secure-1PSIDTS in hand
                     before SignInGaia (result=bootstrapped)
      → failure log: freshness: FAILED — no __Secure-1PSIDTS
                     (rotation result=… http=… cookies=…); aborting BEFORE SignInGaia,
                     so nothing is registered with Google and no device entry is stranded

5. fetchConfig → HTTP 403. THIS IS NORMAL AND UNIVERSAL.
   → log: fetchConfig HTTP 403 (1654 bytes) … setCookieNames=[NID]
   → log: run: no device UUID from config — reusing persisted web-device UUID
   The persisted UUID matters: minting a fresh random one each attempt registered a new
   "messages-web-…" device every try, which is why device lists kept growing.

6. SignInGaia → HTTP 200 (~10.4 KB)
   → returns the tachyon token, the mobile identity, and the account's full device list
   → log: signInGaia: token=104b ttl=86400000000 mobile=<email>
          dest=<b64 destReg> (1 primary of N devices)

7. GMGaiaPairing: UKey2 handshake
   → long-poll stream opens, then action=44 (CLIENT_INIT)
   → SERVER_INIT arrives on the long-poll, matched by reqId
   → log: ================ PAIRING EMOJI: 🥱 ================
   → user confirms the emoji on their smartphone
   → action=45 (CLIENT_FINISH), response matched
   → log: pairing CONFIRMED by phone; deriving session keys (keyDerivVer=1)
   → log: ================ GAIA PAIRING COMPLETE — account saved ================
   → log: RESULT attempt=… paired=true stage=CLIENT_FINISHED lastHttp=200 elapsed=5.6s
   → log: ATTEMPT #1 result=PAIRED config=403 signIn=200 elapsed=8.1s cookieAge=7s

8. Session starts (GoogleMessagesSessionClient.connect())
   → log: session start: gaia=true linkAge=0d … cookies[n=16 has1PSIDTS=true …]
   → long-poll #1 opens, setActiveSession OK, rooms load
```

**MEASURED end-to-end timings** (Jack's device, 19 Aug, three consecutive pairings on beta.3): mint in 347–526 ms; total 5.8 s, 8.1 s, 8.2 s. Every one `ATTEMPT #1` or `#2` `result=PAIRED`.

---

## 8. The steady-state session

Once paired, `GoogleMessagesSessionClient` runs two cooperating coroutines plus timers.

### 8.1 `longPollLoop` / `openLongPollOnce`

Opens `Messaging/ReceiveMessages` and reads a stream of newline-delimited pblite elements. `OkHttp` `readTimeout(0)` — unbounded at the client level, because a long-poll is *supposed* to sit idle. **This is exactly what made Bug B possible** (§10).

**Post-fix, per stream:**

```kotlin
val readDeadlineMs = GoogleMessagesConfig.streamReadDeadlineOverrideMs
    .takeIf { it > 0L }
    ?.also { Log.w(TAG, "STREAM DEADLINE OVERRIDE ACTIVE — …DEBUG ONLY…") }
    ?: STREAM_READ_DEADLINE_MS
source.timeout().timeout(readDeadlineMs, TimeUnit.MILLISECONDS)
lastStreamActivityMs = System.currentTimeMillis()
lastStreamHeartbeatMs = 0L      // per-stream reset — see §11.3
```

`lastStreamActivityMs` is re-stamped after every `read > 0`.

### 8.2 `ackLoop`

Acks received messages, and doubles as the **timer thread**: rotation-due checks, token-refresh checks, the 30-minute active-session re-assert, the `alive:` heartbeat (moved here today), and the new stale-stream watchdog.

### 8.3 Constants (**MEASURED** from `GoogleMessagesSessionClient.kt`)

| Constant | Value | Notes |
|---|---|---|
| `MAX_LONGPOLL_FAILURES` | 8 | Before giving up on reconnecting |
| `TOKEN_REFRESH_LEAD_MS` | 1 h | Refresh the 24 h token this early |
| `HEARTBEAT_INTERVAL_MS` | 5 min | Cadence of the `alive:` line |
| `ACTIVE_SESSION_RETRY_MS` | 15 s → max 5 min | Backoff on `setActiveSession` rejection |
| `ACTIVE_SESSION_MAX_REJECTS` | 5 | Then treat the token as dead |
| `ACTIVE_SESSION_REASSERT_MS` | 30 min | Unconditional re-assert, by design |
| `STREAM_HEARTBEAT_OBSERVED_MS` | 10 s | **Google's measured keepalive.** Documentation of an observation, not a timer. |
| `STREAM_READ_DEADLINE_MS` | **5 min** | Fix 1. Sized at 30× the keepalive. |
| `STREAM_STALE_MS` | **10 min** | Fix 2, the watchdog. 2× the deadline, so the deadline gets first refusal. |
| `MAX_AUTO_RECLAIMS` | 3 | Automatic receive-slot reclaims before stopping |
| `RPC_CALL_TIMEOUT_MS` | 25 s | Normal RPC |
| `UNPAIR_CALL_TIMEOUT_MS` | 3 s | Unpair is best-effort and must not hang teardown |

### 8.4 Feature flags and debug overrides (`GoogleMessagesConfig.kt`)

| Flag | Default | Purpose |
|---|---|---|
| `cookieRotationEnabled` | `true` | Master switch for rotation |
| `cookieBootstrapOnRecovery` | `true` | Master switch for minting |
| `tokenRefreshLeadOverrideMs` | `0` | Debug: arm an early token refresh to test that a mid-session refresh doesn't disturb the stream. **Confirmed 17 Aug: 592 ms, HTTP 200, no reconnect, messages never stopped.** |
| `streamReadDeadlineOverrideMs` | `0` | Debug: shorten the read deadline to force the timeout path. Sticky by design, guarded by a test and a loud `Log.w`. |
| `messengerActivityClassName` | `null` | Launcher wiring |

**Testing note that cost real time to learn:** a 60-second override will *never* fire on a healthy stream, because keepalives arrive every 9–10 s and each one resets the deadline. To force the timeout you must set the override **below the keepalive**, e.g. `5_000L`.

---

## 9. Bug A — link-time freshness failure

### 9.1 The symptom

The phone printed `GAIA PAIRING COMPLETE`, the user was told they were linked, and the link died anywhere from 13 minutes to a few hours later with no error the user could act on.

### 9.2 The evidence (Alex Browning, 19 Aug, capture `20260819122500Z2`)

```
06:41:13.942  GMCookieRot: bootstrapNow: requested (mint=true cookies=15)
06:41:14.516  GMCookieRot: rotate BOOTSTRAP OK: accepted=[] changed=false
                           nextIn=600s result=bootstrap-empty
06:41:14.517  GMPairResult: RESULT attempt=790f2d13… paired=true stage=CLIENT_FINISHED
06:41:14.525  GMPairResult: ATTEMPT #1 result=PAIRED config=403 signIn=200
                            elapsed=16.0s cookieAge=16s
```

Read those four lines together: Google was asked to mint the freshness cookie, returned **HTTP 200 with an empty `Set-Cookie`**, nothing changed — and one millisecond later the code declared the pairing a success.

It then confirmed itself dead:

```
06:54:33.979  bootstrapNow: requested (mint=true cookies=15)
06:54:34.425  rotate BOOTSTRAP OK: accepted=[] changed=false result=bootstrap-empty
08:19:36.975  bootstrapNow: requested (mint=true cookies=15)
08:19:37.591  rotate BOOTSTRAP OK: accepted=[] changed=false result=bootstrap-empty
08:19:38.147  bootstrapNow: skipped — last attempt 1s ago (floor 60s)
```

Three empty mints spanning **98 minutes**. Then:

```
08:20:34.263  🔓 saved 14 gmessages cookies; names=[…no NID…]
08:20:42.072  bootstrapNow: requested (mint=true cookies=14)
08:20:42.580  rotate BOOTSTRAP OK: accepted=[__Secure-1PSIDTS, __Secure-3PSIDTS]
                           changed=true result=bootstrapped
08:20:42.614  ATTEMPT #2 result=PAIRED config=403 signIn=200 elapsed=8.0s cookieAge=0s
```

A **fresh harvest** minted immediately and produced a healthy link.

### 9.3 Root cause

Two separate defects compounding:

**(a) The wrong question.** The code asked *"did the rotation change anything?"* That question is unanswerable, because `changed = false` is **both** the healthy case (a 16-cookie harvest already carried the pair; Google returned the same values) **and** the fatal case (the mint produced nothing). The old log line literally said `nothing minted (harvest may already carry the pair)` while three lines above, the same capture read `has1PSIDTS=false`.

**(b) The mint happened too late and could not retry.** It ran near the end of pairing, single-shot, and a 429 parked rotation for the steady-state 30 minutes — on a session that had no freshness cookie at all.

### 9.4 The fix

1. **Ask the cookie, never the delta.** One gate, one question: `cookies["__Secure-1PSIDTS"].isNullOrBlank()`. This is the change that closes the *category*, not just the instance.
2. **Move the mint before `SignInGaia`** (Fix 6). If the mint fails we abort before anything is registered with Google, so no orphan device entry is stranded in the customer's device list.
3. **Retry once** with a link-time-appropriate 70 s wait, re-entering `attempt()` directly to bypass the 60 s floor.
4. **Distinguish rejection from rate limit** (added last, after Jack hit it himself — §9.5). A 401/403 does not expire; waiting cannot help.
5. **Tell the user something true.** Two different messages for two different failures:
   - 401/403: *"Google rejected this login. On the computer, sign in again, then rescan the code."*
   - anything else: *"Couldn't finish securing the connection to Google. Wait a minute, then sign in again on the computer and rescan the code."*
6. **Say what's happening during the wait.** `onRetryWait` fires only when a wait will really happen, so the screen never flashes a message it has to retract. The copy, Jack's exact words: *"we are refreshing with Google to link this phone. This can take a minute or two, then an emoji will appear to finish linking your smartphone."*

### 9.5 The 401 discovery (Jack's own device, 19 Aug 15:39)

```
15:39:24.270  🔓 saved 14 gmessages cookies; … fp=a8fe5420
15:39:25.200  rotate BOOTSTRAP HTTP 401 (39B) — cookies untouched:
                 )]}' [["identity.hfcr",600],["di",22]]
15:39:25.213  bootstrapForLink: nothing minted (result=bootstrap-http401) — waiting 70s …
15:40:35.306  rotate BOOTSTRAP HTTP 401
15:40:35.323  freshness: FAILED … ATTEMPT #1 result=FAILED … elapsed=70.7s
--- user signs in again ---
15:43:10      fresh harvest fp=88037b55 → minted in 400ms → ATTEMPT #2 PAIRED elapsed=10.7s
```

`fp=a8fe5420` is **the same fingerprint that minted successfully at 14:48** — a 51-minute-old blob. So a 401 here means *these credentials are stale/rejected*, and the user spent 70 seconds behind a screen promising "this can take a minute or two" to be told something we already knew.

**This points at an upstream fix that has NOT been written** (§15.1): stamp the harvest and refuse a blob older than ~10 minutes locally, with "this code is stale, refresh the page", rather than round-tripping to Google to be rejected.

### 9.6 Independent cross-check (INFERRED support, external)

Other reverse-engineered Google-login projects were checked to see whether our approach deviates:

- **`notebooklm-py`** independently uses the same `RotateCookies` endpoint, the same jspb sentinel body, the same 600 s interval, and a **60-second floor** matching our `MIN_INTERVAL_MS`. Their issue #865 fix is **structurally identical** to `ensureFreshnessCookie` — refuse to proceed without the freshness cookie.
- **Gemini-API-adjacent projects** log the same "HTTP 200 with empty `Set-Cookie`" phenomenon. Cause unexplained there too.
- **Our desktop-User-Agent requirement (§5.2) appears to be novel** — no other project found documents it.

Conclusion: the design is conventional for this problem space. We are not doing anything strange.

---

## 10. Bug B — the receive-stream wedge

### 10.1 The symptom, in the customer's words

> *"Right now it seems I'm still synced, and I can send messages, but I don't see incoming ones."*

### 10.2 The evidence (Alex, capture `20260819171907Z1`)

The 08:20 session was healthy — 16 cookies, `has1PSIDTS=true`. Then:

```
11:24:22.046  alive: up 3h3m … inbound[last inbound 12m ago] rot[last=rotated nextDueIn=412s]
11:24:27.661  session long-poll #14 open
11:24:27.663  startup ack count=0
              … and then nothing from the stream for 1h55m, to the end of the capture
```

**No stream #15. No further `alive:` line. No inbound message.** Meanwhile, on other threads, everything looked perfect:

```
11:43:44  GMCookieRot: rotate OK: accepted=[…] changed=true result=rotated
11:51:15  re-asserting active session [up 3h30m, last inbound 39m ago]
11:52:19  setActiveSession re-asserted OK [up 3h31m]
12:06:38  rotate OK … result=rotated
12:18:19  rotate OK … result=rotated
12:21:16  re-asserting active session [up 4h0m, last inbound 69m ago]
12:22:27  setActiveSession re-asserted OK [up 4h1m]
12:52:54  setActiveSession re-asserted OK [up 4h32m]
13:10:04  rotate OK … result=rotated
```

Cookie rotation succeeded every ~10 minutes right through 13:10. Sends went out at 11:51, 12:06, 12:09, 12:21. The `last inbound` counter climbed monotonically: 39 m → 69 m. Authentication was flawless the entire time.

One TCP connection had gone quiet and the phone was waiting on it forever.

### 10.3 Root cause

`OkHttp` `readTimeout(0)` plus an unbounded `source.read()`. When Google's receive stream stopped producing bytes without closing, `read()` blocked indefinitely. There was no deadline, no watchdog, and no way for any other part of the system to notice.

**Confirmed** by a stack trace pinned to line 1097 in an earlier reproduction.

### 10.4 Why it was so hard to see

`maybeHeartbeat` — which prints the `alive:` line, the single most useful diagnostic in the whole system — had exactly **one call site, inside `longPollLoop`, inside the `code == 0` branch**. That is to say: the health report was printed by the very loop that gets stuck. A wedge therefore produced *silence*: no error, no exception, no clue. Just an absence.

This retro-explains why Alex's `alive:` lines stop dead at 11:24:22 and why the earlier round of diagnosis went in circles.

### 10.5 The fix (three parts)

**Fix 1 — a read deadline.** `source.timeout().timeout(STREAM_READ_DEADLINE_MS)`, 5 minutes, override-aware. A stream that has produced nothing for 5 minutes is dead, not idle, because a healthy one speaks every 9–10 seconds.

**Fix 2 — a watchdog in `ackLoop`.** If `now - lastStreamActivityMs > STREAM_STALE_MS` (10 min), cancel `longPollJob` and relaunch it. Belt and braces: if the deadline somehow doesn't fire, this does. It runs on a *different* thread from the one that can wedge, which is the entire point.

**Fix 3 — make the failure visible.**
- `maybeHeartbeat` moved out of `longPollLoop` and into the `ackLoop` timer (`% 12` block).
- The `alive:` line gained **`stream[quiet Ns]`** via a new `streamGap(now)` helper.
- The per-stream heartbeat branch now logs the gap (`stream heartbeat — 9s since previous`, `stream heartbeat — first on this stream`).

### 10.6 How the deadline got sized — an instructive mistake, preserved in a comment

The first version used **25 minutes**, derived from observed stream *lifetimes*, because the keepalive interval had never been logged. Once Fix 3 made keepalives visible, the first measurement showed **9–10 seconds** — so 25 minutes was ~150× too loose. Retuned to 5 minutes.

There was a second, subtler error in that first measurement: gaps of 14 s / 23 s / 37 s / 65 s appeared to be keepalive jitter, but were actually **reconnect backoff spanning two different streams**. Fixed by resetting `lastStreamHeartbeatMs = 0L` per stream, so a gap is only ever measured within one stream's life.

Both mistakes are documented in the constant's KDoc on purpose. The lesson generalises: **derive a timeout from the signal you are actually watching, and make sure your measurement can't span a discontinuity.**

### 10.7 What the fix does and does not do

- **Does:** bound the worst case at ~5 minutes of delayed inbound messages instead of forever, and make a recurrence legible in one log line.
- **Does not:** explain why the stream goes quiet. See §14.3.
- **Untested in anger:** since the fix, nothing has wedged, so neither the deadline nor the watchdog has ever fired in the wild.

---

## 11. Complete change log — 19 August 2026

### 11.1 `GMCookieRotation.kt`

- Added `LINK_RATE_LIMIT_BACKOFF_MS = 70_000L` and `internal const val LINK_RETRY_DELAY_MS = 70_000L` (originally 90 s; shortened to 70 s after device feedback).
- `bootstrapNow(http, cookies, linkTime: Boolean = false)` — threads `linkTime` through to `attempt` → `rotate`.
- **New** `bootstrapForLink(http, cookies, sleep, onRetryWait)` — one retry, re-entering `attempt()` directly to bypass `MIN_INTERVAL_MS`; `sleep` and `onRetryWait` are seams for tests and for the UI respectively.
- 429 branch now chooses its backoff by `linkTime`.
- **New empty-mint diagnostic** (Fix 4):
  ```kotlin
  if (bootstrap && !changed) {
      Log.w(TAG, "rotate BOOTSTRAP empty — HTTP ${resp.code} bodyLen=${body.length} " +
          "setCookie=${resp.headers("Set-Cookie").map { it.substringBefore('=') }} " +
          "body=${body.take(200)} ${diagHeaders(resp)}")
  }
  ```
- **New** private `diagHeaders(resp)` helper.
- **New** `@Volatile var lastHttpCode: Int = 0 private set` — recorded on the 429, http-error and 2xx paths (2xx clears it to 0), cleared by `reset()`.
- **New no-retry guard:**
  ```kotlin
  if (lastHttpCode == 401 || lastHttpCode == 403) {
      Log.w(TAG, "bootstrapForLink: Google REJECTED these credentials (HTTP $lastHttpCode) — " +
          "NOT retrying. A rejection does not expire; the harvest is stale or the " +
          "Google session is gone, and only a fresh sign-in fixes either.")
      return changed
  }
  ```

### 11.2 `GMGaiaClient.kt`

- `run(onEmoji, onSecuring)`; `runInner` now takes a **mutable** cookie map (`HashMap(store.loadCookies())`), calls `GMCookieRotation.reset()`, then `ensureFreshnessCookie(cookies, onSecuring)` **before** `fetchConfig` (Fix 6).
- **New** `ensureFreshnessCookie(...)`: fast path for a 16-cookie harvest; otherwise `bootstrapForLink`; then the cookie-presence gate; abort with `lastError` on failure.
- Error copy branches on `GMCookieRotation.lastHttpCode` (401/403 vs everything else).
- `freshness: FAILED` log corrected — it used to claim `after retry` on a path where no retry happens, and now prints `http=<code>` instead.

### 11.3 `GoogleMessagesSessionClient.kt`

- **New fields:** `lastStreamActivityMs`, `lastStreamHeartbeatMs`, `aliveLogLastMs`.
- **New constants:** `STREAM_HEARTBEAT_OBSERVED_MS = 10_000L`, `STREAM_READ_DEADLINE_MS = 5 * 60_000L` (was 25 min), `STREAM_STALE_MS = 10 * 60_000L` (was 30 min).
- **Fix 1** (~line 1092): override-aware read deadline + per-stream heartbeat reset.
- `lastStreamActivityMs` stamped after every `read > 0`.
- **Fix 2:** stale-stream watchdog in `ackLoop` — cancel + relaunch `longPollJob` at `STREAM_STALE_MS`.
- **Fix 3:** heartbeat branch logs the gap; `maybeHeartbeat` moved from `longPollLoop` to `ackLoop`'s `% 12` block; `alive:` line gained `stream[${streamGap(now)}]`; new `streamGap(now)` helper.
- Comment at `adoptFreshCookies` (line ~424) rewritten — see §11.4.

### 11.4 Unevidenced claims removed (three sites)

The codebase asserted, as fact, that stale `messages-web-*` device entries **compete for the receive slot** and can **silently take over receiving**. One site went further and called it *"the leading hypothesis for Bug A"* — which is now flatly wrong, since Bug A was the missing freshness cookie and Bug B was the unbounded stream read.

**MEASURED:** four captures were searched for `BROWSER_INACTIVE` and for displacement of the active receive registration. **Zero real occurrences**, across accounts holding 33, 11, and two more device entries. (An earlier grep appeared to find 9 hits in Alex's log; all 9 were the re-assert line's own explanatory text matching the pattern.)

Rewritten at:
- `ui/GoogleMessagesApp.kt` (`freshRelink`) — now states what is observed (entries accumulate; one test account reached 30+, a customer's 11), that displacement was searched for and not found, and that the cleanup is therefore housekeeping rather than a fix for a known receive bug. Ends: *"If a stale-entry takeover is ever seen in a log, say so here with the evidence."*
- `GoogleMessagesAccountStore.kt:209` (`saveGaiaSession` KDoc) — same treatment, plus *"Do not cite it as a cause without a log that shows one."*
- `GoogleMessagesSessionClient.kt:424` (`adoptFreshCookies` KDoc) — now says the reason rung 3 matters is **the user's time** (skipping a QR scan and a second emoji handshake), and explicitly marks the takeover claim as unverified and *not* the cause of either diagnosed bug.

**Jack caught the first of these himself**, having earlier caught and disproved a similar unevidenced claim about Google Workspace accounts being unable to sign in. Both were mine. Finding sites 2, 3 and 4 was only possible because he flagged site 1.

### 11.5 Launcher repo

- `messenger/GmessagesCookieCallbacks.kt` — added `val onSecuring: (Long) -> Unit`.
- `launcher/MouseAccessibilityService.kt` — `pairingSecuringMs` state, replay on re-register, `gaia.run(onEmoji = …, onSecuring = …)`, `⏳ securing` log.
- `messenger/GoogleCookieReceiveActivity.kt` — `securingMs` state; new UI branch ordered **emoji → securing → signingIn → waiting → result** (checked before the generic signing-in branch and after the emoji one, because the pause happens before an emoji exists and once one exists the emoji is the thing to show); Jack's exact copy; heartbeat line now carries `securingWaitMs=`.
- **Merge conflict resolved** (`feature/logging-for-google-messages-cookie` → `beta`), one hunk in `GoogleCookieReceiveActivity.kt:151-162`. Both sides had rewritten the same `GMCookieWait` heartbeat string. Resolution: took the feature branch's content (the old *"waiting for smart phone…"* wording was actively wrong — it blamed the phone during a 70 s cookie mint the phone had nothing to do with) and **restored the `[relaydiag]` prefix** from `beta`, because every other line on that path carries it and this is the one you most want a grep to return. Verified live at 16:19:41 on Jack's device.
- `DumbDownApp.kt` — a test scaffold was added and then fully reverted (byte-identical; a stray double blank line was caught and collapsed).

### 11.6 Tests

- `GMCookieRotationLinkTest.kt` — **new**, now **18 tests**. Fake Google via an OkHttp `Interceptor`, so no socket is ever opened and every failure shape is testable, including the one we cannot provoke on demand (the 200-with-empty-`Set-Cookie`).
- `GMSessionStreamTest.kt` — **new**, 4 tests on threshold ordering, the keepalive multiple, and the override default.
- `GMCookieRotationTest.kt` — the Workspace rationale removed and replaced with observed evidence.

---

## 12. Instrumentation reference — how to read a log

This is the highest-value section for a new session. The rolling logcat is the only telemetry that exists, so knowing which line answers which question is most of the diagnostic skill.

### 12.1 Log tags

| Tag | Owner | Watch for |
|---|---|---|
| `GMCookieRot` | `GMCookieRotation` | mint/rotate outcomes, backoffs, floors |
| `GMGaia` | `GMGaiaClient` | pairing steps, `freshness:` verdict. **Contains the tachyon token — excluded from support bundles.** |
| `GMGaiaPair` | `GMGaiaPairing` | UKey2, the emoji, `GAIA PAIRING COMPLETE` |
| `GMPairResult` | pairing summary | `RESULT` / `ATTEMPT #N` one-liners |
| `GMSession` | `GoogleMessagesSessionClient` | `alive:`, stream heartbeats, long-poll opens, `setActiveSession`, `unpair` |
| `GMCookies` | cookie persistence | `cookie set changed → N received […] (has __Secure-1PSIDTS=…)` |
| `GMRepo` / `MsgStore/gmessages` | message layer | echo reconciliation, room names, saves |
| `GMNotify` | notifier | notification post/clear |
| `TypeSyncRelay` | launcher | relay socket, `[relaydiag]` lines, cookie arrival |
| `GMCookieWait` | launcher pairing screen | `[relaydiag] waiting… …securingWaitMs=…` |

### 12.2 The single most important line

```
alive: up 21m linkAge=0d expiry=1418min net=wifi/validated=true
       cookies[n=16 has1PSIDTS=true has3PSIDTS=true names=[…]]
       inbound[last inbound 13m ago] stream[quiet 9s] rot[last=rotated nextDueIn=539s]
```

Field by field:

| Field | Healthy | Alarm |
|---|---|---|
| `up` | monotonically increasing | resets = process restart |
| `linkAge` | 0–13 d | ≥13 d → the two-week ceiling is near |
| `expiry` | ≤1439 min, decreasing | small numbers with no refresh happening |
| `net` | `wifi/validated=true` or `cell/validated=true` | `validated=false` |
| `cookies[n=…]` | **16** with `has1PSIDTS=true` | **14 or `has1PSIDTS=false` = the link is doomed** |
| `inbound[…]` | resets to `0m` when texts arrive | monotonic climb over hours **on an active user** |
| `stream[quiet Ns]` | **0–10 s** | **>20 s means the stream is going quiet; this is the wedge tell** |
| `rot[last=…]` | `rotated` / `bootstrapped` | `bootstrap-empty`, `429`, `http401`, `threw:…` |

**Critically: as of today this line is printed by `ackLoop`, not by the receive loop.** If it stops appearing while the process is alive, that is now a much stronger signal than before, because the thread that prints it is not the thread that wedges.

### 12.3 Verdict lines you can grep for

```bash
# pairing outcomes
grep -E "GMPairResult|freshness:|GAIA PAIRING COMPLETE"

# the cookie question, answered
grep -E "has1PSIDTS|rotate BOOTSTRAP|result=(bootstrapped|bootstrap-empty)"

# the stream question, answered
grep -E "alive:|stream heartbeat|session long-poll #|STREAM DEADLINE OVERRIDE"

# relay / cookie delivery
grep -E "\[relaydiag\]|saved [0-9]+ gmessages cookies"
```

### 12.4 Lines that look like errors and are NOT

Anyone new to these logs will chase these. Don't.

| Line | Why it's fine |
|---|---|
| `fetchConfig HTTP 403 … setCookieNames=[NID]` | Universal, every pairing, every account. Handled: falls back to the persisted web-device UUID. |
| `Set-Cookie on …/SendMessage: SIDCC, __Secure-1PSIDCC, __Secure-3PSIDCC` | Google re-issuing the CC cookies on nearly every RPC. Absorbed automatically. |
| `unmatched long-poll msg sessionId= action=0` | Pairing-stream chatter that isn't ours. |
| `user alert BROWSER_ACTIVE … replayed backlog, not acting on it` | Backlog replay on connect, deliberately ignored. |
| `unpair: Google REJECTED the revoke (HTTP 401)` | The *normal* outcome on the credentials-are-dead re-link path. The status check exists precisely so this doesn't get logged as success. |
| `[socket]:check permission begin!`, `ClassNotFoundException: com.mediatek.cta.CtaUtils` | MediaTek platform noise. Not ours. |
| `Davey! duration=…`, `Skipped N frames`, `[ANR Warning] onMeasure` | Slow-device rendering. Real, but a different problem. |
| `DeadSystemException: The system died` | The whole device crashed; our process is collateral. Look for `systemui` / `com.android.phone` dying at the same second. |

### 12.5 Lines that mean something is genuinely wrong

| Line | Meaning |
|---|---|
| `freshness: FAILED — no __Secure-1PSIDTS (… http=…)` | Pairing correctly refused. `http=401/403` → stale harvest or dead login. |
| `rotate BOOTSTRAP empty — HTTP 200 …` | The unexplained empty mint (§14.1). |
| `rotate BOOTSTRAP HTTP 401` | Credentials rejected. No retry, by design. |
| `rotate refused with hfcr=never` | A **request** problem (check the User-Agent), not an account problem. |
| `alive:` stops while the process lives | Something has wedged. Post-fix this should be near-impossible. |
| `stream[quiet >20s]` | The wedge, forming. |
| `STREAM DEADLINE OVERRIDE ACTIVE` | Debug flag left on in a shipped build. Should never appear in a customer log. |
| `bootstrapNow: skipped — last attempt Ns ago (floor 60s)` | Benign in isolation, but a *symptom* of the double-call gap in §15.2. |

---

## 13. Measured facts and baselines

Everything here is **MEASURED**, with provenance. This table is the reason a new session shouldn't need to re-derive anything.

| Quantity | Value | Provenance |
|---|---|---|
| Google's receive-stream keepalive | **9–10 s** | Jack's device, beta.3, 19 Aug, hundreds of samples, screen on, wifi |
| Receive-stream lifetime, median | **~891 s / ~905 s** (~15 min) | Two healthy customers, beta.1, ~34 h each, 135 + 127 cycles |
| Receive-stream lifetime, max | **1183 s / 1201 s** (~20 min) | Same. **Zero of 262 cycles exceeded 20 minutes.** |
| Receive-stream lifetime, Jack's beta.3 | 16 m 53 s (stream #1 → #2, clean rollover) | Jack's device, 19 Aug 15:57:35 → 16:14:28 |
| Alex's wedged stream | **≥1 h 55 m**, never returned | Alex, 19 Aug, stream #14 opened 11:24:27, capture ends 13:19 |
| Rotation interval Google asks for | 600 s | every `nextIn=600s` |
| Rotation floor Google enforces | ~60 s | 200, then 429, 429 from a signed-in console, 17 Aug. Corroborated by `notebooklm-py`'s 60 s floor. |
| Consecutive successful rotations, healthy devices | **271** (118 + 153), zero 429, zero empty mints, zero 401/403 | Two healthy customers, ~34 h each |
| Tachyon token TTL | 24 h (`ttl=86400000000` µs) | every `signInGaia:` line |
| Google session ceiling | ~2 weeks | day-13 re-link banner; **INFERRED**, and the doc that justified it is missing (§4.4) |
| Mid-session token refresh cost | 592 ms, HTTP 200, no reconnect, messages never stopped | 17 Aug, via `tokenRefreshLeadOverrideMs` |
| Pairing end-to-end, post-fix | 5.8 s / 8.1 s / 8.2 s | Jack, 19 Aug, three consecutive pairings |
| Link-time mint latency, post-fix | 347–526 ms | same |
| Normal harvest size | 14 cookies (16 after mint) | every `🔓 saved N gmessages cookies` |
| `BROWSER_INACTIVE` / displacement events | **0** | four captures; device-entry counts 33, 11, and two more |
| `fetchConfig` result | 403, 1654 bytes, `setCookieNames=[NID]` | universal, both accounts, every attempt |
| Empty mints on Alex's failing harvest | 3 of 3, spanning 98 min, all with `NID` present (15 cookies) | Alex, 06:41 / 06:54 / 08:19 |
| Successful mint on Alex's next harvest | 1 of 1, 14 cookies, no `NID` | Alex, 08:20:42 |
| Device entries in Jack's account | 33 (`1 primary of 33 devices`) | `signInGaia:` line, 19 Aug |

---

## 14. Open questions, ranked

### 14.1 Why does `RotateCookies` return HTTP 200 with an empty `Set-Cookie`? — **UNKNOWN**

The highest-value unknown. It is the mechanism behind Bug A's original failure, we cannot provoke it on demand, and Google gives no error. Observed three times in 98 minutes on one device, never on the others.

What we know: it is not a rate limit (`rotate BOOTSTRAP OK` is only reachable on a 2xx, so a 429 would have printed differently). It resolved with a **fresh harvest**, not with time.

We have contained it (pairing refuses; the empty mint is retried once and then reported honestly) and logged it thoroughly via the Fix 4 diagnostic. The next occurrence should carry response headers, body prefix, and `Set-Cookie` names, which is more than we had.

Other projects see it too and also can't explain it (§9.6).

### 14.2 Is the 5-minute read deadline right under Android Doze? — **UNKNOWN, and the most actionable gap**

The deadline is sized at 30× a keepalive measured with the **screen on**, on **wifi**. Under Doze, Android defers network wakeups; if keepalives are delayed past 5 minutes, the deadline fires when nothing is actually wrong and the phone reconnects needlessly — burning battery and possibly churning the receive registration.

**The measurement that resolves this:** one overnight capture, screen off, on the charger, with the new `stream[quiet Ns]` field in place. If overnight gaps stay under a minute, 5 minutes is safe and could even tighten to 60–90 s. If they routinely exceed a minute, the deadline needs to be Doze-aware (or the watchdog needs to be the primary mechanism, with the deadline relaxed).

This is a single overnight run. **Do it first.**

### 14.3 Why does the receive stream go silent? — **UNKNOWN**

Google stopped sending on an established connection without closing it, for at least 1 h 55 m, while the same credentials worked fine on every other endpoint. Candidates never tested: a carrier/NAT idle timeout dropping the connection without a FIN (Alex was on `net=cell`); a Google-side per-stream cap; something about a stream that opens with `startup ack count=0`.

Worth noting: Alex was on **cell**, and the two healthy comparison devices' streams recycled every 15 minutes like clockwork. A NAT idle-timeout hypothesis is consistent with a silent, FIN-less death — but this is **INFERRED and untested**. Do not write it down as the cause.

### 14.4 Is `NID` implicated in mint failure? — **UNKNOWN, tempting, do not conclude**

**MEASURED:** on Alex's device, 3/3 harvests carrying `NID` (15 cookies) produced empty mints; the 1/1 harvest without `NID` (14 cookies) minted immediately. Earlier: 6/6 mints succeeded on `NID`-free harvests, 0/3 on `NID`-bearing ones.

**Why this is not a conclusion:** n=1 device for the two-sided comparison, and it is confounded with browser-extension version — the extension that produced the `NID` harvests may differ from the one that produced the clean ones. It is also plausible that `NID`'s presence is a *symptom* of whatever browser state causes the empty mint, not a cause.

**How to settle it:** get one device to produce both harvest shapes with the *same* extension version, minutes apart. Until then it is a lead.

### 14.5 Can a stale device entry take over receiving? — **UNKNOWN, and now marked as such in the code**

Zero evidence across four captures with up to 33 entries. Previously asserted as fact in three places; now labelled unverified (§11.4). If it ever *is* observed, the log line to look for is `BROWSER_INACTIVE`, and the code comments explicitly ask for the evidence to be recorded.

---

## 15. Known gaps and deferred work

### 15.1 The harvest has no shelf life — **not written, highest-value product fix**

Jack's 401 came from a **51-minute-old** harvest. We now fail fast, but the phone still round-trips to Google to be told the blob is stale. The right fix is local: stamp the harvest and refuse anything older than ~10 minutes with *"this code is stale, refresh the page"*, moving the answer to where the user can act on it.

Note that `GMGaiaClient` already logs `ageOfHarvest=Ns`, so the data is in hand; nothing consumes it as a gate.

### 15.2 The reauth path calls `bootstrapNow` twice and the second call is floor-skipped

Visible in Alex's log:

```
08:19:36.975  bootstrapNow: requested (mint=true cookies=15)
08:19:37.591  rotate BOOTSTRAP OK: accepted=[] … result=bootstrap-empty
08:19:38.147  bootstrapNow: skipped — last attempt 1s ago (floor 60s)
```

The second call is wasted — the 60 s floor declines it 500 ms later. Harmless today, but it means the reauth path effectively gets **one** attempt where the code reads as though it gets two. Either collapse the duplicate call or give reauth the same `bootstrapForLink` treatment that link time got.

### 15.3 `messages-web-*` entries accumulate

Every full re-pair adds an identically-named entry to the customer's device list (33 on Jack's account). Revoke is attempted (`ACTION_UNPAIR_GAIA_PAIRING`) and **MEASURED** as succeeding with HTTP 200 when credentials are still good; it 401s on the credentials-are-dead path, which is expected. So it is bounded in practice but not zero. Cosmetic as far as we can prove (§14.5) — but customers see it.

### 15.4 No automated recovery for a genuinely dead cookie

By decision, not oversight (§5.5). The user re-links once.

### 15.5 The unit-test suite has not been run in this session

The code compiles and runs on device (`v6.12.0-beta.3`). Whether `./gradlew :gmessages:testDebugUnitTest` is green after today's edits was **not confirmed here** — it cannot be run remotely (§4.3). **Run it first thing.** There are 91 tests across 9 files; the ones most likely to be stale are the 18 in `GMCookieRotationLinkTest` and the 4 in `GMSessionStreamTest`, both new today.

### 15.6 Dangling doc reference

`GMESSAGES_SEAMLESS_LINK_DESIGN_20260817.md` is cited by `GoogleMessagesAccountStore.kt` and does not exist (§4.4). The claim it supports concerns session expiry and cannot currently be verified.

---

## 16. Risks

### 16.1 Device Bound Session Credentials (DBSC) — existential, long-term

Google is rolling out DBSC, which binds session cookies to a TPM-held key in the originating browser. If it reaches the accounts used here, **exporting a cookie jar to another device stops working at all** — not degraded, gone. There is no mitigation inside this architecture; it would require a different approach to establishing a session. Worth monitoring, not worth pre-building for.

### 16.2 Protocol drift

Every endpoint, action code, and encoding is undocumented and unversioned. A silent change on Google's side breaks us with no notice. The mitigation is what today's work was about: fail loudly and legibly, so a break is a one-log diagnosis instead of a two-week mystery.

### 16.3 The support-log channel is the only telemetry

No crash reporting, no metrics. Every diagnosis depends on a customer noticing, caring enough to submit, and the relevant window still being in the rolling buffer. This caps how fast anything can be found. **A real consideration for the next project: even minimal aggregate telemetry — "did `freshness:` fail", "did `stream[quiet]` exceed 60 s" — would change the economics of this work completely.**

### 16.4 Unrelated but live: the `__CALL_HISTORY__` crash

Found while reviewing a healthy customer's log; **nothing to do with Google Messages**, but it is a real user-facing crash and someone should own it.

```
java.lang.IllegalArgumentException: Key "__CALL_HISTORY__" was already used.
If you are using LazyColumn/Row please make sure you provide a unique key for each item.
```

**MEASURED:** 10 distinct crashes on customer …1833's device on 19 Aug between 16:17 and 16:48 — clustered, so they were almost certainly opening the same screen repeatedly and it died every time. Customer …9307: zero.

The keyed list is `launcher/.../ui/AppListScreen.kt:335` — `key = { _, item -> item.packageName }` — which throws the instant the list contains the synthetic `__CALL_HISTORY__` token twice. Two candidate sources, **neither confirmed**: `MainAppsGridActivity.migrateLayoutTokens`, which maps `com.android.dialer` → `CALL_HISTORY` and would emit it twice if a saved layout holds *both* tokens; and the unconditional `appItems.add(CALL_HISTORY)` at `AllAppsActivity.kt:225`. Confirming it means inspecting that user's stored `launcher_prefs`.

---

## 17. Test suite

`gmessages/src/test/kotlin/com/offline/dpadmessenger/backend/gmessages/` — **91 tests, 9 files, all plain JVM, no device, no network.**

| Tests | File | Covers |
|---:|---|---|
| 20 | `GMCookieRotationTest.kt` | Interval parsing, floors, clamps, the `hfcr=never` sentinel, persistence |
| 18 | `GMCookieRotationLinkTest.kt` | **The link-time bootstrap.** Fake Google as an OkHttp interceptor |
| 17 | `GMSessionProtoTest.kt` | Session protobuf encode/decode |
| 15 | `PbLiteTest.kt` | pblite codec |
| 6 | `X25519Test.kt` | Curve25519 |
| 5 | `GMCryptoSessionTest.kt` | Session crypto |
| 4 | `GMSessionStreamTest.kt` | **Stream thresholds:** ordering, keepalive multiple, override default |
| 4 | `GMPairingProtoTest.kt` | Pairing constants/encoding |
| 2 | `GMGcmTest.kt` | GCM |

### 17.1 What makes `GMCookieRotationLinkTest` worth reading first

It is the best single document of the reasoning behind Bug A. Design points worth preserving:

- **Google is an OkHttp `Interceptor`,** so no socket opens and every failure shape is scriptable — including the 200-with-empty-`Set-Cookie` we cannot provoke on a real device.
- **Every assertion is against `holdsFreshnessCookie(cookies)`,** never against the bootstrap's return value. Conflating those two *is* the original defect, so the tests refuse to conflate them.
- **A negative control:** `the pre-fix single-shot bootstrap fails on the identical 429`. If that test ever starts passing, the retry has stopped being what rescues a rate-limited link, and the positive test proves nothing.
- **The 401 test is deliberately generous:** the script's *second* reply would mint successfully. If the retry ever comes back, the cookie appears and the test fails — which is the point. A rejection must not be waited out even when waiting would have worked.
- **A whitelist, not a blacklist:** there is a test asserting a **500 still retries**, so the no-retry guard can't drift into "anything that isn't a 429."
- **Sticky-state test:** a 401 recorded in one link must not suppress the retry in the next one (`reset()` clears `lastHttpCode`). `GMCookieRotation` is a process-wide object and this is a real hazard.
- **A floor-invariant test:** `LINK_RETRY_DELAY_MS > 60_000`, so nobody trims the retry into a no-op that still costs the user the wait.
- **No log assertions:** `isReturnDefaultValues = true` stubs `android.util.Log` to a no-op, so wording cannot be checked. Behaviour, cookie state, call counts and backoffs can be, and are.

---

## 18. Working practices that actually paid off

Offered because they were expensive to learn and they are what made today productive.

1. **Ask about the state, not about the delta.** `changed == false` was both healthy and fatal. `cookie == null` is unambiguous. When a check is ambiguous, no amount of care downstream saves you.
2. **Instrument the thing that can fail, from somewhere that cannot.** The `alive:` line was useless during a wedge because it was printed by the wedged loop. Moving it was a two-line change that converts a future silent failure into a one-line diagnosis.
3. **Size a timeout from the signal you're actually watching.** 25 minutes came from stream *lifetimes* because keepalives weren't logged. The right input was 9–10 s. Log the signal first, then choose the number.
4. **Make sure a measurement can't span a discontinuity.** The first keepalive numbers were polluted by cross-stream reconnect backoff. Per-stream reset fixed it.
5. **Never write a hypothesis as a fact in a comment.** Three sites, one of which asserted the "leading hypothesis" for a bug that turned out to be something else entirely. A wrong comment is worse than no comment because it is trusted.
6. **A negative control is worth as much as the test.** Without `the pre-fix single-shot bootstrap fails on the identical 429`, nothing proves the retry is what does the work.
7. **Distinguish "no reports" from "no problem."** Two customers reported no issues, but their `last inbound` counters routinely climb to 10–13 hours overnight. A multi-hour receive outage would be invisible to them. Their clean log is weak evidence; Alex, who noticed within hours, is strong evidence.
8. **When the user says a claim of yours smells wrong, check it before defending it.** Both times Jack pushed back, he was right.

---

## 19. Where things stand, per actor

| Actor | Version | State | Next |
|---|---|---|---|
| Jack (dev device) | beta.3 | Clean. 3 pairings, all first-try mints; keepalives steady 9–10 s; one clean stream rollover at 16:14:28 | Overnight screen-off capture (§14.2) |
| Alex Browning (…3562, `stifrontman@gmail.com`) | pre-fix | Last seen wedged at 13:19, 19 Aug. Has reproduced **both** bugs, one per capture | Ship, 24 h use, resubmit either way |
| Customer …1833 | beta.1 | Google Messages healthy (135 stream cycles, 118 rotations, zero failures). **Separately crashing on call history** (§16.4) | Update; also fix the crash |
| Customer …9307 (`+16152439307`) | beta.1 | Healthy (127 cycles, 153 rotations, zero failures, zero crashes) | Update |

---

## 20. Suggested first moves for the new project

Ordered by value per unit of effort.

**1. Run the unit tests.** `./gradlew :gmessages:testDebugUnitTest` on the mac mini. 91 tests; 22 of them are new today and have never been through the runner in a verified way. Nothing else should happen first. (§15.5)

**2. Overnight screen-off capture.** One night, charger, screen off, then read the `stream[quiet Ns]` values. This is the single measurement that most reduces uncertainty in the system right now, and it decides whether `STREAM_READ_DEADLINE_MS` stays at 5 minutes, tightens to 60–90 s, or has to become Doze-aware. (§14.2)

**3. Get Alex's 24-hour log.** Draft email exists (§20.1). When it lands, the three lines that matter are in §12.2 and §12.5: `stream[quiet Ns]`, any stale-stream/reconnect line (which is a *success* even though it reads like an error), and `freshness:`.

**4. Add the harvest shelf-life gate.** ~20 lines. `ageOfHarvest` is already logged; make it a gate at ~10 minutes with an honest message. Removes a whole class of confusing 401 at link time. (§15.1)

**5. Fix the reauth double-`bootstrapNow`.** Small, and it removes a misleading log line. (§15.2)

**6. Settle the `NID` question** with one controlled experiment — both harvest shapes from the same extension version, minutes apart. Either it becomes a real finding or it stops occupying attention. (§14.4)

**7. Consider minimal telemetry.** Two counters — `freshness:` failures, and `stream[quiet]` exceeding a threshold — reported in aggregate would change how fast every future bug in this area gets found. Currently everything waits on a customer noticing. (§16.3)

**8. Write the missing design doc, or delete the reference.** (§15.6)

**9. Hand off the `__CALL_HISTORY__` crash** to whoever owns the launcher UI. It is unrelated to this project but it is live and reproducible for at least one customer. (§16.4)

### 20.1 Draft customer email (approved wording, ready to send)

> **Subject:** Fixed the messaging issue — one favor
>
> Hi Alex,
>
> Your logs pinned down two separate problems, and both are fixed in the update going out now.
>
> The first was a bad link: the phone reported it was connected to Google when it wasn't, so it quietly stopped working a few minutes later. The second is the one you described this morning — the connection your phone uses to *receive* messages went silent around 11:24, and the phone had no way to notice it had gone dead, so it sat there waiting. Sending kept working the whole time, which is why everything looked fine from your end.
>
> Please take the update when it prompts you. You shouldn't need to sign in again.
>
> Then the favor: use the phone normally for 24 hours and submit a log **either way** — working or broken. A log from a good day tells me as much as a bad one, because it shows the fix held.
>
> If you can, leave the phone sitting untouched for a few hours (and overnight), then have someone text you before you pick it up. That's the exact condition that broke it, so it's the most useful test there is.
>
> Thanks — this was a hard one to find and your logs are what found it.
>
> Jack

---

## 21. Glossary

| Term | Meaning |
|---|---|
| **GAIA** | Google's account/identity system. "GAIA pairing" = pairing via a signed-in Google account rather than a QR code. |
| **GDitto** | The network name for Google-account mode. `Bugle` is the QR-mode equivalent. |
| **UKey2** | Google's key-agreement protocol. Produces the emoji both devices display so the user can confirm they match. Actions 44 (CLIENT_INIT) and 45 (CLIENT_FINISH). |
| **pblite** | `application/json+protobuf` — protobuf encoded as nested JSON arrays. `PbLite.kt`. |
| **jspb** | The JS protobuf flavour whose sentinel body `RotateCookies` expects. |
| **tachyon token** | The 24-hour bearer token from `SignInGaia`. Refreshed via `RegisterRefresh`. |
| **destReg / destRegistrationId** | The customer's smartphone, as addressed on the relay. |
| **harvest** | The cookie blob the browser extension exports. Normally 14 cookies. |
| **mint** | Getting Google to issue `__Secure-1PSIDTS` for the first time on this device, via `RotateCookies`. |
| **freshness cookie** | `__Secure-1PSIDTS`. |
| **hfcr** | `identity.hfcr` — the field in the rotate response carrying the next interval in seconds. `2147483647` = "never." |
| **long-poll / stream** | The `ReceiveMessages` HTTP response held open to receive inbound messages. |
| **wedge** | The Bug B failure: a stream that is nominally open but produces nothing, forever. |
| **the relay** | Offline's Heroku WebSocket ("Type Sync relay"), which carries the encrypted cookie blob from computer to flip phone. |
| **rung 1/2/3** | The three recovery levels of §5.5. |
| **DBSC** | Device Bound Session Credentials. §16.1. |

---

## 22. Appendix A — grep cheat sheet

```bash
# ---- is this device's link fundamentally sound? ----
grep -E "has1PSIDTS=(true|false)" | tail -5          # 16 cookies + true = sound
grep -E "freshness:"                                  # OK / FAILED, and why
grep -E "result=(bootstrapped|bootstrap-empty|429|http[0-9]+)"

# ---- is the receive path alive? ----
grep "alive:" | tail -20                              # read stream[quiet Ns]
grep -c "stream heartbeat"                            # should be ~6/min while up
grep "session long-poll #"                            # cadence should be ~15 min
grep -E "STREAM DEADLINE OVERRIDE|stale"              # watchdog / debug flag

# ---- pairing forensics ----
grep -E "GMPairResult|PAIRING EMOJI|GAIA PAIRING COMPLETE"
grep -E "saved [0-9]+ gmessages cookies"              # harvest size + fp
grep -E "ageOfHarvest"                                # stale-blob tell

# ---- is the app crashing? ----
grep -A4 "FATAL EXCEPTION" | grep -E "Process:|Exception|Error"
# NB: DeadSystemException = the whole device died, not us

# ---- stream lifetime distribution (the §13 baseline method) ----
grep "session long-poll #" | awk '{print $1, $2, $NF}'   # then diff timestamps per pid
```

### Version identification

```bash
grep "reportVersion"        # e.g. version=v6.12.0-beta.1
```

Or by capability — a log with **no** `stream heartbeat`, **no** `stream[quiet`, **no** `freshness:` and **no** `bootstrapForLink` lines is **pre-fix**, regardless of what version string it carries. That check is how the two healthy customers were identified as beta.1 without relying on the version line.

---

## 23. Appendix B — incident timelines, verbatim

### B.1 Alex, Bug A — 19 Aug, capture `20260819122500Z2`

```
06:40:58.265  🔓 saved 15 gmessages cookies; names=[APISID, HSID, NID, OSID, SAPISID,
              SID, SIDCC, SSID, __Secure-1PAPISID, __Secure-1PSID, __Secure-1PSIDCC,
              __Secure-3PAPISID, __Secure-3PSID, __Secure-3PSIDCC, __Secure-OSID]
06:41:13.942  bootstrapNow: requested (mint=true cookies=15)
06:41:14.516  rotate BOOTSTRAP OK: accepted=[] changed=false nextIn=600s
                                   result=bootstrap-empty
06:41:14.517  RESULT attempt=790f2d13… paired=true stage=CLIENT_FINISHED lastHttp=200
06:41:14.525  ATTEMPT #1 result=PAIRED config=403 signIn=200 elapsed=16.0s cookieAge=16s
06:54:18.074  alive: …                                    ← last alive on this session
06:54:33.979  bootstrapNow: requested (mint=true cookies=15)
06:54:34.425  rotate BOOTSTRAP OK: accepted=[] changed=false result=bootstrap-empty
08:19:36.975  bootstrapNow: requested (mint=true cookies=15)
08:19:37.591  rotate BOOTSTRAP OK: accepted=[] changed=false result=bootstrap-empty
08:19:38.147  bootstrapNow: skipped — last attempt 1s ago (floor 60s)
08:20:34.263  🔓 saved 14 gmessages cookies; names=[…no NID…]
08:20:42.072  bootstrapNow: requested (mint=true cookies=14)
08:20:42.580  rotate BOOTSTRAP OK: accepted=[__Secure-1PSIDTS, __Secure-3PSIDTS]
                                   changed=true result=bootstrapped
08:20:42.614  ATTEMPT #2 result=PAIRED config=403 signIn=200 elapsed=8.0s cookieAge=0s
```

### B.2 Alex, Bug B — 19 Aug, capture `20260819171907Z1` (same device, later)

```
09:14:36  rotate OK … result=rotated                  ← the 08:20 session, healthy
09:20:50  setActiveSession re-asserted OK [up 1h0m]
…         rotation every ~10 min, re-assert every 30 min, alive every ~11–20 min
11:24:22.046  alive: up 3h3m … inbound[last inbound 12m ago] rot[nextDueIn=412s]
11:24:27.398  refreshTokenIfNeeded: 1256min to expiry — skipping
11:24:27.650  Set-Cookie on …/ReceiveMessages: SIDCC, __Secure-1PSIDCC, __Secure-3PSIDCC
11:24:27.661  session long-poll #14 open
11:24:27.663  startup ack count=0
              ══ NOTHING from the stream, and no alive line, for the rest of the capture ══
11:43:44.841  session cookie rotated; n=16 has1PSIDTS=true …
11:51:15.102  re-asserting active session [up 3h30m, last inbound 39m ago]
11:52:19.758  setActiveSession re-asserted OK [up 3h31m]
12:06:38.966  session cookie rotated; n=16 …
12:18:19.343  session cookie rotated; n=16 …
12:21:16.218  re-asserting active session [up 4h0m, last inbound 69m ago]
12:22:27.012  setActiveSession re-asserted OK [up 4h1m]
12:52:54.588  setActiveSession re-asserted OK [up 4h32m]
13:10:04.625  rotate OK … result=rotated
13:19:03.576  ── capture ends, still wedged ──
```

### B.3 Jack, the 401 — 19 Aug

```
14:48:09      (earlier) harvest fp=a8fe5420 mints successfully
15:39:24.270  🔓 saved 14 gmessages cookies; … fp=a8fe5420    ← same blob, 51 min old
15:39:25.200  rotate BOOTSTRAP HTTP 401 (39B) — cookies untouched:
                 )]}' [["identity.hfcr",600],["di",22]]
15:39:25.213  bootstrapForLink: nothing minted (result=bootstrap-http401) — waiting 70s
15:40:35.306  rotate BOOTSTRAP HTTP 401
15:40:35.323  freshness: FAILED … ATTEMPT #1 result=FAILED … elapsed=70.7s
15:43:10      🔓 saved 14 … fp=88037b55   → minted in 400ms
              → ATTEMPT #2 result=PAIRED elapsed=10.7s
```

### B.4 Jack, post-fix healthy run — 19 Aug beta.3

```
15:56:47.746  🔓 saved 14 gmessages cookies; … fp=95144c46
15:56:48.335  bootstrapNow: requested (mint=true cookies=14)
15:56:48.838  rotate BOOTSTRAP OK: accepted=[__Secure-1PSIDTS, __Secure-3PSIDTS]
                                   changed=true nextIn=600s result=bootstrapped
15:56:48.871  freshness: OK — 16 cookies with __Secure-1PSIDTS in hand before SignInGaia
15:56:49.555  fetchConfig HTTP 403 (1654 bytes)                    ← benign
15:56:50.445  signInGaia HTTP 200 (10468 bytes)
15:56:52.690  ================ PAIRING EMOJI: 🥱 ================
15:56:56.166  ================ GAIA PAIRING COMPLETE — account saved ================
15:56:56.175  ATTEMPT #1 result=PAIRED config=403 signIn=200 elapsed=8.1s cookieAge=7s
15:57:35.224  stream heartbeat — first on this stream
15:57:45.177  stream heartbeat — 9s since previous
…             steady 9–10s
16:14:28.310  session long-poll #2 open              ← clean rollover after 16m53s
16:14:28.313  stream heartbeat — first on this stream
16:18:37.358  alive: up 21m … inbound[last inbound 13m ago] stream[quiet 9s]
                    rot[last=rotated nextDueIn=539s]
16:19:41.347  [relaydiag] waiting… 0s elapsed; relayConnected=true signingIn=false
                    securingWaitMs=0 emoji=false      ← merged log line, live
16:20:41.397  [relaydiag] wait ended after 60s: success=true
```

---

## 24. Appendix C — questions this document deliberately does not answer

Listed so a new session doesn't mistake absence for oversight.

- **How the browser extension works internally.** Out of scope here; it is a separate codebase. What matters at this boundary is: it produces a 14-cookie blob, it does not include the freshness pair, and the blob goes stale (§15.1).
- **How the Type Sync relay is implemented.** Pre-existing infrastructure. Treated as a reliable encrypted pipe; `[relaydiag]` lines are the interface for debugging it.
- **The message/room data model, dedup, and echo reconciliation.** 1547 lines in `GoogleMessagesMessageRepository.kt`, largely stable, and documented separately in `matrix-app/GMESSAGES_DOUBLE_BUBBLE_FIX.md`. Not touched today.
- **Media upload/download.** Implemented (`uploadEncryptedMedia`, `downloadMedia`), untouched today, no known issues.
- **The chat UI and D-pad interaction.** A different problem domain with its own history in `launcher/GMESSAGES_STATUS.md` §166+.
- **Whether the two-week Google session ceiling is really two weeks.** Cited by a comment whose source document is missing (§15.6). Treated as INFERRED.

---

*End of brief. If you extend this document, keep the MEASURED / INFERRED / UNKNOWN discipline — it is the main thing that stopped today from going in circles.*
