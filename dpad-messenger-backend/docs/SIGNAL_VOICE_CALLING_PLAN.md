# Signal voice calling — implementation plan

Goal: add **1:1 audio calls** to the Signal backend (`:signal`, direct mode)
so the linked device on the TCL Flip 2 can place and receive Signal voice
calls, using the same libsignal identity/session state that already carries
its messages.

Scope for this plan (as agreed):

- **Signal direct mode only.** Not the Signal↔Matrix bridge, not the Matrix
  backend. (Matrix has no native VoIP in `matrix-rust-sdk` — see the note at
  the end.)
- **Audio only.** No video. RingRTC and the `CallMessage.Offer` wire type both
  distinguish `OFFER_AUDIO_CALL` from `OFFER_VIDEO_CALL`; we only ever send/
  accept the audio type. A "video later" seam is called out but not built.
- **1:1 calls only.** No group/adhoc calls, no call links.

> Companion to [`SIGNAL_BRIDGE.md`](./SIGNAL_BRIDGE.md) (protocol/architecture)
> and [`SIGNAL_LAUNCHER_STATUS.md`](./SIGNAL_LAUNCHER_STATUS.md) (on-device dev
> log). This is the design/plan doc; the launcher-side running log will live in
> `SIGNAL_LAUNCHER_STATUS.md` once work starts.

---

## TL;DR — feasibility verdict

**Feasible, but it is a genuine new subsystem — not a "fill in the TODOs" job
like the rest of Signal messaging was.** Three things make it tractable:

1. **The signaling wire format already exists in-repo.** `CallMessage`
   (Offer / Answer / IceUpdate / Busy / Hangup, all carrying `opaque` bytes)
   is already in `signal/src/main/proto/SignalService.proto` and already rides
   inside `Content` (field 3). We are *not* inventing a protocol; we are
   ferrying opaque blobs through the send/receive paths we already have.
2. **The calling engine is a drop-in library.** `org.signal:ringrtc-android`
   (the exact engine Signal-Android uses) bundles a WebRTC fork and exposes a
   `CallManager` whose `Observer.onSend*` callbacks hand us the very `opaque`
   blobs those `CallMessage` fields want. Its Android build ships an
   **armeabi-v7a** slice, so it runs on the Flip 2.
3. **Linked devices are allowed to call.** Signal added multi-device calling
   ("ICE forking") years ago; Desktop and iPad — both *linked* devices — place
   and receive calls today. Our device is deviceId=2, i.e. one tine of that
   fork. So there is no protocol-level wall the way there historically was.

The real work is everything around the engine: audio routing, a foreground
"in-call" service, ICE/TURN relay credentials, an incoming-call UX that works
on a D-pad flip phone, and multi-device (ICE-forking) send semantics. Estimate
below: **~3–4 focused weeks**, most of it on-device, none of it verifiable in
CI (same caveat as the rest of `:signal`).

Two things to accept up front, both already true for messaging so neither is
new: **AGPL-3.0** (RingRTC is AGPL, exactly like `libsignal-android` we already
ship) and **3rd-party-client ToS risk** (Signal forbids non-official clients;
calling is a louder, more fingerprintable surface than messaging).

---

## Current state

### What exists and helps us

| Piece | Where | Note |
|---|---|---|
| `CallMessage` proto (Offer/Answer/IceUpdate/Busy/Hangup, `opaque`) | `signal/src/main/proto/SignalService.proto` (~L123) | Already generated into `SignalServiceProtos`. `callMessage = 3` inside `Content`. |
| Authenticated chat WebSocket (inbound) | `SignalChatWebSocket.kt` | `handleIncomingMessage` decrypts an envelope → `Content.parseFrom` → dispatches on `hasDataMessage`/`hasSyncMessage`/`hasReceiptMessage`. **CallMessage falls through today** (logged as "no recognized payload"). |
| Per-recipient encrypt + send | `SignalSender.kt::encryptAndSendContent` | Builds `Content`, fans out one sealed-sender `OutgoingMessage` per device, `PUT /v1/messages/<aci>`. Reused verbatim for call signaling. |
| Linked account + creds | `SignalAccount` / `SignalAccountStore` | `aci`, `pni`, `deviceId` (=2), `password`, identity keys. Everything RingRTC needs to identify "self". |
| Protocol store / sessions | `AndroidSignalProtocolStore` | Sessions with the peer already exist once you've messaged them; call signaling encrypts through the same sessions. |
| Repo → app seams | `SignalMessageRepository`, `SignalRepository.createIfPaired` | Single construction site to wire a `SignalCallManager` into both the socket (inbound) and the repo (UI actions). |

