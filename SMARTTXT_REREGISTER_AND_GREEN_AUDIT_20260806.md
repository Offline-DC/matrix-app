# Smart Txt — re-registration & green-bubble audit

**Prepared for:** the OpenBubbles engineer
**Date:** 2026-08-06
**Scope:** two concerns — (1) "is Smart Txt re-registering more than rustpush asks it to?" and
(2) "are we only going green when Apple's `id-query` returns status 0?"

Everything below carries a `file:line` or a `log-file:line`. Section 7 lists the exact commands to
reproduce each claim from scratch.

---

## 0. TL;DR

| Concern | Verdict |
|---|---|
| **Re-registering too often?** | **Not in these logs — zero re-registrations in 51 h across a device reboot and an app update.** Proven positively (not by absence). **But the concern is legitimate about the code:** Smart Txt has one automatic trigger that rustpush and OpenBubbles both lack. See §2.2 / §5.1. |
| **Green only on query 0?** | **Yes on the confirmed-negative path.** rustpush hard-errors unless the top-level `id-query` status is `0` (`user.rs:993`), so a "not on iMessage" verdict is only reachable from a status-0 response. **But Smart Txt has one path OpenBubbles does not: an IDS lookup *error* routes to green SMS** (`lib.rs:3329`). It did not fire in this capture. It should be deleted. See §5.2. |
| **The one green send in the capture** | **Correct.** Apple returned status 0 with an empty identity list, and the inbound message from that same contact 27 s earlier arrived as `cmd=140` on `com.apple.private.alloy.sms` — i.e. a forwarded SMS. That contact is genuinely not on iMessage. See §4.2. |
| **What actually broke for this user** | **APS, not registration.** 436 `Aps connection failed … (2)` over 21.6 h under one PID, cleared by a reboot. See §6. |

---

## 1. Provenance — what was analysed

| Artifact | Identity |
|---|---|
| Smart Txt app + FFI | `matrix-app` @ `ca6b518`, working tree **clean** (`git status --porcelain` empty) |
| Vendored rustpush | `repos/rustpush` |
| Upstream rustpush | `github.com/TaeHagen/rustpush` @ HEAD (cloned fresh for the diff) |
| OpenBubbles | `github.com/OpenBubbles/openbubbles-app` @ `eed1b63` |
| Logs | 2 bundles, `com.offlineinc.dumbdownlauncher v5.54.0-beta.2`, TCL 4058G, Android 11, `lowRamDevice=true` |

**Log coverage.** Merged span **08-04 08:29:39 → 08-06 11:27:22 local (UTC−4)**, ≈51 h. Three PIDs:

| PID | Window | Duration | Event that ended it |
|---|---|---|---|
| 5365 | 08-04 08:29:39 → 08-05 06:05:51 | 21.60 h | **device reboot** (2 h 38 m silence; next PID is *lower*, 1798) |
| 1798 | 08-05 08:44:27 → 08-05 09:34:58 | 0.84 h | **app update** — see below |
| 10810 | 08-05 09:36:59 → 08-06 11:27:22 | 25.84 h | still running at export |

The 1798→10810 transition is an **app update, not just a restart**: `SMSFLAG send` is logged
unconditionally on the Kotlin send path (`SmartTxtMessageRepository.kt:1044`) and landed in commit
`4075887` (2026-08-04 16:10). PID 1798 performed two sends (`08:58:10`, `08:59:33`) with **no**
`SMSFLAG` line; PID 10810 emits one on every send. So PID 10810 is the first process running a
build that contains the current send path.

**Log holes — read this before quoting anything.** Bundle 2 reports `logcatRespawns=37`,
`logcatGapMs=62000`; 28 provably-lossy windows totalling ≈241 min (~12 % of that bundle), plus the
2 h 38 m reboot gap and the 2 m app-update gap. **Absence of a line is not proof it never
happened.** Every claim below is therefore stated either as a *positive* observation or is
explicitly flagged as absence-based.

---

## 2. Re-registration: the complete trigger set

### 2.1 rustpush's own triggers — and proof we did not touch them

