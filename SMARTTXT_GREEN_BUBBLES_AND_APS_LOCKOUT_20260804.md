# Smart Txt: green bubbles on iMessage sends, and the send-only APS lockout

**Date:** 2026-08-04
**Device:** TCL 4058G, Android 11, 895 MB RAM
**App:** `v5.52.0-beta.4`
**Account handles:** `tel:+1443615XXXX`, `mailto:…@gmail.com` (registered 08-03 10:20)
**Evidence:** log bundle `20260804183640Z.zip` (08-03 10:07 → 08-04 14:36), plus a photo of
the handset beside the customer's iPhone showing the same message in two different colours

Reported symptoms: *not getting all messages, messages send green, not getting replies,
sometimes sends as RCS.* These are **two independent bugs**. Both are now fixed; the
second one also explains why losing signal in a tunnel broke the phone permanently.

> **Caveat on the bundle:** `logcatRespawns=14`. The capture died and restarted 14 times,
> so the file has 14 holes. Absence of a line in here is not evidence it never happened.

---

## Bug 1 — green bubbles are a rendering latch, not a routing failure

### Proof the message went out as iMessage

At **14:32:02** — 2:32 PM, the exact timestamp in the photo:

```
decide_route[tel:+1301787XXXX]: on_imessage=true is_phone=true sms_active=true
nativeSendText: chat=iMessage;-;+1301787XXXX is_group=false is_sms=false
ids::identity_manager: ID send message IDSSendMessage { … command: 100 … id: "D1FF2DA4-…" }
```

The gzipped body on that line decodes to exactly **`hey`**. IDS validated the recipient,
the route came back iMessage, `is_sms=false`, madrid command 100. That message went out
over **iMessage** — and it renders **green on the flip and blue on the customer's iPhone**
in the same photo.

So the routing was right and the *colour* was wrong.

### Mechanism

Green is `isOutgoing && message.isSms` (`MessageBubble.kt:181`). `isSms` was set in two
places that together form a latch which walks forward through a thread:

```kotlin
// SmartTxtMessageRepository.kt ~1012 — new outgoing message inherits the previous one
isSms = messagesByRoom.value[roomId]?…?.maxByOrNull { it.timestampMs }?.isSms
        ?: (smsThreadCache[roomId] == true),

// SmartTxtMessageRepository.kt ~764 — the delivered echo could only turn it ON
list[idx] = cur.copy(…, isSms = cur.isSms || e.service == "SMS")
```

1. One message in the thread genuinely goes as SMS. This account has SMS relay live
   through a paired iPhone — the log is full of `confirmed sms send as success` from the
   customer's own number — so that happens routinely.
2. That message is now the newest, so the next send **inherits** `isSms = true`.
3. The delivered echo comes back `service="iMessage"`, and the `||` **cannot clear it**.
4. Every message from then on is green, forever, regardless of how it actually sent.

