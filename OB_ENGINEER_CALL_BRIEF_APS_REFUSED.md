# Call brief — APS connect refused, device goes send-only

**For:** OpenBubbles engineer call
**From:** Offline DC (Smart Txt — rustpush via JNI on a TCL flip, Android 11, 938 MB)
**Date:** 2026-08-05

---

## The one-line version

On a customer handset, Apple started refusing our APS connect. rustpush retried forever,
the phone kept **sending fine**, and received **nothing for 20 of 24 hours** — with no
error surfaced anywhere. We want to sanity-check our reading of the mechanism and our fix
before we ship it.

---

## What we observed

Field handset, `rustpush` at our fork of `OpenBubbles/rustpush`, two log bundles ~24h apart.

```
12:50:54  rustpush::aps: Send timed out (keepalive-pong), forcing reload!   ← lost signal
12:50:59  APS: Generating (opening socket)
12:51:00  APS: Generated (socket up)
12:52:19  failed to connect Aps connection failed … Change Apple Hardware. (2)
          … and from here, ~914 rejections over 41 hours
```

- **Trigger:** a loss of connectivity (she went through a tunnel). Keepalive-pong times
  out → socket reload → the reconnect gets `ConnectResponse` **status 2**.
- **Symptom:** sends keep working, delivery receipts come back, nothing is ever received.
  Two outages of **13.8 h** and **6.3 h** with zero inbound.
- **IDS registration stayed healthy the whole time** — `Reregistering in 3739908 seconds`.
- It recovered **on its own, twice**: once inside the same process (socket regenerated,
  1083 backlogged messages flushed immediately), once at a process restart.

## Our reading of the mechanism

In `aps.rs::do_connect`, a non-zero status returns **above** `SetState { state: 1 }` and
the topic `filter`:

```rust
let (token, status) = self.wait_for_timeout_named(recv, …, "connect-response").await?;
if status != 0 {
    return Err(PushError::APSConnectError(status))   // ← returns here
}
…
self.send(APSMessage::SetState { state: 1 }).await?;      // never runs
self.filter(&*self.current_topics.lock().await, …).await?; // never runs
```

So the socket is open and can transmit — which is why sends work — but it is subscribed
to nothing, so Apple has nowhere to deliver. **Is that right?**

## How this surfaces in OpenBubbles — three paths, and our case slips all three

We traced every way a refused APS connect can reach an OB user:

1. **During setup/login.** `catch (e) → controller.updateConnectError(e.message)` → the
   `ErrorText` widget on the Apple ID login / 2FA / finalize pages. This is where
   *"You need to re-setup your device. Go to Settings -> Change Apple Hardware"* is
   designed to be read — it names a screen because the reader is mid-setup.
2. **On a failed send.** `markFailed(msg, error)` writes `error-protocol: <PushError>`
   into the message guid, the bubble shows failed, and `createFailedToSend` notifies if
   the app is closed.
3. **On registration failure.** `RegisterState_Failed` → `createRegisterFailed`
   (`rustpush_service.dart:3361`), one-shot `notifiedFailed` latch, no threshold.

Our failure fires **none** of them: setup was long finished, **sends succeeded** (the
socket transmits — it just subscribed to nothing), and **registration stayed healthy**
for the full 20 hours. There is no steady-state APS health check — `conn` appears in the
Dart only as an argument passed into API calls, with no subscription to
`ResourceManager::resource_state`.

So the question isn't "why doesn't OB handle this" — it's that OB's three surfaces all
sit either side of this particular gap.

## Questions for the call

1. **Is the gap above known?** Your three surfaces are setup-time, failed-send, and
   registration-failure. A refused connect in steady state, with sends still working
   and registration healthy, reaches none of them. Have OB users hit this, and what
   happens to them today — do they just notice and reinstall?

2. **What does `ConnectResponse` status 2 actually mean?** Bad cert, stale token, or
   throttling? Ours recovered spontaneously after ~14 h and again after ~3 h, which
   reads more like throttling than a dead certificate — but we're guessing.

3. **Why is `state.keypair = None` commented out?** The comment says clearing it shifts
   the token and invalidates subscriptions. We nearly re-enabled it behind a retry
   threshold and backed off. What actually breaks if a device re-activates after a
   sustained refusal — and does re-registering while Apple is refusing you make the
   throttle worse?

4. **What's the right recovery weight?** You have three:
   `markFailedToLogin()` (re-login, keep hardware) / `(hw: true)` / `(hw: false, logout: true)`.
   For a refused APS connect, which — and does a full logout risk digging the hole
   deeper given it re-registers?

5. **Is there a better signal than APS state?** We're currently reading
   `resource_state` for `APSConnectError`. But rejections are noisy: **48 separate
   episodes in 41 hours**, of which only **2** caused a real outage. Copying your
   threshold-free `notifiedFailed` latch onto this signal would alert ~48 times.
   We're inclined to gate on "refused **and** nothing delivered for 30 min" instead.
   Does that sound right, or is there a cleaner health signal we're overlooking?

6. **Anything about our environment that makes this more likely?** Rooted TCL flip,
   938 MB RAM, frequent DNS loss (37 `No address associated with hostname` in one
   bundle), `hw.openbubbles.app` for validation data. Also worth asking: **is a shared
   or duplicated hardware identity across a fleet a plausible cause of status 2?**

---

## What we've built so far (open to being told it's wrong)

- **rustpush: unmodified.** We read `resource_state` from our FFI, nothing patched.
- Detection in our FFI: `ResourceState::Failed(f)` where `f.error` is `APSConnectError`.
  Read per poll rather than consuming watcher transitions, because in one bundle the
  watcher's lines went silent while rejections continued.
- Surface: a system notification on a dedicated errors channel, one-shot latch cleared on
  reconnect — modelled on your `createRegisterFailed` / `clearRegisterFailed`. We match
  your three surfaces and added no fourth.
- **We deliberately do NOT tell the user to log out** (see Q4). She recovered twice
  without one — once in-process, presenting the same certificate Apple had refused ~200
  times — so we cannot justify costing someone their history and an Apple sign-in for an
  action we have never seen resolve this. The notification states the symptom and points
  at our support address.
- Undecided: the 30-min delivery-gap gate (Q5).

## Separately, a smaller one we already fixed

Unrelated bug worth a sanity check: we were rendering iMessage sends as green. Our own
bug — we inherited a new outgoing message's service from the previous message in the
thread and then OR'd it with the delivered echo (`isSms = cur.isSms || service == "SMS"`),
so it could latch on but never off. We've made the echo authoritative. We believe OB
colours each bubble straight from the per-message `service` off the wire and never infers
— **confirm?**
