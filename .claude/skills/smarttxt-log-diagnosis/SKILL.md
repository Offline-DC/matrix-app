---
name: smarttxt-log-diagnosis
description: >-
  Diagnose and fix Smart Txt (iMessage-on-a-flip-phone) issues from a user's
  logs. Use this whenever someone shares a Smart Txt log bundle (an "Export
  logs"/"Report error" zip, a rolling-logcat dump, or an adb capture) or reports
  a Smart Txt problem: missing or duplicated chats, "group chat with myself",
  can't start a new message from their phone number, "Not Delivered", stuck /
  won't connect, 2FA / FSA / registration failures, or OpenBubbles-migration
  trouble. Encodes the launcher + matrix-app + rustpush architecture, the handle
  and registration lifecycle, how OpenBubbles' rustpush core behaves, the exact
  log signals to grep for, and the known failure modes with their fixes.
---

# Smart Txt log diagnosis

You are diagnosing Smart Txt, a flip-phone iMessage client. Someone has (or is
about to have) an issue and a log bundle. Your job: from the logs, find the root
cause fast, decide whether it's client-side or account-side, and propose a fix
as a reviewable diff. This skill is the fast path — it front-loads the
architecture, the OpenBubbles comparison, and the exact log lines so you don't
have to re-derive them under time pressure.

## 30-second model

- **Smart Txt** = the launcher app (`com.offlineinc.dumbdownlauncher`) hosting a
  chat UI from **matrix-app**, backed by **rustpush**, the Rust iMessage engine,
  loaded over JNI as `libsmarttxt_ffi.so`.
- **rustpush** here is `Offline-DC/rustpush`, forked from **`OpenBubbles/rustpush`**
  (the `upstream` remote). So the load-bearing iMessage logic *is* OpenBubbles'
  own source. "How does OpenBubbles do it?" almost always means "what does this
  rustpush do by default" — you can read it directly in `rustpush/src/`.
- **Golden rule:** the handles a user can send *as* (their numbers/emails) are
  owned by rustpush/IDS. Read them **live** from rustpush; never trust a cached
  snapshot. The largest class of Smart Txt bugs came from a stale handle snapshot;
  the fix was to read live. See `reference/handles-registration-openbubbles.md`.

## Before you read a single log line: strip the noise

The Rust engine logs **every mutex lock/unlock** (`rustpush::util`) and APNs
internals (`rustpush::aps`) at INFO — on a raw capture ~95% of all lines
(measured: 31,440 `rustpush::util` + 5,405 `rustpush::aps` out of ~39,000; the
useful `smarttxt_ffi` lines were 149).

As of the current build the always-on ring (`SmartTxtLogRing`) drops those two
sub-streams at the source, so **Export-logs / Report-error bundles are already
signal-dense** and the filter below is a near no-op on them. **Raw `rolling-logcat`
dumps and manual `adb` captures still carry the full flood**, so run it anyway
(it's harmless when there's nothing to strip):

```bash
# gunzip any segment-*.log.gz first.
cat current.log segment-*.log 2>/dev/null \
  | grep -avE 'rustpush::util:|rustpush::aps:' \
  > signal.log
```

This keeps everything else — the Rust signal AND the Kotlin-tag lines
(`IMsgRepo`, `IMsgRenewal`, `ObMigrator`, …) the tree relies on. For a
Rust-engine-only view, append `| grep -aE ' SmartTxtRust: '`.

Every grep below runs on `signal.log`. (Exception: a *deadlock / stuck* bug is the
one case where the `rustpush::util` lock traces matter — see that symptom.)

> All Rust logs share one logcat tag, `SmartTxtRust`; the module rides in the
> message body, e.g. `SmartTxtRust: rustpush::ids::identity_manager: New handles…`.
> Kotlin logs use their own tags (`IMsgRepo`, `IMsgRenewal`, `RustPushNative`,
> `RustPushBridge`, `ObMigrator`, `IMsgLogRing`, `IMsgLogExport`, …).

> **Privacy:** bundles contain real phone numbers, emails, message GUIDs, group
> names, and sometimes message text. Treat every bundle as sensitive PII.

## Intake

Three bundle shapes, all the same underlying logcat:

1. **Export logs / Report error zip** (`smarttxt-logs-*.zip`): `current.log` +
   `segment-*.log` + `meta.txt`. The always-on rolling ring (Smart Txt tags only,
   ~5 MB window). Read `meta.txt` first — it has package, app version, device,
   Android version, capture time.
2. **rolling-logcat dump**: `current.log` + `segment-*.log.gz` (+ `segment-pre-*.log`).
   `gunzip` the segments.
3. **Manual adb capture** (`smarttxt-verify.sh`, `smarttxt-coldstart-capture.sh`,
   `smarttxt-stuck-capture.sh` in the repo root): `raw.txt` and filtered outputs.

First moves, in order: read `meta.txt`; build `signal.log`; then run the one
query that unlocks most issues (below).

## The question that unlocks most issues: what handles are registered?

```bash
grep -aE 'REGISTERED with iMessage|re-registered handles|nativeReconcileHandles: handles' signal.log
# → handles=["mailto:…@icloud.com", "mailto:…@gmail.com", "tel:+1…"]
```

Compare that set to what the user says they have (iPhone → Settings → Messages →
Send & Receive). **Most Smart Txt "bugs" are one handle — usually the phone
number — missing from this set.** From here, follow the tree.

## Decision tree by symptom

### "Can't start a new message from my number" / my number isn't in the picker
The picker shows the registered handle set, so this is governed purely by whether
`get_handles()` includes the number.
- `grep 'REGISTERED with iMessage' signal.log` — is `tel:+<their number>` present?
  - **Absent** → it wasn't vended by IDS (`id-get-handles`) at registration time.
    Check for a reconcile / reregister: `grep -aE 'reconcile:|New handles; reregistering|Reregistering now' signal.log`.
    - `reconcile: registered handles already match IDS` (and still no number) →
      IDS genuinely isn't vending the number for this account → **account-side**.
      Confirm the Apple ID has the number under Send & Receive; compare `id-get-handles`
      to a known-good user. A freshly provisioned Mac does **not** need to "offer"
      the number — that is not the requirement. No client change conjures a handle
      IDS won't vend.
    - Otherwise → likely a **transient** first-login miss. A fresh login re-queries
      `id-get-handles` and usually fixes it; reconcile-on-login now catches it too.
  - **Present** → the engine has it; look upstream at the account store / picker
    (`SmartTxtAccountStore`, `HandlePickerScreen`, `SmartTxtApp` `sendHandles`).
- See `reference/handles-registration-openbubbles.md` for the full lifecycle.

### Duplicate chats / "group chat with myself" / a 1:1 forks into groups
A phantom self-handle is riding into `counterparts`. counterparts =
participants − my_handles (live). If one of the user's OWN handles is missing from
the live set, it isn't subtracted → it looks like an extra participant → the 1:1 is
promoted to an ad-hoc group and forks by group id.
```bash
grep -a 'recv msg' signal.log        # inspect counterparts=[...] on 1:1s
```
- **Healthy 1:1** (self absent from counterparts):
  `recv msg: … sender=tel:+1SELF is_from_me=true is_group=false counterparts=["+1OTHER"] chat_guid=iMessage;-;+1OTHER`
- **Bug**: `counterparts` on a 1:1 contains the user's own number/email next to the
  real other party.
- Root fix is already shipped (my_handles read live per receive + reconcile on
  login). If you still see self in counterparts, confirm the installed `.so` is the
  read-live build (`grep -a 'reconcile:' signal.log` should show reconcile ran).

### "Not Delivered" / send fails
```bash
grep -aE 'decide_route|No identity for payload|IDS returned zero keys' signal.log
```
- `decide_route[…]: on_imessage=false … valid=[]` → IDS returned no key for the
  recipient (not on iMessage, or a lookup blip). For a phone with SMS forwarding
  live it routes SMS; otherwise it defaults to iMessage.
- 6005: `grep -aE 'Auth returns 6005|IDS returned 6005|identity closed \(6005\)' signal.log`.
  6005 = bad auth; the engine re-logs + re-registers. A few are normal; a tight loop
  means auth/anisette is broken (see registration).

### Stuck / won't connect / spinner forever
```bash
grep -aE 'nativeConnect|session connect|boot connect attempt|unhealthy on launch|fixConnection|self-heal' signal.log
```
- Look for `nativeConnect ok` (or its absence), the boot-connect retry ladder, and
  the self-heal path.
- **Deadlock class** — the ONE time you keep `rustpush::util`: capture with
  `smarttxt-stuck-capture.sh` (it preserves the lock traces) and look for a mutex
  `Locked …` with no matching `dropped`.

### Registration / sign-in fails (2FA, FSA, NAC)
```bash
grep -aE 'nativeAuthenticate|login_email_pass|2FA verify|FSA verify|nativeRegister|registering!|Failed to register|RUST PANIC' signal.log
```
- `Failed to register {uri} status {N}` = per-handle IDS rejection.
- NAC / validation problems cluster around `nativeRegister: [3/3]`.
- anisette/provisioning: `grep -aiE 'anisette|provision' signal.log`.

### OpenBubbles → Smart Txt migration
Migration logs use the Kotlin `ObMigrator` tag (not `SmartTxtRust`):
```bash
grep -aE 'ObMigrator|MIGRATION (START|OK|FAILED|CRASHED)' *.log
```
See the migration notes in `reference/architecture.md`.

## Making the fix

Propose a diff for review — do not apply silently. Each proposal states:
1. **Root cause** in one line, with the exact log evidence (file + line ranges from
   the bundle, and the relevant source `file:line`).
2. The **minimal diff**. Prefer the read-live-from-rustpush shape over adding
   another cache. Reproduce OpenBubbles behaviour rather than inventing iMessage
   logic it lacks.
3. **What log line would have made this faster** (feed it into
   `reference/logging-gaps.md`).
4. **Build impact**: Kotlin-only → APK rebuild; rustpush/FFI → `.so` rebuild (and
   whether rustpush must be bumped). The handle read-live fix needed no rustpush bump.

## Reference (read as needed)

- `reference/architecture.md` — repos, modules, the JNI surface, the runtime model,
  and every key flow with the log line that marks it.
- `reference/handles-registration-openbubbles.md` — the handle + registration
  lifecycle, IDS, reconcile, and exactly how OpenBubbles' rustpush core behaves
  (with `file:line`). Start here for anything handle-shaped — most issues are.
- `reference/log-signals-cheatsheet.md` — every diagnostic log line, what it means,
  the grep for it, and its source location.
- `reference/logging-gaps.md` — is current logging sufficient? The noise problem,
  concrete proposed additions, and what's already good enough.