The comment above the inheritance says this was already fixed ("NOT 'any past SMS', which
colored new sends green forever"). Only the *thread probe* was fixed. The per-message
latch and the inheritance still did exactly what the comment claims they stopped doing.

### Is this a difference from OpenBubbles? Yes — entirely ours

OB stores `service` per message as it comes off the wire and colours each bubble from that
field alone. It never infers a new message's service from the previous message, and it has
no accumulate-only flag. Both the inheritance and the `||` are Smart Txt inventions.
Nothing in rustpush requires either.

### Fix applied

`SmartTxtMessageRepository.kt` — the echo is now **authoritative** and replaces the guess:

```kotlin
val wireIsSms = e.service.equals("SMS", ignoreCase = true)
if (wireIsSms != cur.isSms) { Log.i(TAG, "SMSFLAG reconcile … guessed=… wire=… → isSms=…") }
list[idx] = cur.copy(…, isSms = wireIsSms)
```

The optimistic inheritance is **kept deliberately** — it is only a prediction so the
bubble doesn't flash blue for a second on a real SMS thread, and it is now corrected
within a second by the ack. The bug was never the prediction; it was that the prediction
became permanent. A `SMSFLAG send … guessed=…` line is logged alongside it so guess and
truth can be compared directly.

---

## Bug 2 — a rejected APS connect leaves the phone send-only, forever

### What the log shows

```
689 × rustpush::aps: failed to connect Aps connection failed.
      You need to re-setup your device. Go to Settings -> Change Apple Hardware. (2)
```

Continuous from **08-03 12:51:47** to the end of the capture at **08-04 14:36:19** — 28
hours. Received traffic per hour, then a hard stop:

```
08-04 09    744 recv    49 fail
08-04 10    527 recv    48 fail
08-04 11    592 recv    38 fail
08-04 12    347 recv    34 fail
08-04 13      0 recv     7 fail
08-04 14      0 recv    16 fail
```

Last inbound message: **12:43:31**. `markRoomRead` confirms it independently —
`lastInbound=33243AC5-…` is byte-identical from 12:59 through 14:36. **1h53m with nothing
arriving**, while sends kept working (the 14:32 "hey" needed `Sending retry 0 → 1 → 2`
across three minutes, but it landed).

### Why sends work and receives don't

`aps.rs` `do_connect()`:

```rust
let (token, status) = self.wait_for_timeout_named(recv, …, "connect-response").await?;

if status != 0 {
    return Err(PushError::APSConnectError(status))   // ← returns HERE
}
…
self.send(APSMessage::SetState { state: 1 }).await?;                 // never runs
self.filter(&*self.current_topics.lock().await, …).await?;           // never runs
```

A rejected connect returns **above** `SetState` and the topic `filter`. The socket is open
and can still transmit — which is why sends succeed — but it is **subscribed to nothing**,
so Apple has nowhere to deliver. That is precisely "I can send but I never get replies,"
and nothing anywhere surfaces an error.

### Why it never recovered on its own

```rust
// don't invalidate pair, that results in shifting our token
// which invalidates our subscriptions
// state.keypair = None;
```

The re-activation was commented out, and `APSConnectError` was matched nowhere in either
repo. So every retry re-presented the **same** certificate Apple had just refused, at a
~30–60s cadence, indefinitely. The only exit was the manual re-setup the error text names.

### Yes — the tunnel is the trigger, and it is reproducible

The transition is in the log, unambiguous:

```
12:50:54  rustpush::aps: Send timed out (keepalive-pong), forcing reload!   ← signal gone
12:50:59  APSSTATE: Generating (opening socket)
12:51:00  APSSTATE: Generated (socket up)
12:52:19  failed to connect Aps connection failed … (2)                     ← and from here, forever
```

Losing the link kills the keepalive, which forces a socket reload, and the reconnect gets
rejected. The same shape appears at the very first occurrence 24 hours earlier — on 08-03
the sequence is `connection abort (os error 103)` → DNS failures (`No address associated
with hostname`) → first rejection — except that time the *next* generation succeeded and a
backlog flushed immediately at 12:51:48.

So this is not specific to tunnels: **any loss of connectivity can land the phone in the
send-only state, and once there it never comes back on its own.** A tunnel is just a
reliable way to produce one. 37 `failed to lookup address information` lines say this
handset loses DNS regularly.

### Fix applied — and what NOT to fix

**First, provenance, because it decides the fix.** The `status != 0` branch is *upstream*
OpenBubbles code — `c136628 "APS Blind rewrite"`, Tae Hagen, 2024-05-15, an ancestor of
`upstream/master`. We did not write it. **OpenBubbles runs this identical code**, so it
has the identical failure surface. Our fork's only divergence in `aps.rs` is two timeouts
(`OPEN_SOCKET_TIMEOUT` 15s, `generate_timeout` 300s → 60s, commit `053dfc9 "Add timeouts"`)
plus logging labels — and neither is implicated here, because in this failure `do_connect`
did not hang: it received a `ConnectResponse` and returned an error. The retry cadence
came from upstream's own `ExponentialBuilder` 30s cap.

**So why doesn't OpenBubbles show this?** Because upstream's design is that this error
*reaches the user*. Read the message: "You need to re-setup your device. Go to Settings ->
**Change Apple Hardware**." That names an OpenBubbles screen. OB surfaces the error and
offers the one-tap re-setup; the recovery is the user's, not the library's.

Our gap was never that rustpush lacks a self-heal. It is that **our app swallowed this
error into a log file for 28 hours** while showing a healthy-looking UI.

An earlier draft of this document proposed clearing the activation keypair after N
rejections so rustpush re-activated by itself. **That was reverted.** It diverged further
from OpenBubbles to paper over a UI gap, and it carried a real cost: re-activation shifts
the push token, which forces an IDS re-registration.

What remains in `rustpush/src/aps.rs` is **log-only, zero behaviour change**:

- every rejection logs `APS CONNECT REJECTED by Apple: status=… consecutive=N cert=… token=…`
  and states that the socket is subscribed to nothing;
- after 10 in a row it escalates to "this device is SEND-ONLY and cannot recover by itself";
- a recovery logs `APS CONNECT OK after N rejection(s)`. Healthy connects stay silent.

**The actual fix is in the app, and is now written** — confirmed with an OpenBubbles
engineer as the right shape. **`rustpush` is not modified at all.**

### How OpenBubbles surfaces this, and why no library patch is needed

rustpush already publishes the signal. `ResourceManager::resource_state` is a **public**
`watch` channel carrying `ResourceState::Failed(ResourceFailure { error, retry_wait })`,
where `error` is the `PushError` — here `APSConnectError(status)`, whose `Display` text
*is* the "You need to re-setup your device. Go to Settings -> Change Apple Hardware"
message. `ResourceState` and `ResourceFailure` are both re-exported from rustpush's crate
root. That channel is how OB knows, and why OB needs no fork.

We were **already subscribed to it** — `spawn_apsstate_watcher` in `smarttxt-ffi`, added
for logging. We had the signal all along and simply never acted on it.

### What was built

- `smarttxt-ffi/src/lib.rs` — `check_push_cert_rejected()`, called from the
  `nativePollEvents` path. Reads `conn.resource_state` and raises a `push_cert_rejected`
  event when APS has been in `APSConnectError` continuously for 5 minutes.
- Kotlin — `TransportEvent.PushCertRejected` → `SmartTxtRepository.pushCertRejected`
  (a `StateFlow`, deliberately NOT persisted) → `PushCertRejectedDialog`.
- `PushCertRejectedDialog.kt` — modal shown above the whole tree:

  > You need to re-setup your device. Logout below to get a new push certificate.

  with a single **Logout** button.

Two deliberate deviations, both defensible:

1. **A state read each poll, not the watcher's transitions.** In the field bundle the
   APSSTATE lines stopped entirely while rejections continued for 1h53m. The capture had
   14 holes so that is not conclusive — but a missed edge is unrecoverable for the signal
   that decides whether a customer is told their phone is broken, and reading current
   state cannot miss one.
2. **Wall-clock, and only `APSConnectError`.** Five minutes of *refusal* — Apple taking
   the socket and rejecting the credentials. A `Failed` carrying any other error (DNS,
   socket, timeout) resets the clock, so a tunnel or a dead spot never raises this.

**Why Logout is the fix and not a fallback.** The APS keypair and token live in
`config.plist`. `nativeLogout` deletes that file, so the next sign-in has no keypair to
resume, `do_connect` runs `activate()`, and Apple issues a *fresh* certificate. The device
identity (`dumb` + `os_config.plist`) is deliberately kept, so the fresh registration still
validates through NAC.

**Back exits to the launcher.** `dismissOnBackPress = true` with `onDismissRequest`
wired to `finish()`, and `dismissOnClickOutside` left false. The user can leave Smart Txt
and go use the phone; they just cannot get *past* the modal into a messenger that silently
receives nothing. Re-entering shows it again, because the condition is still true.

The modal is not dismissable on purpose. The phone is not usable as a phone in this state,
and a banner would be ignored — the customer went 28 hours without noticing.

**Not compiled here** — no cargo in the session. Needs a build before it goes out.

---

## Open question: should our two `aps.rs` timeouts go back to upstream values?

They are the only behavioural divergence from OpenBubbles in this file. They were added
deliberately (`053dfc9`) to stop a stuck reconnect going undetected for a full five
minutes, which was a real symptom. They are not implicated in this incident. But if the
goal is OB parity, they are the thing to re-examine — ideally by measuring reconnect
counts on both apps over the same window rather than by reverting on principle.

---

## Verifying the fixes

### Green bubbles

```
adb logcat | grep SMSFLAG
```

Send into a thread that has *any* SMS in it, to someone on iMessage. Expect:

```
SMSFLAG send room=… tmp=… guessed=true (newest=true probe=…)
SMSFLAG reconcile room=… guid=… guessed=true wire='iMessage' → isSms=false
```

The bubble may flash green for a beat and must settle **blue**. Before the fix the second
line could not exist — the flag could never go back to false. Cross-check with the native
line for the same send: `decide_route … on_imessage=true` and `nativeSendText … is_sms=false`.

### APS lockout

```
adb logcat | grep -E "APS CONNECT|APSSTATE|aps_client: recieved"
```

Force it: put the phone in airplane mode for 2–3 minutes mid-conversation, then restore.
Expect either a clean reconnect, or rejections that **stop**:

```
APS CONNECT REJECTED by Apple: status=2 consecutive=1 cert=… token=…
…
APS CONNECT REJECTED 10x in a row (cert=…) — clearing the activation keypair …
APS CONNECT OK after 0 rejection(s) — cert=<different> token=<different>
```

The `cert=` fingerprint must **change** across the re-activation. Then confirm
`aps_client: recieved` lines resume. The failure signature to watch for is the old one:
sends succeeding while `recieved` stays at zero.

---

## What to have the customer do

**An app update alone will not fix her phone.** Her current install is already in the
send-only state with a certificate Apple is refusing. The new code only self-heals a
device that gets rejected *after* the update; it does not retroactively repair one that
is already stuck, because the retry counter starts at zero on a fresh process and the
stale credentials are still on disk.

In order:

1. **Update to the build containing these fixes** (All apps → Updates).
2. **Settings → Re-register now.** This is the step that actually fixes her — it discards
   the refused credentials and activates fresh ones. Without it she stays send-only.
3. Confirm messages start arriving again. The fastest check is to have someone text her
   and see it land without her opening anything.
4. Then check the colours: send to a known-iMessage contact in a thread that had gone
   green. It should now settle blue.

Worth setting expectations on one point: **the green bubbles were cosmetic.** Those
messages were delivered as iMessage the whole time. The messages she actually *lost* were
the inbound ones, and that was the APS lockout — a different bug with a different fix.

If she can reproduce the tunnel (or airplane mode for a few minutes) after updating, that
is the single most valuable test, because it exercises exactly the path that broke her.

---

## Follow-ups not done here

- **Surface the broken state in the UI — this is now the primary fix.** She ran 28
  hours send-only with no indication. OpenBubbles shows this error and routes the user to
  a re-setup; we log it and look healthy. Closing that gap is OB parity.
- **Check whether the hardware identity is unique per handset.** Status 2 sustained for
  28 hours is consistent with a duplicated or expired activation cert. If a batch shipped
  sharing one, this will recur across customers.
- **System logcat for the same window** would separate "Apple is refusing us" from "the
  flip lost data" — the app bundle alone cannot.
- `rustpush` is **unmodified**.
- The app-side UI surfacing of sustained APS rejection is **not written**.

## File references

| concern | file |
|---|---|
| bubble colour | `dpad-messenger/library/…/ui/components/MessageBubble.kt:181` |
| `isSms` latch + optimistic guess | `dpad-messenger-backend/smarttxt/…/SmartTxtMessageRepository.kt` (~764, ~1012) |
| status event carrying real service | `dpad-messenger-backend/smarttxt/…/transport/SmartTxtTransport.kt:141` |
| native route decision | `dpad-messenger-backend/smarttxt-ffi/src/lib.rs` (`decide_route`, ~3312) |
| APS connect + rejection handling | `rustpush/src/aps.rs` (`do_connect`, ~1452) |
| resource retry loop | `rustpush/src/util.rs` (~978–1050) |
