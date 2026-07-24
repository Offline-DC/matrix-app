# Log signals cheatsheet

The exact lines worth grepping, what each means, and where it's emitted. All Rust
lines are logcat tag `SmartTxtRust`; the `module:` shown is the target in the body.
Line numbers are `smarttxt-ffi/src/lib.rs` and `rustpush/src/…` at the pinned revs.

## Always start here (strip the ~95% noise)

```bash
cat current.log segment-*.log 2>/dev/null \
  | grep -aE ' SmartTxtRust: (smarttxt_ffi|rustpush::(ids|imessage|macos_remote)::)' \
  | grep -avE 'rustpush::util|rustpush::aps' > signal.log
```

`rustpush::util` (mutex lock/unlock) and `rustpush::aps` (APNs internals) log at
INFO and are pure noise for everything except a **deadlock** investigation — there,
keep `rustpush::util` and look for a `Locked …` mutex with no matching `dropped`.

## Connect / lifecycle

| grep | meaning | source |
|---|---|---|
| `nativeInit ok` | native identity built (dumb/NAC) | lib.rs:1231 |
| `nativeConnect ok` / `nativeConnect: already connected` | APNs up | lib.rs:1320 / 1248 |
| `nativeConnect: APNs attempt N/4 failed` / `APNs failed` | APNs trouble | lib.rs:1288 / 1301 |
| `boot connect attempt N/…` (Kotlin `IMsgRepo`) | boot retry ladder ran (not needed on a healthy boot) | SmartTxtRepository |
| `network recovery callback registered` | reconnect watcher armed | SmartTxtRepository:536 |
| `unhealthy on launch` / `self-heal…` | launch-time health check tripped | SmartTxtRepository:589+ |
| `receive loop ended (gen N)` | the receive loop stopped | lib.rs:1967 |

## Login / registration

| grep | meaning | source |
|---|---|---|
| `nativeAuthenticate: begin GSA login` / `login_email_pass → {state}` | sign-in start / GSA result | lib.rs:1441 / 1460 |
| `2FA verify → …` / `FSA verify → …` | 2FA / security-key result | lib.rs:1578 / 1644 |
| `nativeRegister: [1/3]…[3/3]` | IDS auth → authenticate → register (NAC validation at 3/3) | lib.rs:1695-1706 |
| `registering!` (`rustpush::ids::user`) | rustpush registering the vended handles | user.rs:1036 |
| `Failed to register {uri} status {N}` | per-handle IDS rejection (hard-fails the whole register) | user.rs:1163 |
| `REGISTERED with iMessage ✅ handles=[…]` | **the registered handle set** — the money line | lib.rs:1746 |
| `registration failed: {msg}` (Kotlin `IMsgRepo`) | Kotlin saw a Failure result | SmartTxtRepository:315 |
| `RUST PANIC: …` + `backtrace:` | native panic (hook-logged) | lib.rs:261-262 |

## Handles / reconcile (read `handles-registration-openbubbles.md`)

| grep | meaning | source |
|---|---|---|
| `nativeReregister: ✅ re-registered handles=[…]` | manual reregister returned this set | lib.rs:1781 |
| `nativeReconcileHandles: handles=[…]` | reconcile entrypoint result | lib.rs:1806 |
| `reconcile: IDS vends handles we haven't registered (possible=… registered=…)` | drift detected → refreshing | lib.rs:2776 |
| `reconcile: registered handles already match IDS` | no drift (if a handle's still missing → account-side) | lib.rs:2783 |
| `reconcile: get_possible_handles failed (…)` | couldn't check (kept current) | lib.rs:2784 |
| `Reregistering now!` / `Successfully reregistered!` | rustpush reregister lifecycle | identity_manager.rs:363 / 389 |
| `New handles; reregistering! {real} {my}` | command-66 handles-changed → reregister | identity_manager.rs:744 |
| `Auth returns 6005, relog required!` / `IDS returned 6005; attempting to re-register` | 6005 auth path | identity_manager.rs:374 / 695 |
| `No identity for payload! {err}` | HandleNotFound — inbound for an unregistered handle | identity_manager.rs:790 |
| `Reregistering in {N} seconds` | scheduled renewal timer (large N is normal) | identity_manager.rs:500 |

## Receive / threading (the thread-fork detector)

`recv msg: guid=… ts=… sender=… is_from_me=… is_group=… counterparts=[…] cv_name=… chat_guid=…`
(lib.rs:2297). Also `recv read-on-device/peer-read-receipt: … counterparts=[…]`
(lib.rs:2135) and `recv tapback: … chat=…` (lib.rs:2227).

- **Read `counterparts` on a 1:1**: the user's OWN handle must NOT be present. If it
  is → phantom-self bug (stale/missing handle) → chats fork. See handles doc.
- `chat_guid=iMessage;-;<other>` = 1:1; `iMessage;+;<gid>` = group (keyed by Apple's
  stable gid). Same members but different gid ⇒ intentionally distinct rooms.

## Send / route

| grep | meaning | source |
|---|---|---|
| `decide_route[recipient]: on_imessage=… is_phone=… sms_active=… valid=[…]` | routing decision (`valid=[]` ⇒ IDS had no key) | lib.rs:2881 |
| `decide_route[…]: validate failed (…) — forwarding on, routing SMS` | fell back to SMS | lib.rs:2873 |
| `nativeSetSendHandle: {h}` | default send-from handle applied | lib.rs:2912 |
| `nativeSendText: send failed: {e}` | outbound iMessage send error | lib.rs:2559 |

## Renewal / migration / Kotlin tags

- Renewal (`IMsgRenewal`): `running SmartTxt renewal`, `renewal ok (registered …)`,
  `renewal failed: {msg} — will retry`.
- `fixConnection` ladder (`IMsgRepo`): `fixConnection attempt …`, `fixConnection: recovered`,
  `fixConnection: re-register failed: …`.
- Migration (`ObMigrator`): `════ MIGRATION START ════`, `MIGRATION OK`,
  `MIGRATION FAILED: {reason}`, `MIGRATION CRASHED`, `handles=… appleId=…`.
- Log plumbing (`IMsgLogRing` / `IMsgLogExport`): `Smart Txt log ring started at …`,
  `Bundle ready: N log files, …`, `Uploaded {key} …`.

## Kotlin tag legend

`IMsgRepo` (repository/lifecycle), `IMsgRenewal` (renewal worker), `IMsgSession`,
`IMsgRelayHttp`/`IMsgRelayWS`/`IMsgRelayStub`/`IMsgMockRelay` (transports),
`IMsgNativeTransport`, `IMsgContacts`, `IMsgCache`, `IMsgRepoHolder`,
`RustPushNative`/`RustPushBridge` (JNI glue), `ObMigrator` (migration),
`IMsgLogRing`/`IMsgLogExport` (the always-on log ring + export).

## "Is this the read-live build?"

- `grep -a 'reconcile:' signal.log` returns lines (reconcile exists only in the fix).
- `counterparts` on 1:1s never contains the user's own handle.
- `nativeReconcileHandles` appears. If none of these hold, you may be looking at a
  pre-fix `.so` — check `meta.txt` app version.
