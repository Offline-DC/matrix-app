# Handles, registration, and how OpenBubbles does it

Most Smart Txt issues are handle-shaped. This is the deep model. Line numbers are
against the pinned `rustpush` rev and the current `smarttxt-ffi/src/lib.rs`; re-grep
if they've drifted.

## Vocabulary

- **`get_handles()`** — `rustpush/src/ids/identity_manager.rs:449`. The handles the
  account is **registered** to send/receive as, right now. Local, cheap, synchronous
  read of registration state.
- **`get_possible_handles()`** — `identity_manager.rs:454`. What IDS says the account
  **could** register (the Apple-vended alias set). A network call
  (`user.rs:839` per-user, aggregated `user.rs:1038-1058`).
- A handle is a URI: `tel:+1NXXNXXXXXX`, `mailto:name@icloud.com`, `mailto:name@me.com`.

## Registration (why a handle goes missing)

- `register()` registers exactly what `id-get-handles` vends per user
  (`user.rs:1036 "registering!"`, per-URI at `~1058`). A per-URI failure
  **hard-fails the whole registration** (`user.rs:1163 "Failed to register {uri} status {N}"`),
  so it cannot silently drop just one handle.
- Therefore: **a handle missing from `get_handles()` means it wasn't in the
  `id-get-handles` response at register time.** Two causes: a transient/partial IDS
  result (common, especially at first login), or the account genuinely isn't vending
  it (rare, account-side).

## Reconcile — the OpenBubbles mechanism (reactive, in rustpush)

rustpush keeps registration current by **reacting** to IDS, not by trusting a
snapshot. In `identity_manager.rs`:

- **Handles-changed push (command 66):** on an inbound IDS control message,
  `Got reregister command, reregistering!` (`:736`) / `IDS said handles changed`
  (`:740`) → it computes `real_handles = get_possible_handles()` (`:742`) and, if
  they differ from the current set, `New handles; reregistering! {real} {my}`
  (`:744`) → reregister. **This is the canonical "possible != mine → reregister"
  logic.** It is *reactive* (push-triggered), not a login-time sweep.
- **6005 (bad auth) path:** `IDS returned 6005; attempting to re-register` (`:695`),
  `Auth returns 6005, relog required!` (`:374`), `Reregistering now!` (`:363`),
  `Successfully reregistered!` (`:389`).
- **Scheduled renewal:** `Reregistering in {N} seconds` (`:500`, `:511`) — a large N
  (~45 days) is the normal periodic timer, not a problem.
- **No identity for payload** (`:790`) = `HandleNotFound`: an inbound referenced a
  handle we haven't registered — the classic symptom of a dropped handle.

How OB stays correct end-to-end: rustpush fires a **`keys_updated`** callback on
every reregistration (automatic *and* manual) carrying the fresh user/handle set.
The OpenBubbles app consumes it, so a handle that appears on a later reregister
propagates into app state automatically. It never depends on a login-time snapshot.

## How Smart Txt diverged — and the fix (the Annie class)

**Divergence (the bug):** Smart Txt historically kept a login-time snapshot
(`AppState.my_handles` / `self_handles`). The receive path subtracted *that* snapshot
from participants. Automatic reregisters (6005 / command-66) updated rustpush but
**not** the snapshot (`keys_updated` was dropped; `nativeReregister` fetched handles
and discarded them). So when the phone number landed on a later reregister, nothing
on the Smart Txt side saw it. Two visible symptoms from one gap:
- **Thread forking / "group chat with myself":** with the user's own handle missing
  from the snapshot, it wasn't subtracted → it rode into `counterparts` as a phantom
  participant → 1:1s were promoted to ad-hoc groups and forked by gid.
- **Number missing from the send-from picker:** the picker reads the registered set;
  the number simply wasn't in it.

**Fix (shipped, matrix-app only — no rustpush change):**
- Removed the snapshot. `my_handles` is read **live** at each receive
  (`lib.rs` receive loop: `let my_handles = client.identity.get_handles().await`
  before `push_relay_event(msg, &my_handles)`), and at send/route
  (`decide_route` / `sms_route` read `client.identity.get_handles().await`).
- Added `reconcile_handles(client)` (`lib.rs ~2771`): compares
  `get_possible_handles()` vs `get_handles()` and, if they differ,
  `refresh_now()`. Logs `reconcile: IDS vends handles we haven't registered
  (possible=… registered=…) — reregistering` / `reconcile: registered handles
  already match IDS` / `reconcile: get_possible_handles failed (…)`. Called on login
  and exposed as `nativeReconcileHandles`.
- Net: Smart Txt now matches OB's "trust rustpush, stay current, don't cache" model,
  reproducing the propagation OB gets from `keys_updated` without keeping a snapshot.

## Validated healthy baseline (from a real capture)

A real bundle (owner's own device) shows the fix working:
```
REGISTERED with iMessage ✅ handles=["mailto:…@icloud.com","mailto:…@gmail.com","tel:+1XXXXXXXXXX"]
recv msg: … sender=tel:+1SELF is_from_me=true is_group=false counterparts=["+1OTHER"] cv_name=None chat_guid=iMessage;-;+1OTHER
```
All three of the owner's handles are registered, and on a 1:1 the owner's own number
is **absent** from `counterparts` (correctly subtracted). That's what "healthy" looks
like: use it as the reference when reading a new bundle.

## Client-side vs account-side (the call you must make)

- If `get_handles()` is missing a handle but `reconcile`/`get_possible_handles` shows
  IDS *can* vend it → client/transient. A fresh login (re-queries `id-get-handles`)
  and/or the reconcile path resolves it.
- If `get_possible_handles()` genuinely doesn't include it (reconcile says
  "already match IDS" and the number still isn't there) → **account-side**. No client
  change conjures a handle IDS won't vend. Verify: Apple ID → Send & Receive lists the
  number; compare `id-get-handles` to a known-good user. Note: we provision a Mac for
  users; that Mac does **not** need to have the number toggled on — plenty of users
  register fine without it, so "the Mac doesn't offer the number" is not the cause.