### What's missing (the whole feature)

- No calling engine dependency (`ringrtc-android`) and no WebRTC.
- No `CallMessage` **send** path (no `SignalSender.sendCallMessage`).
- No `CallMessage` **receive** routing (the `hasCallMessage()` branch).
- No audio capture/playback wiring, audio focus, or output routing.
- No ICE/TURN relay credentials fetch.
- No in-call foreground service / call notification / ringtone.
- No call UI (incoming / outgoing / in-call), and the UI `MessageRepository`
  contract has no call surface.
- Launcher: no `RECORD_AUDIO`, no `FOREGROUND_SERVICE_MICROPHONE`, no
  `MANAGE_OWN_CALLS`, no call Activity/hardware-key handling.

`SIGNAL_BRIDGE.md` says it outright: *"No voice/video calls. Signal calling is
a separate WebRTC stack; out of scope for this bridge."* This plan is that
separate stack.

---

## Architecture

RingRTC owns the media and the SDP/ICE cryptography; **we own only the
transport of opaque signaling blobs**, and we already have that transport.

```
        ┌───────────────────────── :signal module ─────────────────────────┐
        │                                                                   │
 UI  ──▶ SignalMessageRepository ──▶ SignalCallManager ──▶ RingRTC          │
 (call/  (call actions, call state)   (glue + Observer)     CallManager     │
 accept/                    ▲            │      ▲            + WebRTC (.so)   │
 hangup)                    │            │      │            audio in/out ───┼──▶ 🎙/🔊
                            │   inbound  │      │ outbound
                            │  received* │      │ onSend*(opaque)
                            │            ▼      │
             SignalChatWebSocket        SignalSender.sendCallMessage
             hasCallMessage() ──────▶   Content{callMessage} ──▶ PUT /v1/messages
                    ▲                                                   │
                    └──────────────── chat.signal.org ◀────────────────┘
```

Flow of a placed call:

