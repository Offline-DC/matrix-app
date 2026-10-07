# Signal launcher integration — working status & handoff

> Companion to [`SIGNAL_BRIDGE.md`](./SIGNAL_BRIDGE.md) (the protocol/architecture
> reference). This file is the **running development log** for getting Signal
> usable inside the dumb-down launcher on the Flip 2 — what was fixed, what's
> verified on-device, and what's still open. Start here to pick up where we left
> off.

Last updated: June 2026.

---

## TL;DR — where we are

Linking, receiving, sending to **already-known** contacts, unlink handling, and
the contact picker all work on-device. **First-contact delivery to a PNI-only
cold contact now works** — verified on-device: CDSI returns a real 2-device PNI
bundle, the type-6 sealed-sender send returns HTTP 200, and the recipient
received it and replied. The remaining issue is cosmetic-but-important:
**PNI/ACI thread splitting** — sends keyed by PNI land in one thread while the
ACI reply opens a second. See [Thread splitting](#pniaci-thread-splitting).

---

## What got fixed this round (all on-device unless noted)

### 1. Linking (provisioning) — FIXED
- **Symptom:** scanning the QR on the primary phone showed a "network error" on
  the primary; the launcher logged nothing on scan.
- **Cause:** the provisioning WebSocket URL carried a non-standard
  `?agent=DPADMSG` query param. The server issued an address (so the QR rendered)
  but **refused to route the primary's provision message** to a socket tagged
  with an unknown agent.
- **Fix:** `SignalProvisioningClient.SIGNAL_PROVISIONING_URL` is now plain
  `wss://chat.signal.org/v1/websocket/provisioning/` (no agent param). Added
  socket lifecycle + frame logging (`onOpen`/`onMessage`/`onFailure`/`onClosed`
  and a per-request line in `handleBinaryFrame`), tag `SignalProvisioning`.
- **Result:** links end-to-end; device shows up as deviceId=2 in the primary's
  Linked Devices.

### 2. Receive socket 403 loop — FIXED
- **Symptom:** `SignalChatWS` logged `403 Forbidden` on the upgrade and
  reconnected every 5s forever (full stack trace each time). Receive was dead.
- **Cause:** same `agent=DPADMSG` problem — the chat URL had `&agent=DPADMSG`
  plus an `X-Signal-Agent: DPADMSG` header.
- **Fix (`SignalChatWebSocket`):**
  - URL is now `…/v1/websocket/?login=…&password=…` (no agent param/header).
  - Reconnect uses **exponential backoff** (5s → cap 60s), reset on a healthy
    open.
  - A **401/403** is treated as terminal after `AUTH_FAILURE_LIMIT = 3`
    consecutive failures: stops reconnecting and calls
    `repository.markAuthExpired()` (surfaces the re-link prompt) instead of
    looping. `@Volatile stopped`/`reconnectAttempts`/`authFailures` track state.

### 3. Unlink / auth-expired handling — FIXED
- **Symptom:** after unlinking from the primary, sends still showed a "sent"
  check; every send actually 401'd.
- **Causes & fixes:**
  - **Group send swallowed failures.** `fanOutGroup` logged "0/N delivered" but
    `sendGroupDataMessage` returned success → false ✓. Now `fanOutGroup`
    **rethrows when zero members were reached** (partial delivery still counts
    as sent), preferring the auth exception.
  - New `SignalAuthException` thrown on HTTP 401 in
    `SignalSender.encryptAndSendContent`.
  - `SignalMessageRepository` exposes `authExpired: StateFlow<Boolean>` +
    `markAuthExpired()`; both send `catch` blocks flip it on
    `SignalAuthException` and mark the bubble FAILED.
  - `SignalRepository.authExpiredFlow()` surfaces it to the app layer.
  - New `ui/SignalReconnectScreen` + `SignalApp` gate: when `authExpired`, the
    chat is replaced by a "Disconnected from Signal — Log out & re-link" screen.
    Re-link does a full logout (shutdown, clear account, **wipe cached
    messages**, reset pairing → QR).

### 4. New-message contact picker was empty — FIXED
- **Cause:** `listContacts()` read only `contactsByServiceId` (filled by contact
  sync, which is intentionally disabled), so a fresh link showed nothing.
- **Fix:** new `SignalLocalContacts` reads the device address book
  (`ContactsContract`), merged into `listContacts()` (de-duped by last-7-digits).
  Added `READ_CONTACTS` to the `:signal` manifest; `appContext` threaded into the
  repo via both factories.

### 5. CDSI contact discovery — IMPLEMENTED (delivery unverified)
Lets you start a chat with a number you've never messaged. **The safe, official
method** — same attested-enclave discovery the real Signal app uses (handled
inside libsignal's native `net` stack).
- `SignalApi.getCdsiAuth()` → `GET /v2/directory/auth` (`CdsiAuthResponse`).
- `SignalContactDiscovery` wraps `org.signal.libsignal.net.Network.cdsiLookup`;
  normalizes to E.164 (NANP heuristics), caches hits + misses.
- `startConversation` falls back to CDSI when a number isn't already known.
- **API verified against libsignal-android 0.86.5 source** (this bit is
  version-sensitive):
  - `CdsiLookupRequest(previousE164s, newE164s, serviceIds, Optional<byte[]> token)`
    — 4 args, `previousE164s` first, **no** `returnAcisWithoutUaks` boolean.
  - `network.cdsiLookup(user, pass, request, Consumer<byte[]> tokenConsumer)` →
    `CompletableFuture<CdsiLookupResponse>`.
  - `CdsiLookupResponse.entries(): Map<String, Entry>`, keyed by `"+E164"`;
    `Entry.aci`/`Entry.pni` are `ServiceId.Aci?`/`ServiceId.Pni?`.
  - Use `getRawUUID().toString()` for the bare UUID — `ServiceId.toString()`
    returns a **redacted** log string, not the id.

### 6. PNI-only contacts (name + honest send) — PARTIAL
- **On-device truth:** CDSI returns cold contacts as **PNI-only**
  (`aci=null pni=PNI:<uuid>`). This is by design — Signal won't hand out a
  stranger's ACI from a cold lookup.
- **Done:**
  - Discovery falls back to the **PNI service-id** when no ACI.
  - `startConversation` resolves the **address-book name** for the number
    (`localNameForNumber`) so the chat shows the saved name, not the digits, and
    persists it via `updateContact`.
  - **Empty-bundle guard:** `encryptAndSendContent` throws if the recipient
    prekey bundle has zero devices (a PNI with no fetchable prekeys), so a no-op
    send no longer shows a false "sent". Added a `sending to … — N device
    message(s)` log.

---

## PNI first-contact delivery — RESOLVED (verified on-device)

A cold send to `+15555550301` (CDSI: `aci=null pni=PNI:e20ffa63…`) went through
end-to-end. Decisive log lines:
```
refreshed prekey bundle for PNI:e20ffa63… (2 device(s))
sending to PNI:e20ffa63… — 2 device message(s)
PUT …/v1/messages/PNI:e20ffa63… → HTTP 200: {"needsSync":true}
sent-transcript delivered to 3 sibling device(s)
```
The PNI returned a **real 2-device prekey bundle** (so the feared "N=0, no
fetchable prekeys" branch didn't happen), the existing **type-6 sealed-sender**
path was accepted (no authenticated `SessionCipher` fallback needed after all),
and the recipient **received the message and replied** — full confirmation.

> The authenticated-send fallback (`WHISPER_TYPE→1` / `PREKEY_TYPE→3`,
> `CIPHERTEXT_TYPE_*` in `SignalSender`) was the contingency plan if this had
> been a 4xx. It turned out unnecessary; keep it noted in case a different
> recipient with no PNI prekeys ever forces the issue.

---

## PNI/ACI thread splitting

Once delivery worked, the next on-device symptom: outgoing messages to a cold
contact sit in a thread keyed by **PNI** (shows as the bare number, e.g.
`2489046456`), while that person's **reply** arrives under their **ACI** and
opens a *second* thread (under their saved name, e.g. `jack android`). Same
human, two chats — because DM rooms are keyed by service-id (`sig:dm:<serviceId>`)
and PNI ≠ ACI, with no upgrade step.

**Fix applied (forward resolution, no back-migration):** `receiveIncoming` now
records `numberToServiceId[senderE164] = senderACI` from the reply. So the next
time you type/search that number, `startConversation → resolveServiceIdForNumber`
returns the **ACI** and opens the same room the replies land in, instead of
re-running CDSI → PNI and splitting again. Existing split PNI threads are **not**
migrated — during dev they're cleared with `pm clear`.

> Heavier alternative (deferred): merge-on-receive (move the PNI room's messages
> into the ACI room when the reply arrives) and/or re-key DM rooms by E.164 with
> PNI/ACI as upgradeable routing attributes. Matches how real Signal upgrades
> PNI→ACI on first reply. Not needed while data is disposable.

---

## Files touched this round

| File | Change |
|------|--------|
| `signal/.../SignalProvisioningClient.kt` | drop `agent` param; lifecycle/frame logging |
| `signal/.../SignalChatWebSocket.kt` | drop `agent`; backoff; terminal 401/403 → `markAuthExpired` |
| `signal/.../SignalSender.kt` | `SignalAuthException`; `fanOutGroup` rethrow-on-0; empty-bundle guard; send log |
| `signal/.../SignalMessageRepository.kt` | `authExpired` flow + `markAuthExpired`; local-contacts merge; CDSI fallback in `startConversation`; `localNameForNumber`; `appContext`/`discovery` ctor params |
| `signal/.../SignalRepository.kt` | construct `SignalContactDiscovery`; `authExpiredFlow()` |
| `signal/.../SignalBackendFactory.kt` | pass `appContext` + `discovery` |
| `signal/.../SignalApi.kt` | `getCdsiAuth()` + `CdsiAuthResponse` |
| `signal/.../SignalContactDiscovery.kt` | **new** — CDSI wrapper (libsignal net) |
| `signal/.../SignalLocalContacts.kt` | **new** — device address book reader |
| `signal/.../ui/SignalReconnectScreen.kt` | **new** — re-link prompt |
| `signal/.../ui/SignalApp.kt` | observe `authExpired`; reconnect gate; shared `logout` |
| `signal/src/main/AndroidManifest.xml` | add `READ_CONTACTS` |

> ⚠️ **None of this has been compiled this round** — the Android build runs on
> the dev Mac, not in the editing environment. First real verification is
> `./gradlew :app:installDebug`. Watch especially the version-sensitive CDSI
> code in `SignalContactDiscovery`.

---

## Other known gaps (pre-existing, not addressed this round)

- **Groups don't work** until `SignalGroups.SERVER_PUBLIC_PARAMS_B64` is filled
  with Signal's real production zkgroup params (deliberately blank; fails loud).
- Signal doesn't implement `GroupConversationStarter` (DPAD group create) or
  `InitialSyncAware` (sync spinner) that gmessages has.
- **Media send** needs live-CDN device verification.
- Nice-to-haves: periodic one-time prekey top-up; stale-device 409/410 retry;
  persisted SenderKey distribution ids.

---

## Hygiene before "done"

Debug logging added this round prints sensitive data to logcat and should be
trimmed (the module had a deliberate pass to strip this):
- `SignalCDSI` prints **phone numbers** and the CDSI key set.
- `SignalProvisioning` prints inbound frame metadata.

---

## Bundle size — why Signal adds ~68 MB

Measured from the built APK: `lib/armeabi-v7a/libsignal_jni.so` is **67.8 MB**,
stored **uncompressed**, and is essentially the entire size increase.

- **Google Messages added ~nothing** because that backend is pure Kotlin (libsignal
  was dropped; X25519 is an in-repo TweetNaCl port; protobuf is hand-rolled).
- **Signal needs the native lib:** `libsignal_jni.so` is compiled Rust holding
  the whole protocol (Double Ratchet, X3DH/PQXDH, sealed sender, zkgroup) **plus
  the `net`/CDSI stack** (Noise, enclave attestation, embedded Tokio runtime,
  TLS). It's **monolithic** — same size whether or not CDSI/groups are used — and
  R8/ProGuard can't shrink native code.
- Already mitigated: the `:signal` module pins `abiFilters` to **armeabi-v7a
  only** (the APK ships one ABI, not ~4× that).

### "Download the 68 MB once" — options
The launcher already has `update/SplitApkInstaller` (PackageInstaller split
sessions), which makes this very reachable:

- **Option A (recommended): libsignal in its own split APK.** Build as an App
  Bundle / ABI split so native libs land in a `config.armeabi_v7a.apk`. On
  update, ship only the changed `base.apk` and install with a
  **`MODE_INHERIT_EXISTING`** session so the existing 68 MB split is **retained,
  not re-downloaded**. Re-ship the big split only when bumping the libsignal
  version. (~15 lines of change in `SplitApkInstaller`.)
- **Option B: delta/binary-patch updates** (bsdiff / archive-patcher). Works well
  precisely because the `.so` is stored uncompressed — an unchanged region diffs
  to ~0. More moving parts (server-side patch gen + client apply).

> Note: this goal is **at odds with compressing the `.so`** (`useLegacyPackaging`).
> Keep it uncompressed/Stored — compression defeats delta patches and split reuse.
> So it's "smaller one-time APK (compression)" **or** "download-once + tiny
> updates (splits/patches)". Download-once is the better deal.

---

## Quick test/logcat reference

```bash
# Build + install
./gradlew :app:installDebug
./gradlew :gmessages:testDebugUnitTest   # unrelated but a quick green check

# Link flow
adb logcat -s SignalProvisioning          # expect socket onOpen → /v1/address → /v1/message → device registered

# Receive socket
adb logcat -s SignalChatWS                # expect "chat socket OPEN (HTTP 101)", no 403 spam

# Contact discovery + send (the open blocker)
adb logcat -s SignalCDSI SignalSender SignalApi

# Re-test pairing from scratch
#   unpair on the primary phone, then:
adb shell pm clear com.offlineinc.dumbdownlauncher
```
