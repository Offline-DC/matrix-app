# Logging sufficiency assessment

Is Smart Txt's current logging enough to diagnose issues from a bundle? **Mostly
yes for the common cases (handles / threading / routing / registration) once you
strip the noise — but the signal-to-noise ratio is the real problem, and one
always-on capture is diluted enough to lose history fast.** These are
recommendations to review, not applied changes.

## Finding 1 (highest impact): the noise ratio

> **Since updated.** The measurements below describe the logger as it was when this
> was written: one flat `LevelFilter::Info` with no per-target directives. It is now
> a global ceiling of `Debug` plus an `env_logger` filter that holds the catch-all at
> `Info` and pins `rustpush::util` / `rustpush::aps` to `Warn`, so the two floods
> below are silenced at the source rather than dropped downstream. `SmartTxtLogRing`
> still filters them from older captures. Read the ratios as the case for that change,
> not as the current state.
>
> Two gates decide whether a line appears, and both must pass: the ceiling
> (`with_max_level`, which sets `log::set_max_level` and is checked inside the
> `debug!` macro before it formats anything) and the per-target filter. Enabling one
> module's debug output means raising the ceiling **and** naming the module — that is
> the usual reason an added `debug!` never shows up.

The FFI's `android_logger` ran at `LevelFilter::Info` (lib.rs:239), and rustpush
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

**Fixes:**
- **(A) ✅ SHIPPED — filter the always-on ring in-process.** `SmartTxtLogRing` now
  drops any line containing `rustpush::util:` or `rustpush::aps:` in the tail loop
  (`isNoise`, before `writeLine`), so the ~5 MB window is ~20× denser in signal and
  future Export-logs / Report-error bundles arrive pre-stripped. It doesn't touch
  what the manual capture scripts or a full logcat can still see, so deadlock
  captures (`smarttxt-stuck-capture.sh`, raw) keep the lock traces.
- **(B) Source-level, bigger blast radius — quiet the modules globally.** Give the
  FFI `android_logger` a per-target filter (android_logger 0.13 `Config::with_filter`
  with an env-logger-style directive, e.g. `rustpush::util=warn,rustpush::aps=warn`).
  This helps battery and *all* captures, but it removes the lock traces everywhere,
  which the "stuck/deadlock" class occasionally needs. Do (A) first; consider (B)
  only if battery beats deadlock-diagnosability, ideally behind a build flag.
- **(C) ✅ SHIPPED — warm the ring on app entry.** `SmartTxtLogRing.ensureStarted`
  now runs at the top of `SmartTxtApp` (before the sign-in gate), not only in the
  signed-in chat screen, so the ring is already capturing on the login/setup screen.
  This closes a cold-ring race where the *first* "Report error" tap on a login
  failure returned a stale window (the exporter zips right after starting a cold
  ring, beating its first logcat buffer dump). **Diagnostic tell:** if a bundle's
  newest log line is hours older than `meta.txt` `capturedAtMs`, it was captured
  cold/stale — ask for a second capture (or the build predates this fix).

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

1. ✅ **DONE — `rustpush::util` / `rustpush::aps` filtered out of `SmartTxtLogRing`**
   (Finding 1A). Export-logs / Report-error bundles are now signal-dense at the source.
2. **Always log `possible=[…] registered=[…]` at login/reconcile** (Finding 2, #1)
   — turns the most common investigation into a one-line read. **← now the top open item.**
3. Log the per-user handle list at rustpush register time (Finding 2, #2).
4. Add the IDS error to empty-`valid` route lines; add a handle-set-changed diff
   (Finding 2, #3–4) — nice-to-have.

Net verdict: **current logging is ~sufficient for the common cases after noise
stripping. The highest-value change — cutting the noise in the always-on ring — is
now shipped; the top remaining item is one always-on possible-vs-registered handle
line at login.**