1. UI asks `SignalMessageRepository.placeCall(roomId)` → `SignalCallManager`
   resolves the peer's ACI + device list and calls `CallManager.call(remote,
   AUDIO, localDeviceId)` then `proceed(...)` with the ICE servers.
2. RingRTC fires `Observer.onSendOffer(callId, remote, remoteDeviceId,
   broadcast, opaque, AUDIO)`. We wrap `opaque` in `CallMessage.Offer{ id=callId,
   type=OFFER_AUDIO_CALL, opaque }`, put it in a `Content`, and hand it to
   `SignalSender.sendCallMessage(...)`. `broadcast=true` → send to all the
   peer's devices (ICE forking); else just `remoteDeviceId`.
3. The peer's device(s) answer. Their `CallMessage.Answer{opaque}` arrives on
   our chat socket → `SignalChatWebSocket` sees `content.hasCallMessage()` →
   `SignalCallManager.onCallMessage(sender, senderDeviceId, cm)` →
   `CallManager.receivedAnswer(callId, remote, senderDeviceId, opaque, ...)`.
4. `IceUpdate` blobs flow both ways the same way (`onSendIceCandidates` ↔
   `receivedIceCandidates`) until ICE connects; RingRTC starts SRTP audio.
5. `hangup`/`busy` similarly map `onSendHangup`/`onSendBusy` ↔
   `receivedHangup`/`receivedBusy`.

The key insight: **`onSend*` (RingRTC → us) is exactly the set of `CallMessage`
variants, and `received*` (us → RingRTC) is the same set inbound.** The glue
class is mostly 1:1 method mapping plus proto (un)packing.

---

## Key facts, constraints & decisions

### RingRTC as a dependency

- Artifact: `org.signal:ringrtc-android` (Maven Central), AAR, **AGPL-3.0-only**.
  Verify the current version against
  <https://central.sonatype.com/artifact/org.signal/ringrtc-android> at impl
  time and prefer a version whose bundled WebRTC and `CallManager` API match a
  `libsignal-android` we're comfortable with (we're on `0.86.5`).
- **ABIs:** RingRTC's build targets `arm arm64 x86 x64` (its `Makefile`
  `GN_ARCHS`), so the AAR contains an `armeabi-v7a` (`arm`) `.so`. Keep the
  module's existing `abiFilters = ["armeabi-v7a"]` so we ship one slice, same
  as `:signal` does for libsignal.
- **Bundle size:** RingRTC bundles a WebRTC fork (statically linked into
  `libringrtc.so`), and like `libsignal_jni.so` native code R8 can't shrink.
  Concrete numbers: the published AAR (`2.61.0`, Dec 2025) is **74.3 MB** — but
  that's all four ABIs (`arm`, `arm64`, `x86`, `x64`) plus the WebRTC jar; the
  build-time download, not the shipped payload. After the module's
  `abiFilters = ["armeabi-v7a"]`, only the single v7a `libringrtc.so` ships —
  estimated **~20–25 MB uncompressed** (confirm exactly from the built APK's
  `lib/armeabi-v7a/libringrtc.so`, the way libsignal's 67.8 MB was measured).
  So the app's native footprint goes from ~68 MB → **~88–93 MB**. Mitigation
  already exists and is
  documented in `SIGNAL_LAUNCHER_STATUS.md` → "Bundle size": ship native libs in
  an ABI split and reuse it across updates via `SplitApkInstaller`
  (`MODE_INHERIT_EXISTING`). Treat "add RingRTC" and "move native libs to a
  split" as the same work item.
- **License:** AGPL-3.0 adds nothing new — `libsignal-android` is already
  AGPL-3.0, so the app's license posture is unchanged. (Still worth an explicit
  note in the app's OSS-licenses screen.)

### Delivery & size strategy (how this stays off the 5 MB launcher)

The launcher base APK is ~5 MB precisely because it **ships no native code**:
the only in-process backend it bundles is `:gmessages`, which is deliberately
pure-Kotlin (libsignal dropped; X25519 = TweetNaCl port; hand-rolled protobuf).
Everything heavy is a **separate, on-demand-installed app**, fetched from GitHub
Releases and installed via `PackageInstaller`. The model already in the tree:

- **OpenBubbles** (`com.openbubbles.messaging`, SmartTxt) ships as a **`.zip`
  of split APKs** (`base.apk` + `config.*` — the per-device set an App Bundle
  produces) and is sideloaded by `update/SplitApkInstaller.installFromZip`, so a
  **one-ABI, Google-signed set** installs instead of the ~400 MB universal APK.
  Catalogued in `UpdateChecker` (`…/openbubbles-messaging/releases`,
  `assetMatcher = *.zip`).
- The **Signal/Matrix messenger is its own app** too —
  `applicationId = com.offline.dpadmessenger.app` (the wired `:app`). So
  libsignal (67.8 MB) and, with this plan, RingRTC (~20–25 MB) live **in that
  app, not the launcher**. Voice calling therefore adds **zero bytes to the
  launcher**. (Note: the Signal app isn't in `UpdateChecker`'s catalog yet the
  way OpenBubbles is — wiring it up is the same GitHub-Releases + `SplitApkInstaller`
  pattern.)

Applying the launcher's technique to keep the *Signal app's* footprint down,
three levers, cheapest-first:

1. **Ship one ABI as a split set (already the OpenBubbles pattern).** Build the
   Signal app as an App Bundle, publish the armeabi-v7a split `.zip`, install via
   the existing `SplitApkInstaller`. RingRTC's v7a `libringrtc.so` rides in the
   `config.armeabi_v7a` split next to libsignal — one ABI, not four.
2. **Download the native split *once*, retain it across updates
   (`MODE_INHERIT_EXISTING`).** This is the high-ROI lever for the ~90 MB of
   native code and exactly what `SIGNAL_LAUNCHER_STATUS.md` → "Bundle size /
   Option A" already recommends. Today `SplitApkInstaller` uses
   `MODE_FULL_INSTALL`, so every update re-downloads everything; switching the
   *upgrade* path to `MODE_INHERIT_EXISTING` and streaming only the changed
   `base.apk`/logic splits keeps the big `config.armeabi_v7a` split (libsignal +
   libringrtc) on-device. RingRTC benefits for free — the 90 MB is paid once,
   later updates are a few MB. (~15 lines in `SplitApkInstaller`.)
3. **Gate RingRTC as its own on-demand "calling pack" (optional; text-only users
   never download it).** Put `libringrtc.so` in a *separate* split from
   libsignal and install it the first time the user places/receives a call.
   Because a `.so` must be present before `System.loadLibrary`, "on-demand"
   native delivery has to be real install, not a runtime file drop — two viable
   routes for this off-Play device:
   - **PackageInstaller add-split** into the installed Signal package (same
     `MODE_INHERIT_EXISTING` machinery): commit the calling-pack split, then
     `System.loadLibrary("ringrtc")` links. Matches the existing sideload model.
     (Play's `SplitInstallManager` dynamic-feature flow is the on-Play
     equivalent, but they sideload, so this is the fit.)
   - **Raw `.so` download + `System.load(absolutePath)`** into app-private
     storage — simplest, fully in-app, no split plumbing, but *you* own the
     download's signature/integrity check and lose split-signing guarantees.
     Viable because RingRTC is a single well-defined `.so`.
   UX caveat: the pack must land before a call connects. Outgoing is fine (show
   a one-time "Setting up calling… ~22 MB"). Incoming can't wait on a download,
   so **opportunistically pre-fetch the pack on the first Wi-Fi after linking**
   so calls stay instant; fall back to "download to answer" if absent.

Notes: RingRTC and libsignal **don't share** their bundled crypto/WebRTC, so
the two native libs are additive — no dedup. And native code can't be
R8-shrunk, so `abiFilters` (v7a-only, already set) is the only per-lib size
lever besides the delivery tricks above.

**Recommendation:** lever 1 is table stakes (matches OpenBubbles); do lever 2
(`MODE_INHERIT_EXISTING`) as part of this work — it's the biggest win and
helps libsignal too; treat lever 3 (on-demand calling pack) as a follow-on only
if avoiding the ~22 MB for non-callers matters, with Wi-Fi pre-fetch so incoming
calls don't stall.

### Linked-device / ICE-forking semantics (do not skip)

Because we are a **secondary** device, both directions are multi-device:

- **Incoming call:** the caller ICE-forks an offer to *all* our devices
  (primary phone + this Flip). Both ring. If the user answers on the primary,
  the caller hangs up the others (`CallMessage.Hangup` type `HANGUP_ACCEPTED`
  with the winning `deviceId`). We must honor that and stop ringing — don't
  treat it as a normal hangup/missed call.
- **Outgoing call:** when *we* place a call, RingRTC's `onSendOffer` may set
  `broadcast=true` (fork to all the peer's devices) — fan out to every device
  in the peer's device list. Answers come back per device; RingRTC picks the
  winner and tells us to hang up the losers via `onSendHangup`.
- Our own **other** devices also need to know we're on a call (so the primary
  doesn't keep ringing). RingRTC handles the "tell my other devices" via
  hangup/busy to `remote == self`; make sure self-sends aren't filtered out by
  `SignalSender` (today its fan-out excludes `account.aci` in *group* sends —
  call signaling must be allowed to target self's other devices).

### Message delivery characteristics

- Call signaling is **urgent + online**. `PUT /v1/messages` supports an
  `urgent` flag and an `online` semantics; offers should be urgent (wake the
  peer via push), ICE updates are online/ephemeral. Add these knobs to
  `encryptAndSendContent` (today it's implicitly urgent/normal).
- Call messages are **not** conversation content — they must **not** create or
  reorder a room, bump unread counts, or persist to `SignalMessageStore`. Route
  them out of the message pipeline *before* the DataMessage handling in
  `SignalChatWebSocket`.
- A **missed/ended call** *should* drop a small call-event row into the
  timeline ("Missed voice call", "Outgoing call · 2:14"). That's a UI `Message`
  of a new kind, written locally — not a wire message.

### ICE / TURN relays

RingRTC needs ICE servers (STUN/TURN). Signal issues short-lived TURN
credentials from its service (the calling-relays endpoint, `GET
/v1/calling/relays` in Signal-Android — **verify path/shape against the server
version** at impl time). Add `SignalApi.getCallingRelays()` returning the
ICE-server list + credential TTL; pass into `CallManager.proceed(...)`. Cache
until near expiry.

### Audio, focus & foreground service

- `RECORD_AUDIO` runtime permission (new for `:signal`).
- `AudioManager`: request audio focus (`AUDIOFOCUS_GAIN_TRANSIENT` for the call),
  set `MODE_IN_COMMUNICATION`, route to earpiece by default with a speaker
  toggle; handle wired/BT headset. On a flip phone, **earpiece + loudspeaker**
  are the realistic outputs.
- A **foreground service** for the duration of the call
  (`FOREGROUND_SERVICE_MICROPHONE` type on API 34+, which the launcher targets
  at `compileSdk 36`), holding a `WAKE_LOCK`, showing an ongoing call
  notification. Model it on the launcher's existing FGS pattern.
- Ringtone/vibrate for incoming; respect the launcher's DND (`DndMuteManager`).

### Telecom integration — recommended-but-optional

`ConnectionService` + `MANAGE_OWN_CALLS` (self-managed `PhoneAccount`) gives
proper integration: the OS shows a real incoming-call screen, routes audio,
and knows to pause a cellular call. **Recommendation:** Phase-2 nicety. Ship
first with an in-app full-screen incoming-call Activity (`USE_FULL_SCREEN_INTENT`
+ high-priority notification) because it's fewer moving parts on the Flip and we
fully control D-pad focus; add self-managed Telecom afterward for correctness
(GSM-call interop, hardware call/end keys).

---

## The plan (phased)

### Phase 0 — deps, proto, build wiring (~0.5 day)

- Add `implementation("org.signal:ringrtc-android:<ver>")` to
  `signal/build.gradle.kts`. Keep `abiFilters` = `armeabi-v7a`.
- Confirm `CallMessage` (and `Content.callMessage`) are present in the checked-in
  proto (they are) and generated. Add any missing enums we reference.
- Add R8 keep rules for `org.signal.ringrtc.**` to `consumer-rules.pro`
  (native/JNI callbacks + the `Observer` must survive minification, same class
  of bug the libsignal/protobuf keeps already fixed).
- Decide native-lib packaging now (ABI split vs bundled) so size doesn't
  surprise us later.

### Phase 1 — `SignalCallManager` (the glue) (~4–5 days)

New file `signal/.../signal/calling/SignalCallManager.kt`:

- Owns a single `org.signal.ringrtc.CallManager` (created via
  `CallManager.createCallManager(observer)`).
- Implements `CallManager.Observer`:
  - `onStartCall`, `onCallEvent`, `onCallConcluded` → drive a `StateFlow<CallState>`
    (`Idle / Outgoing / IncomingRinging / Connecting / Active(startedAt) / Ended(reason)`).
  - `onSendOffer/onSendAnswer/onSendIceCandidates/onSendHangup/onSendBusy` →
    build the matching `CallMessage` proto and call
    `SignalSender.sendCallMessage(...)` (Phase 2). Honor `broadcast`/`remoteDeviceId`.
- Public API used by the repo/UI:
  - `placeCall(peerAci, peerDeviceIds)` → `callManager.call(remote, AUDIO,
    localDeviceId)` → gather ICE (Phase 4) → `proceed(...)`.
  - `acceptCall()` → `callManager.acceptCall(callId)`.
  - `hangup()` → `callManager.hangup()`.
  - `setMuted(Boolean)` → `callManager.setAudioEnable(!muted)`.
  - `setSpeaker(Boolean)` → `AudioDeviceManager` (Phase 5).
  - `onCallMessage(senderAci, senderDeviceId, cm)` (inbound, Phase 3).
- `Remote` identity: implement RingRTC's `Remote` around the peer's ACI so
  RingRTC can key call state by peer.

### Phase 2 — outbound `CallMessage` send (~2 days)

In `SignalSender.kt`, add:

```kotlin
suspend fun sendCallMessage(
    recipientServiceId: String,
    callMessage: SignalServiceProtos.CallMessage,
    urgent: Boolean,
    targetDeviceId: Int?,   // null = all devices (broadcast / ICE fork)
)
```

- Build `Content.newBuilder().setCallMessage(callMessage)` and route through
  `encryptAndSendContent(...)` — the exact path `sendDirectMessage` uses.
- Extend `encryptAndSendContent` with (a) an `urgent` flag on the
  `OutgoingMessage`/PUT and (b) an optional single-device target (call signaling
  sometimes targets one device, not the fan-out).
- Allow **self-device** targeting (needed so our primary stops ringing);
  ensure the group-send "exclude self" filter doesn't apply here.
- Sealed-sender vs unsealed: offers/answers can go sealed-sender like our data
  messages; keep the same `SealedSessionCipher` path, fall back to unsealed on
  the usual 409/410.

### Phase 3 — inbound `CallMessage` receive (~1–2 days)

In `SignalChatWebSocket.kt::dispatchDecryptedContent` (the `if/else if` chain
around `hasDataMessage`/`hasSyncMessage`/`hasReceiptMessage`, ~L352–535):

- Add a branch **before** the DataMessage handling:

```kotlin
if (content.hasCallMessage()) {
    repository.onCallMessage(sourceServiceId, sourceDeviceId, content.callMessage)
    return   // not conversation content: no room, no unread, no persist
}
```

- `SignalMessageRepository.onCallMessage(...)` forwards to
  `SignalCallManager.onCallMessage(...)`, which decodes which variant and calls
  the right `CallManager.received*`:
  - `Offer` → `receivedOffer(callId, remote, senderDeviceId, opaque, localDeviceId,
    messageAgeSec, callMediaType, ...)` — **drop non-`OFFER_AUDIO_CALL` offers**
    (send a hangup/"not supported") to keep scope audio-only.
  - `Answer` → `receivedAnswer(...)`.
  - `IceUpdate` (repeated) → `receivedIceCandidates(...)`.
  - `Hangup` → `receivedHangup(...)` (respect `HANGUP_ACCEPTED` + winning
    deviceId for the "answered elsewhere" case).
  - `Busy` → `receivedBusy(...)`.
- Ensure `sourceDeviceId` is threaded through decrypt (envelope has it) — call
  routing is device-specific.

### Phase 4 — ICE/TURN relays + ICE forking (~2 days)

- `SignalApi.getCallingRelays()` → parse ICE servers + TTL; feed
  `CallManager.proceed(...)`. Cache.
- Verify the multi-device paths on-device: an incoming forked offer rings and
  can be answered here; answering on the primary makes this device stop ringing;
  an outgoing call to a multi-device peer connects to whichever device answers.
- Handle "not in contacts → relayed (no direct IP)" as the safe default calling
  behavior (RingRTC `DataMode`/relay settings), matching Signal's privacy
  default of hiding IPs from non-contacts.

### Phase 5 — audio device management (~2–3 days)

New `signal/.../calling/AudioDeviceManager.kt`:

- Audio focus; `MODE_IN_COMMUNICATION`; start/stop tied to call lifecycle.
- Output routing: earpiece (default) ↔ speakerphone toggle; wired + Bluetooth
  SCO headset detection and routing.
- Proximity wake-lock is moot on a flip form factor but keep the screen-on
  policy sane during an active call.
- Ringtone + vibration for incoming, honoring `DndMuteManager` / muted state.

### Phase 6 — in-call foreground service + notifications (~2 days)

- `CallForegroundService` (type `microphone` on API 34+): holds the wake lock,
  hosts the `SignalCallManager`'s lifetime for an active/ringing call, posts the
  ongoing-call notification, and posts a **full-screen-intent** high-priority
  notification for incoming ringing (so it shows even from a dark screen).
- Tie service start/stop to `CallState`.

### Phase 7 — call UI + `MessageRepository` contract (~4–5 days)

UI lives in `dpad-messenger` (pure Compose, no network) behind new contract
methods; `:signal` implements them, mock repo no-ops them.

Extend `MessageRepository` (all default no-op so mock/gmessages/matrix compile
unchanged):

```kotlin
// Calling (audio 1:1). Default no-op; repos that can't call leave these.
fun observeCallState(): Flow<CallState> = flowOf(CallState.Idle)
suspend fun placeCall(roomId: String) {}
suspend fun acceptCall() {}
suspend fun hangupCall() {}
suspend fun setCallMuted(muted: Boolean) {}
suspend fun setCallSpeaker(on: Boolean) {}
fun canCall(roomId: String): Boolean = false   // gates the "Call" affordance
```

- `CallState` + a small `CallParticipant` model added to the UI data package.
- Screens (D-pad-first, big targets, minimal chrome — match existing
  `dpad-messenger` style): **Outgoing** (name + "Calling…", End), **Incoming**
  (name + Accept/Decline mapped to left/right soft keys / call+end hardware
  keys), **In-call** (name, timer, Mute / Speaker / End; D-pad to move focus).
- Entry points: a "Call" action in the chat screen's menu, gated by
  `canCall(roomId)` (true only for 1:1 Signal rooms with a resolvable ACI —
  not groups, not PNI-only-and-unreachable).
- Timeline call-event rows: render a new `Message` kind for
  missed/incoming/outgoing call summaries (local-only).

### Phase 8 — launcher integration (~2 days)

In `launcher` (`com.offlineinc.dumbdownlauncher`):

- Manifest: add `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, (optional)
  `MANAGE_OWN_CALLS`; declare `CallForegroundService` and the incoming-call
  Activity with `USE_FULL_SCREEN_INTENT`.
