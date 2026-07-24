# Logging sufficiency assessment

Is Smart Txt's current logging enough to diagnose issues from a bundle? **Mostly
yes for the common cases (handles / threading / routing / registration) once you
strip the noise — but the signal-to-noise ratio is the real problem, and one
always-on capture is diluted enough to lose history fast.** These are
recommendations to review, not applied changes.

## Finding 1 (highest impact): the noise ratio

The FFI's `android_logger` runs at `LevelFilter::Info` (lib.rs:239), and rustpush
logs **every mutex lock/unlock** under `rustpush::util` and APNs internals under
`rustpush::aps` at INFO. Measured on one real bundle:

| module | lines | share |
|---|---|---|
| `rustpush::util` | 31,440 | ~80% |
| `rustpush::aps` | 5,405 | ~14% |
| everything else (signal) | ~2,100 | ~5% |
| — of which `smarttxt_ffi` | 149 | ~0.4% |

Impact:
1. **The always-on `SmartTxtLogRing` (Export logs / Report error) is ~95% noise.**
   Its ~5 MB window therefore holds only a *short* span of actual signal — exactly
   the recent handle/registration/threading history you most want is evicted fastest.
   This directly reduces the value of the "Report error" bundles this whole workflow
   depends on.
2. Battery/IO cost of writing the flood continuously (the `smarttxt-coldstart-capture.sh`
   author already hit this: the util flood made a piped reader fall behind).
3. Every diagnosis must pre-filter (this skill does), which is fine for a human but
   wastes the ring's fixed budget.

**Proposed fixes (review):**
- **(A) Cheapest, highest value — filter the always-on ring in-process.** In
  `SmartTxtLogRing.writeLine(...)`, drop lines whose body contains `rustpush::util:`
  or `rustpush::aps:` before writing. That is a ~2-line change scoped to the ring;
  it does not touch what the manual capture scripts or a full logcat can still see,
  and it makes the exported window roughly **20× denser in signal**. Recommended.
  (Deadlock captures already use `smarttxt-stuck-capture.sh`, which pulls raw logcat
  and keeps the lock traces — so nothing needed for deadlocks is lost.)
- **(B) Source-level, bigger blast radius — quiet the modules globally.** Give the
  FFI `android_logger` a per-target filter (android_logger 0.13 `Config::with_filter`
  with an env-logger-style directive, e.g. `rustpush::util=warn,rustpush::aps=warn`).
  This helps battery and *all* captures, but it removes the lock traces everywhere,
  which the "stuck/deadlock" class occasionally needs. Do (A) first; consider (B)
  only if battery beats deadlock-diagnosability, ideally behind a build flag.

## Finding 2: gaps (signals we wish the bundle had)

- **`get_possible_handles()` is only logged when it *differs* from registered**
  (the `reconcile:` mismatch line, lib.rs:2776). So "is this number vendable for this
  account?" can't be answered from a bundle where reconcile found no drift. *Propose:*
  in `reconcile_handles` / at login, always log `handles: possible=[…] registered=[…]`
  once per boot (even when equal). This single line settles transient-vs-account for
  the #1 issue class without needing a repro that trips a mismatch.
- **The `id-get-handles` / `id-register` result isn't logged at INFO.** When a handle
  is dropped, the bundle can't show what IDS actually returned. *Propose:* at
  `user.rs` register (`registering!`, :1036) log the resulting per-user handle list
  (INFO), so a drop is visible at its source, not just inferred downstream.
- **Route failures log `valid=[]` but the *reason* only on the SMS-fallback path.**
  The `on_imessage=false` default-to-iMessage branch (lib.rs:2876 logs the error;
  confirm the primary success/`valid=[]` line also carries the IDS error when the set
  is empty). *Propose:* include the lookup error in the `decide_route … valid=[]` line.
- **No explicit "handle set changed vs last boot" line.** Now that handles are read
  live, a one-line diff when the live set changes would make regressions/relapses
  obvious in a glance. *Propose:* log `handles changed: +[…] -[…]` when a receive/route
  read differs from the previously seen set.

## Finding 3: what's already sufficient (don't add)

- **Receive/threading**: `recv msg: … counterparts=[…] cv_name=… chat_guid=…`
  (lib.rs:2297) is enough to catch the phantom-self / thread-fork bug directly. Keep.
- **Reregister lifecycle**: `Reregistering now!` / `Successfully reregistered!` /
  `New handles; reregistering!` / 6005 lines cover the OB reconcile path well.
- **Registration steps**: `nativeRegister [1/3..3/3]` + `REGISTERED … handles=[…]`
  + `Failed to register {uri} status` localize sign-in failures cleanly.
- **Route decision**: `decide_route[…]: on_imessage=… valid=[…]` answers the
  "why not delivered" question (modulo the reason-string gap above).

## Prioritized recommendation

1. **Filter `rustpush::util` / `rustpush::aps` out of `SmartTxtLogRing`** (Finding 1A)
   — biggest bang, tiny diff, directly improves the Report-error bundles.
2. **Always log `possible=[…] registered=[…]` at login/reconcile** (Finding 2, #1)
   — turns the most common investigation into a one-line read.
3. Log the per-user handle list at rustpush register time (Finding 2, #2).
4. Add the IDS error to empty-`valid` route lines; add a handle-set-changed diff
   (Finding 2, #3–4) — nice-to-have.

Net verdict: **current logging is ~sufficient for the common cases after noise
stripping; the highest-value change is not *more* logging but *less noise* in the
always-on ring, followed by one always-on possible-vs-registered handle line.**