`diff -u` of upstream vs vendored:

- `src/ids/user.rs` — **byte-identical** (empty diff). This is the file that owns `register()`,
  `calculate_rereg_time_s()`, and the `id-query` status check.
- `src/ids/identity_manager.rs` — differs **only** by added `log::` statements and one send-ack
  timeout (`15000` → `30000` ms at `identity_manager.rs:1016`). No control flow touched.
- `src/util.rs` — HTTP client only (removed debug proxy, added `connect_timeout`).

Every path that reaches `register()` goes through `IdentityResource::generate`
(`identity_manager.rs:362-368`), which is invoked **only** by the `ResourceManager` loop
(`util.rs:987`). The complete set:

| # | Trigger | Location |
|---|---|---|
| 1 | **Scheduled.** `registered_at_s + next-hbi` if Apple sent one, else `cert_exp − 300 s` | `identity_manager.rs:490-524`, `user.rs:90-98` |
| 2 | **Service data-hash changed** (app upgrade with a new capability set) or service never registered | `identity_manager.rs:402-411` |
| 3 | **Apple push `c == 32`** — "reregister now" | `identity_manager.rs:735-738` |
| 4 | **Apple push `c == 66` + handle-set inequality** | `identity_manager.rs:739-747` |
| 5 | **IDS lookup returns 6005** | `identity_manager.rs:694-699` |
| 6 | **Failure ladder** — 5 min → 24 h, unlimited retries | `identity_manager.rs:436-439` |
| — | `update_users` → `refresh_now()` | `identity_manager.rs:482-488` — **dead code**, no caller in any of the three trees |

Trigger 4 is the one that matters here:

```rust
// rustpush/src/ids/identity_manager.rs:739-747
66 => {
    debug!("IDS said handles changed");
    let my_handles: HashSet<String> = self.get_handles().await.into_iter().collect();
    let real_handles = self.get_possible_handles().await?;
    if real_handles != my_handles {
        info!("New handles; reregistering! {:?} {:?}", real_handles, my_handles);
        self.manager().await.refresh().await?;
    }
},
```

**The gate is the point.** rustpush performs this comparison *only* when Apple pushes `c == 66`.
It never runs it spontaneously. It also uses `refresh()`, not `refresh_now()`.

### 2.2 What Smart Txt adds on top

**(A) `reconcile_handles` — automatic, on every client build.**

```rust
// smarttxt-ffi/src/lib.rs:3228-3244
async fn reconcile_handles(client: &Arc<IMClient>) -> Vec<String> {
    let my: HashSet<String> = client.identity.get_handles().await.into_iter().collect();
    match client.identity.get_possible_handles().await {
        Ok(possible) if possible != my => {
            log::info!("reconcile: IDS vends handles we haven't registered (…) — reregistering");
            if let Err(e) = client.identity.refresh_now().await { … }
        }
        Ok(_)  => log::info!("reconcile: registered handles already match IDS"),
        Err(e) => log::warn!("reconcile: get_possible_handles failed ({e}) — keeping current handles"),
    }
    client.identity.get_handles().await
}
```

called unconditionally at `lib.rs:2403`, inside `build_client_and_receive`, which runs on
`nativeConnect` (resume path, `lib.rs:1514-1520`) and on `nativeRegister`. So: **every connect,
every cold start, every network-triggered client rebuild.**

The comment above it attributes this to OpenBubbles. That attribution is wrong — the line it
mirrors is rustpush's, at `identity_manager.rs:743`, and it is gated behind Apple's `c == 66` push.
OpenBubbles has no equivalent (§2.3).

**(B) `healIfUnhealthy` — automatic, but properly conditioned.** `SmartTxtRepository.kt:658-720`,
called from the boot path at `:485`. Fires **only** if `!nativeClientReady()` (`:674-675`), guarded
by an in-process re-entry flag (`:679`) and a **6 h persisted cooldown** (`:690-697`,
`HEAL_COOLDOWN_MS` at `:996`). Registration *age* is deliberately not consulted (`:669-673`).
This one is defensible: it targets a dead client, not a stale registration, and it addresses a real
gap OB shares — rustpush's rereg timer is in-process only, so a force-stopped handset has nothing
driving it.