- Route hardware **call/end** keys and soft keys to accept/decline/hang up
  (the launcher already centralizes key handling in `KeyDispatcher` /
  `MouseAccessibilityService`).
- Ensure an incoming Signal call takes foreground even over the launcher home
  and can't be swallowed by the notification-only path
  (`DumbNotificationListenerService`).
- Decide interaction with **cellular** calls: at minimum, don't fight a GSM
  call for audio focus; ideally self-managed Telecom so the OS arbitrates.
- Reuse `MissedCallDetector` styling for a Signal missed-call surface if
  desired.

---

## File-by-file change list

**New (`:signal`)**

- `calling/SignalCallManager.kt` — RingRTC glue + `Observer` + `CallState`.
- `calling/AudioDeviceManager.kt` — focus, mode, routing, ringtone.
- `calling/CallForegroundService.kt` — FGS + notifications.
- `calling/CallModels.kt` — `CallState`, `CallEndReason`, `CallParticipant`.
- `ui/call/OutgoingCallScreen.kt`, `IncomingCallScreen.kt`, `InCallScreen.kt`.

**Edited (`:signal`)**

- `build.gradle.kts` — RingRTC dep; keep `abiFilters`.
- `consumer-rules.pro` — keep `org.signal.ringrtc.**`.
- `SignalSender.kt` — `sendCallMessage(...)`; `urgent`/`online` + single-device
  + allow-self-target in `encryptAndSendContent`.
- `SignalChatWebSocket.kt` — `hasCallMessage()` branch → `repository.onCallMessage`;
  thread `sourceDeviceId`.
- `SignalMessageRepository.kt` — construct/hold `SignalCallManager`; implement
  the new `MessageRepository` call methods; `onCallMessage`; local call-event rows.
- `SignalApi.kt` — `getCallingRelays()` + response model.
- `SignalRepository.kt` (`createIfPaired`) — build `SignalCallManager`, inject
  into repo + socket.
- `AndroidManifest.xml` (`:signal`) — `RECORD_AUDIO`, FGS type, service.

**Edited (`dpad-messenger` UI lib)**

- `data/MessageRepository.kt` — default-no-op call surface + `CallState`.
- Chat screen — "Call" affordance gated by `canCall`.
- Data models — `CallState`, call-event `Message` kind.

**Edited (`launcher`)**

- `AndroidManifest.xml` — permissions, FGS, full-screen incoming Activity.
- Key handling (`KeyDispatcher` et al.) — call/end keys.

---

## Testing (all on-device — CI can't run any of it)

Same reality as the rest of `:signal`: the Android build and every meaningful
test run on a real Flip 2 + a second Signal account.

1. **Loopback / self:** place a call to your own account's other device; verify
   offer/answer/ICE blobs flow (logcat), audio connects.