**(C) Settings "Re-register now"** — manual, `nativeReregister` at `lib.rs:2004`. Equivalent to OB's
troubleshooter button.

**Not triggers** (checked and cleared): `spawn_apsstate_watcher` is log-only (`lib.rs:2100-2151`);
`nativeReconcileHandles` (`lib.rs:2266`, wrapper `RustPushNative.kt:315`) has **no Kotlin caller** —
dead code; the 12 h WorkManager renewal job was removed, with the reasoning recorded at
`SmartTxtRepository.kt:132-143`.

### 2.3 OpenBubbles, for comparison

OpenBubbles has exactly one bridge function that re-registers:

```rust
// ob/rust/src/api/api.rs:1869-1872
pub async fn do_reregister(state: &Arc<IMClient>) -> anyhow::Result<()> {
    state.identity.refresh_now().await?;
    Ok(())
}
```

**Three call sites, all synchronous UI button handlers:** `troubleshoot_panel.dart:509`
("Reregister — run this troubleshooter if you are told to do so"), `profile_panel.dart:75`, and
`profile_panel.dart:752` (both post-subscription-activation).

- **App start:** `RustPushService.onInit` (`rustpush_service.dart:4795-4894`) → `SharedPushState::restore`
  (`api.rs:564-649`) → `make_imclient` (`api.rs:456`). No forced registration anywhere in that chain.
- **Foreground/resume:** `lifecycle_service.dart:64-156` never touches the identity resource.
- **APNs reconnect:** OB's `generated_signal` subscriber only re-persists `hw_info.plist`
  (`api.rs:900-920`). The library's own subscriber sends a cache-flush (`c: 160`), not a register
  (`aps_client.rs:128-141`, `:167-187`).
- **Handle reconciliation:** there is no `getPossibleHandles` Dart binding at all. The single Rust
  use is `validate_cert` (`api.rs:2675-2679`), called once during setup as a liveness probe
  (`setup_view.dart:848-857`) — the returned list is **discarded**, no comparison, no re-register.

**OpenBubbles adds zero automatic re-registration triggers.**

### 2.4 Side-by-side

| Trigger | rustpush (auto) | OpenBubbles | Smart Txt |
|---|---|---|---|
| Cert expiry / `next-hbi` cadence | ✅ | — | — |
| Service data-hash changed | ✅ | — | — |
| Apple push `c == 32` | ✅ | — | — |
| Apple push `c == 66` + handle mismatch | ✅ | — | — |
| IDS 6005 on lookup | ✅ | — | — |
| Failure ladder 5 min → 24 h | ✅ | — | — |
| **Every client build / connect** | ❌ | ❌ | ⚠️ **yes** (`lib.rs:2403`) |
| Launch-time heal when client is dead | ❌ | ❌ | ⚠️ yes, 6 h cooldown (`SmartTxtRepository.kt:658`) |
| Foreground / resume | ❌ | ❌ | ❌ |
| Periodic worker | ❌ | ❌ | ❌ (removed) |
| APNs reconnect | ❌ | ❌ | ❌ |
| User button | — | ✅ ×3 | ✅ ×1 |

---

## 3. Proof from the logs: zero re-registrations in 51 hours

Three independent lines of evidence. The first two are **positive** — they do not rely on a line
being absent, so the 12 % log loss does not weaken them.

### 3.1 The registration deadline never moved — including across the reboot and the app update

rustpush prints `Reregistering in N seconds` where `N = calculate_rereg_time_s()`. Adding `N` to the
line's own timestamp yields the absolute deadline. All 14 occurrences:

| local timestamp | PID | N (s) | implied deadline | file:line |
|---|---|---|---|---|
| 08-04 09:02:20.682 | 5365 | 3805963 | 2026-09-17 10:15:03.682 | `logs1/segment-20260805-063238.log:935` |
| 08-04 10:49:45.549 | 5365 | 3798638 | 2026-09-17 10:00:23.549 | `logs1/segment-20260805-063238.log:5164` |
| 08-04 11:37:29.811 | 5365 | 3796654 | 2026-09-17 10:15:03.811 | `logs1/segment-20260805-063238.log:6405` |
| 08-04 14:53:09.091 | 5365 | 3784035 | 2026-09-17 10:00:24.091 | `logs1/segment-20260805-063238.log:8490` |
| 08-04 15:07:45.872 | 5365 | 3784038 | 2026-09-17 10:15:03.872 | `logs1/segment-20260805-063238.log:8521` |
| 08-05 00:02:37.073 | 5365 | 3751067 | 2026-09-17 10:00:24.073 | `logs1/segment-20260805-063238.log:8611` |
| 08-05 03:23:15.849 | 5365 | 3739908 | 2026-09-17 10:15:03.849 | `logs1/segment-pre-20260805-122824.log:2925` |
| — | — | — | — | ← **device reboot + app update** |
| 08-05 11:47:16.698 | 10810 | 3709667 | 2026-09-17 10:15:03.698 | `logs2/segment-20260805-231048.log:1377` |
| 08-05 16:04:16.796 | 10810 | 3694247 | 2026-09-17 10:15:03.796 | `logs2/segment-20260805-231048.log:2635` |
| 08-05 18:00:12.819 | 10810 | 3687291 | 2026-09-17 10:15:03.819 | `logs2/segment-20260805-231048.log:8560` |
| 08-05 19:56:16.046 | 10810 | 3680328 | 2026-09-17 10:15:04.046 | `logs2/current.log:2329` |
| 08-05 23:40:39.016 | 10810 | 3666865 | 2026-09-17 10:15:04.016 | `logs2/current.log:7459` |
| 08-06 05:38:54.225 | 10810 | 3645369 | 2026-09-17 10:15:03.225 | `logs2/current.log:11406` |
| 08-06 10:15:15.545 | 10810 | 3628788 | 2026-09-17 10:15:03.545 | `logs2/current.log:13406` |

**11 samples resolve to `2026-09-17 10:15:03`, total spread 0.821 s** across 50 hours (the spread is
just `N` being truncated to whole seconds). A completed re-registration rewrites `registered_at_s`
and therefore moves this deadline. **It did not move — including across the reboot and the app
update.** The registration in force at export is the same one from before the capture began; on the
45-day cadence it dates to ≈2026-08-03 10:15.

*(The three `10:00:24` samples are a second tracked registration that stops appearing after the
reboot. Treat as a lead, not a finding — I can't prove the print model and ~12 % of bundle 2 is
lost.)*

### 3.2 The registration-state watcher fired zero times — while its sibling fired 48 times

`spawn_regstate_watcher` (`lib.rs:2066-2098`) subscribes to rustpush's `resource_state` watch
channel and logs **every** transition at `info`. A re-registration necessarily passes through
`resource_state.send_replace(ResourceState::Generating)` (`util.rs:993`), which would emit
`REGSTATE(push): {"state":"registering"}`.

- **`REGSTATE` occurrences in 51 h: 0.**
- **`APSSTATE` occurrences: 48** (30 `Failed`, 9 `Generating`, 9 `Generated`).

`spawn_apsstate_watcher` is spawned on the *adjacent line* (`lib.rs:2416`) in the same function.
Its output proves the watcher machinery, the log level, and the ring's capture filter were all
working. So the zero is a real zero: **the identity resource never changed state during the
capture.** Not "no re-registration completed" — *no re-registration was ever attempted.*

### 3.3 Absence checks (weaker, but consistent)

Zero occurrences, both bundles: `Reregistering now!`, `Successfully reregistered!`, `Register success!`,
`Register failed`, `Triggering reregister because service … data hash changed`,
`Got reregister command, reregistering!`, `New handles; reregistering!`, `Swapped users`,
`Auth returns 6005`, `Retrying <bool> Identity` (the line `refresh_option` logs at `util.rs:1106`
whenever a refresh is actually requested), `nativeRegister`, `nativeReregister`, `nativeConnect`,
and `reconcile:`.