2. **Flip ↔ phone (you → contact):** outgoing audio call to a contact; verify
   connect, mute, speaker, hang up; verify a call-event row lands.
3. **Incoming, answer here:** contact calls you; the Flip rings (full-screen),
   answer, talk, hang up.
4. **Incoming, answer on primary (ICE forking):** contact calls; both your
   phone and Flip ring; answer on the phone; **the Flip must stop ringing**
   (HANGUP_ACCEPTED) and not log a missed call.
5. **Busy / decline / missed:** each produces the right end reason + timeline row.
6. **Relay path:** call a non-contact (or force relay) — connects via TURN.
7. **Audio routing:** earpiece default, speaker toggle, BT headset.
8. **Interop with GSM:** an incoming cellular call during a Signal call (and
   vice-versa) doesn't wedge audio focus.
9. **Bundle:** confirm APK/split size and that `SplitApkInstaller` reuse keeps
   updates small.

Suggested logcat tags: `SignalCall`, `SignalCallWS`, `RingRTC` (the library
logs under its own tag), `SignalSender`.

---

## Risks & open questions

- **ToS / blocking (highest).** Calling is a louder, more fingerprintable
  surface than messaging; a 3rd-party client doing RingRTC signaling is more
  detectable. Same "no production-safe path" caveat as `SIGNAL_BRIDGE.md`,
  more so. Accept knowingly.
- **RingRTC ↔ libsignal version coupling.** RingRTC's `CallManager` API and
  its bundled WebRTC move between versions; pin a RingRTC version validated
  against our `libsignal-android 0.86.5` and re-verify the `Observer`/`received*`
  signatures on any bump (mirror the "verify symbols on bump" discipline the
  Matrix/Signal modules already document).
- **ICE-forking correctness** is the subtlest part — the "answered on another
  device" and self-device notification cases are easy to get wrong and show up
  only with a real multi-device account.
- **Server endpoint drift.** `/v1/calling/relays`, the `urgent`/`online` PUT
  semantics, and message-multi-recipient rules must be checked against the live
  server; treat exact shapes as verify-on-device.
- **Bundle size** compounds the existing 68 MB libsignal `.so`. Ship native
  libs as a reused split; don't compress (breaks the split-reuse/delta strategy).
- **Battery/thermals** of a WebRTC audio session on low-end 32-bit hardware —
  watch during on-device testing; the repo already has `battery_analysis`
  tooling to lean on.
- **No history/CallKit-style OS integration** unless we do the self-managed
  Telecom phase.

---

## Effort estimate

| Phase | Work | Est. |
|---|---|---|
| 0 | Deps, proto, build wiring, packaging decision | 0.5 d |
| 1 | `SignalCallManager` glue + state | 4–5 d |
| 2 | Outbound `CallMessage` send | 2 d |
| 3 | Inbound `CallMessage` receive | 1–2 d |
| 4 | ICE/TURN relays + forking | 2 d |
| 5 | Audio device management | 2–3 d |
| 6 | Foreground service + notifications | 2 d |
| 7 | Call UI + `MessageRepository` contract | 4–5 d |
| 8 | Launcher integration | 2 d |
| — | On-device debugging buffer (ICE/audio always bites) | ~1 wk |