A case-insensitive `/rereg|regist/` over both bundles returns **15 matches, 100 % of them
`Reregistering in N seconds`**.

**One honest caveat.** Zero `reconcile:` and zero `REGSTATE(push, initial)` also means
`build_client_and_receive` did not run *inside the captured window* — each process's client was
built in the sub-second gap before the log ring attached (PID 10810's first line is an `IDS RX` at
09:36:59.748, so the client was already up). **So these logs cannot tell us what `reconcile_handles`
decided at startup.** What they *can* tell us — via §3.1 and §3.2 — is that whatever it decided, no
re-registration followed. §7 has a one-line instrumentation change to close this gap.

---

## 4. Green bubbles: what "query 0" actually means in this stack

### 4.1 rustpush ground truth

**Top-level status is checked, strictly:**

```rust
// rustpush/src/ids/user.rs:993-998
let loaded: IDSLookupResp = plist::from_bytes(&request)?;
if loaded.status != 0 || loaded.results.is_none() {
    return Err(PushError::LookupFailed(IDSError(loaded.status)))
}
Ok(loaded.results.unwrap())
```

Only `0` is accepted. Anything else is an `Err`, never an empty result. **So the answer to your
question is yes: a "not on iMessage" verdict is only reachable from a status-0 response.** This file
is byte-identical to upstream.

**Two caveats you should know about, both in the library, both shared with OpenBubbles:**

*(a) There is no per-URI status check.* The response type has no `status` field:

```rust
// rustpush/src/ids/user.rs:642-652
struct IDSLookupResp { status: u64, results: Option<HashMap<String, IDSLookupUser>> }

#[serde(rename_all = "kebab-case")]
pub struct IDSLookupUser {
    pub identities: Vec<IDSDeliveryData>,
    pub sender_correlation_identifier: Option<String>,
}
```

No `#[serde(deny_unknown_fields)]`, so a per-URI `status` in Apple's reply is silently discarded.
The only status comparison in the entire lookup path is line 994. A per-URI result carrying
`identities: []` *alongside* a non-zero status is therefore indistinguishable from a genuine
negative. (Mitigation: `identities` has no `#[serde(default)]`, so a per-URI result with a `status`
and **no** `identities` key fails deserialization of the whole response → `Err`. Only the
`identities: []` shape is dangerous.)

*(b) Negative results are cached for one hour, on disk.*

```rust
// rustpush/src/ids/identity_manager.rs:24
const EMPTY_REFRESH: Duration = Duration::from_secs(3600); // one hour

// :51-57
fn is_valid(&self) -> bool {
    let stale_time = self.get_stale_time();
    if self.keys.identities.is_empty() { stale_time < EMPTY_REFRESH } else { … }
}
```

`put_keys` calls `save()` (`:342`) → `id_cache.plist`, and the TTL is wall-clock (`at_ms`), so an
empty result survives process restarts. Both apps point at the same file (Smart Txt `lib.rs:2393`;
OB `api.rs:457`). Smart Txt never calls `invalidate_id_cache`; OB exposes it in the troubleshooter
(`troubleshoot_panel.dart:471`).

Everything else in rustpush is fail-safe: the 15 s APS timeout (`aps.rs:1505`), the one retry
(`identity_manager.rs:861`), and `ensure_ready()` on a down resource (`:678`) all produce `Err`,
never an empty list.

### 4.2 The one green send in the capture — and why it was right

`logs2/current.log:14676-14712`, verbatim, in order:

```
11:05:14.015  IDS RX cmd=140 topic=com.apple.private.alloy.sms from=tel:+15555550201 … uuid=d4e1b580…
11:05:14.035  recv msg: guid=D4E1B580-… sender=tel:+15555550202 … chat_guid=iMessage;-;+15555550202
11:05:41.097  IMsgRepo: SMSFLAG send room=iMessage;-;+15555550202 tmp=… guessed=true (newest=true probe=true)
11:05:41.345  W rustpush::ids::identity_manager: IDS returned zero keys for participant tel:+15555550202
11:05:41.434  smarttxt_ffi: decide_route[tel:+15555550202]: on_imessage=false is_phone=true sms_active=true valid=[]
11:05:41.434  smarttxt_ffi: nativeSendText: … is_sms=true from_handle=tel:+15555550201
11:05:42.787  rustpush::imessage::aps_client: recieved [tel:+15555550201] confirmed sms send as success
```

Reading it:

1. **`cmd=140` on `com.apple.private.alloy.sms`** is an inbound **forwarded SMS** relayed from her
   iPhone. The contact's own message arrived over SMS, 27 seconds before she replied.
2. **`IDS returned zero keys for participant`** (`identity_manager.rs:711`) fires only inside the
   `Ok` branch — i.e. Apple returned **status 0** and a per-URI entry with an empty identity list.
   This is the confirmed-negative path, not the error path.