**≈ 3–4 focused weeks**, dominated by on-device signaling/audio debugging.

Suggested first vertical slice to de-risk: **Phases 0–3 + a bare in-call
screen**, targeting a self-call that connects audio. Everything after that is
hardening and UX.

---

## Why Matrix voice calling is *not* in this plan

For the record, since the messenger also has a Matrix backend: adding voice
calling there is a different, heavier project. `matrix-rust-sdk` (we're on
`0.2.74`) **does not implement WebRTC media** — it exposes a widget/postMessage
API and expects the client to embed **Element Call** (MatrixRTC, MSC4143, on a
LiveKit SFU) for real-time media, or to hand-build legacy 1:1 `m.call.*`
signaling on top of a WebRTC stack you supply. On a D-pad flip phone, embedding
a full web Element Call is impractical, and the legacy path is roughly the same
"bring your own WebRTC + signaling glue" effort as this Signal plan but without
`CallMessage`/RingRTC doing the heavy lifting for you. If Matrix calling is ever
wanted, the cheapest route would likely be to **reuse the WebRTC PeerConnection
layer RingRTC pulls in** and drive it with `m.call.*` events — a follow-on to
this work, not part of it.

---

## References

- RingRTC (engine, AGPL-3.0): <https://github.com/signalapp/ringrtc>
  - `CallManager` API: `src/android/api/org/signal/ringrtc/CallManager.java`
    (`call`, `proceed`, `acceptCall`, `hangup`, `setAudioEnable`,
    `received{Offer,Answer,IceCandidates,Hangup,Busy}`, and the
    `Observer.onSend{Offer,Answer,IceCandidates,Hangup,Busy}` callbacks).
  - Maven: <https://central.sonatype.com/artifact/org.signal/ringrtc-android>
- Multi-device calling / ICE forking (why a linked device can call):
  <https://signal.org/blog/ice-forking/>
- Signal-Android (Kotlin reference for wiring RingRTC ↔ CallMessage):
  <https://github.com/signalapp/Signal-Android> (see its `service`/`ringrtc`
  call-manager glue).
- In-repo: `SignalService.proto` (`CallMessage`), `SignalSender.kt`,
  `SignalChatWebSocket.kt`, `SignalRepository.kt`, and `SIGNAL_BRIDGE.md` /
  `SIGNAL_LAUNCHER_STATUS.md`.