3. Both independent signals agreed with it: `newest=true` (the thread's newest message was SMS) and
   `probe=true` (the composer's `nativeIsIMessage` probe).
4. The send was acknowledged by her iPhone as a successful SMS.

**That green was correct.** `+15555550202` is not on iMessage.

Full route census for the capture: **18 `decide_route` decisions, 17 blue, 1 green** (the one above).
Zero `decide_route … validate failed` lines — the error branch never fired.

---

## 5. The two things worth changing

### 5.1 `reconcile_handles` should be gated, or deleted

It is *usually* a no-op. Both sides of the comparison ultimately come from the same
`id-get-handles` call:

- `IdentityResource::get_possible_handles` inserts `handle.uri` for every handle
  (`identity_manager.rs:476`).
- `register()` requests `IDSUser::get_possible_handles` — `handle_data.map(|h| h.uri)`
  (`user.rs:839-843`, called at `user.rs:1040`) — and stores back the URIs Apple echoes in
  `services[].users[].uris`, hard-failing if any carries a non-zero status (`user.rs:1156-1174`).

So after a clean register the sets match and `reconcile_handles` takes the `Ok(_)` branch. **The
risk is the tail:**

1. If Apple's register response ever *omits* a URI that `id-get-handles` returns, `possible != my`
   is **permanently** true. There is no assertion in rustpush covering that case. Separately,
   `register()` only ever **inserts** into `user.registration` (`user.rs:1180`) — it never clears —
   so a user dropped from the response keeps stale `handles` and mismatches indefinitely.
2. In that state, Smart Txt re-registers on **every connect, every cold start, every network flap**.
3. And it uses `refresh_now()`, which **cancels rustpush's failure backoff**:

```rust
// rustpush/src/util.rs:1026-1032
select! {
    _ = tokio::time::sleep(retry_in) => {},
    res = retry_now_recv.recv() => if res.is_none() { break 'stop; },   // ← refresh_now() lands here
    _ = death_recv.recv() => { break 'stop; }
};
```

`refresh()` does not wake that arm; `refresh_now()` does. The only guard on `refresh_option` is a
**15 s** debounce keyed on the last *successful* generation (`util.rs:899`, `:1104-1107`), and
`refreshed_at` starts at `UNIX_EPOCH` (`util.rs:962`), so the first call after a client build is
never debounced. On an account already being throttled, this converts the 5 min → 24 h ladder into
an immediate retry.

**Recommended:** delete the call at `lib.rs:2403` and let rustpush's `c == 66` handler do the job it
already does. If you want a belt-and-braces check, gate it: only run on a *subset* violation
(`!my.is_superset(&possible)`), at most once per N hours persisted, and use `refresh()` not
`refresh_now()`.

*Note: this is a code-shape argument, not something these logs demonstrate. §3 shows it did not
re-register here.*

### 5.2 `decide_route`'s error branch should not route green

```rust
// smarttxt-ffi/src/lib.rs:3316-3335
let valid = match client.identity.validate_targets(&targets, "com.apple.madrid", handle).await {
    Ok(v) => v,
    Err(e) => {
        if is_phone && sms_active {
            log::warn!("decide_route[{recipient}]: validate failed ({e:?}) — forwarding on, routing SMS");
            return sms_route(&client.identity.get_handles().await);   // ← green on ERROR
        }
        log::warn!("decide_route[{recipient}]: validate failed ({e:?}); defaulting to iMessage");
        return Route::IMessage;
    }
};
```

This is the one place the stack can go green **without** a status-0 answer from Apple. Everything
below lands in that branch: `SendTimedOut` (15 s APS timeout + one retry ≈ 30 s), `LookupFailed`
for **any** non-zero status including `6004` "Please try again" and `6009` "temporarily disabled",
`WebTunnelError`, `ResourceState::Failed` from `ensure_ready` (i.e. **mid-re-registration**), and a
plist parse failure.

Three things make this worse than it looks:

- **The gate is effectively always open.** `sms_active` is set true on *any* inbound forwarded SMS
  (`lib.rs:2802`), persisted to disk, and seeded true for every OpenBubbles migration (`lib.rs:815`).
- **The composer disagrees.** `nativeIsIMessage` fails **blue** (`lib.rs:3411`, `:3425`) on exactly
  the same error. During an IDS blip the UI shows a blue composer while the send goes out green —
  which is why this is hard to catch in testing.
- **It couples to §5.1.** A re-registration in flight puts the identity resource in
  `Generating`/`Failed`; `ensure_ready()` returns `Err`; sends during that window route green.
  That is a plausible mechanism linking "flaky after a restart" to "unexpected green bubbles",
  even though neither fired in this capture.

Neither OpenBubbles nor rustpush behaves this way. OB decides a chat's service **once**, at chat
creation, and never re-derives it at send time (`rustpush_service.dart:386`, `:437`); its IDS
pre-check fails to blue via a strict `== false` test on a nullable (`chat_creator.dart:176`, `:298`),
and it warns the user that a green result may just be rate limiting (`chat_creator.dart:187`).

**Recommended:** delete lines 3329-3331 so an error is never a negative — match `nativeIsIMessage`
and fail blue. A doomed iMessage encrypt surfacing as "Not Delivered" is a better failure than a
silent green.

**Secondary, lower priority:**
- `Ok(vec![])` on a *first-ever* lookup for a contact could be re-confirmed with `refresh: true`
  before it's allowed to go green, and the 1 h negative TTL needs a user-visible reset
  (OB has one; Smart Txt does not).
- The hand-rolled E.164 canonicaliser (`BlueBubblesModel.kt:110`, `group_identity.rs:41`) turns any
  non-10-digit string into `+<digits>`; a non-US contact in national format becomes a wrong number
  that IDS legitimately has no identity for → systematic (non-transient) green. OB routes through
  libphonenumber first (`rustpush_service.dart:141`). Low likelihood on a US-only product.
- Smart Txt never sets `MessageInst.target` for SMS sends (`lib.rs:2962`), so
  `get_participants_targets` fans out to every device on the handle; OB narrows it with
  `getSMSTargets` (`rustpush_service.dart:452`). Not a colour bug, but a duplicate-outbound risk on
  multi-device accounts.

---

## 6. What actually degraded this user's service

Not registration. **APS.**

```
logs1/segment-20260805-063238.log:155
08-04 08:29:46.651  5365  5526 E SmartTxtRust: rustpush::aps: failed to connect
Aps connection failed. You need to re-setup your device. Go to Settings -> Change Apple Hardware. … (2)!
```

- **436 occurrences**, all under PID 5365, spanning **08-04 08:29:46 → 08-05 06:04:28** (21.6 h).
- **Zero** occurrences under PID 1798 or PID 10810. The **device reboot cleared it.**
- 30 `APSSTATE: Failed` transitions in the capture.

This is the APS-refused lockout already written up in `OB_ENGINEER_CALL_BRIEF_APS_REFUSED.md`. It
matches the reported symptom shape ("unreliable, sometimes fixed by a restart") far better than
anything in the registration path — and it is worth noting the registration deadline stayed pinned
right through it, i.e. the device was in a broken-transport state with a perfectly healthy
registration.

**Also observed** (unrelated to either question, but worth a look): 54 `IDS DROP … body decrypted
but unparseable`, of which **39 are `cmd=147`** (`MessageReadOnDevice`, `messages.rs:1664`) and
8 are `cmd=190` (rename / participant-change / icon-change). These are read-state and group-metadata
sync from her iPhone being discarded, not lost messages — but 39 dropped read-sync events over 51 h
would explain notifications that don't clear.

---

## 7. How to verify all of this yourself

```bash
# — provenance —
git -C matrix-app rev-parse HEAD && git -C matrix-app status --porcelain   # expect ca6b518, clean
git clone --depth 1 https://github.com/TaeHagen/rustpush /tmp/rp-upstream
diff -u /tmp/rp-upstream/src/ids/user.rs            rustpush/src/ids/user.rs             # expect EMPTY
diff -u /tmp/rp-upstream/src/ids/identity_manager.rs rustpush/src/ids/identity_manager.rs # expect log-only + one 15000→30000

# — every re-register trigger in the app —
rg -n 'refresh_now|reregister|reconcile_handles' matrix-app/dpad-messenger-backend/smarttxt-ffi/src/lib.rs
rg -n 'reregisterOnce|fixConnection|healIfUnhealthy' matrix-app/dpad-messenger-backend/smarttxt/src/main/kotlin

# — OpenBubbles' trigger set (expect 3 UI call sites, nothing automatic) —
rg -n 'doReregister|do_reregister' /path/to/openbubbles-app

# — the logs —
rg -c 'REGSTATE'  logs*/            # expect 0   ← no identity state transition, ever
rg -c 'APSSTATE'  logs*/            # expect 48  ← proves the watcher + log level + ring all worked
rg -c 'Reregistering now!'  logs*/  # expect 0
rg -c 'Retrying'  logs*/            # expect 0   ← util.rs:1106, logged whenever a refresh is requested
rg -n  'Reregistering in' logs*/    # 15 lines; add N to each timestamp → all land on 2026-09-17 10:15:03
rg -n  'decide_route' logs*/        # 18 decisions: 17 blue, 1 green
rg -n  'validate failed' logs*/     # expect 0   ← the error→green branch never fired
rg -c  'You need to re-setup your device' logs*/   # 436, all PID 5365, cleared by the reboot
```

**One instrumentation gap to close.** The log ring attaches a beat after the process starts, so
`build_client_and_receive` — and therefore `reconcile_handles` — falls outside every capture we
have. Promote the reconcile verdict to a line that survives (or emit it again on the first inbound),
and the next bundle will settle at startup what §3 can currently only settle for steady state.

---

## 8. Bottom line for the call

1. **Your instinct about the code is right, and the specific mechanism is real:** we run a
   `possible_handles != registered_handles` check that rustpush only runs on Apple's `c == 66`
   push, we run it on every connect, and we drive it with `refresh_now()` — the one API that
   cancels rustpush's backoff. OpenBubbles adds nothing automatic here. That should be gated or
   removed.
2. **But it is not what happened to this user.** Zero re-registrations in 51 hours, proven two ways
   that don't depend on missing log lines: the registration deadline is pinned to
   `2026-09-17 10:15:03` ± 0.8 s across 11 samples, a device reboot, and an app update; and the
   registration-state watcher never fired once while its sibling APS watcher fired 48 times.
3. **Green only follows a status-0 answer from Apple** — rustpush hard-errors otherwise
   (`user.rs:993`), and the single green send in the capture was correct (that contact's own
   message arrived as a forwarded SMS 27 s earlier). **The exception is `decide_route`'s error
   branch** (`lib.rs:3329`), which routes green on a timeout or an IDS error. It didn't fire here,
   it exists in no other implementation, and it should go.
4. **What actually degraded this handset was APS**: 436 refused connects over 21.6 h, cleared by a
   reboot, with a perfectly healthy registration the whole time.
