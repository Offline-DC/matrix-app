# The boot path, and what the logs say about re-registration on reboot

Two separate things, kept separate on purpose:

1. **A map of everything that runs at boot**, and which parts can reach Apple's `register()`.
2. **What the 2026-08-04/06 logs actually prove** about her reboot.

There is no boot-specific suppression in the code. An earlier draft added a 10-minute
"boot grace" that deferred app-initiated registration after a reboot; it was removed. The
reasons are in §4 — the short version is that the logs showed nothing for it to prevent,
and it delayed recovery on exactly the handsets that most need it.

---

## 1. The boot sequence

There is **no `BOOT_COMPLETED` receiver anywhere** in the launcher or matrix-app
(`grep -rn BOOT_COMPLETED launcher/app/src matrix-app/` → nothing). The app starts at boot
because the launcher is the `android:persistent` HOME app, so Android launches it:

```
Android boot
  └─ DumbDownApp.onCreate                     launcher/…/DumbDownApp.kt:253
      └─ SmartTxtRepository.startBackgroundSyncIfRegistered   SmartTxtRepository.kt:460
          └─ Thread { … }                     ← everything below is on this one thread
```

That thread does exactly seven things. Every one traced to a leaf:

| # | Call | Reaches `register()`? | Gate |
|---|---|---|---|
| 1 | `store.isRegistered()` | No | reads EncryptedSharedPreferences |
| 2 | `OpenBubblesMigrator.sweepOnLaunchAsync` | No | uninstalls the OpenBubbles APK |
| 3 | `restoreStatus` | No | local status enum |
| 4 | `connect()` → `nativeConnect` → `build_client_and_receive` → `reconcile_handles` | **Yes** | actionable-direction test + 6 h persisted cooldown + `refresh()` |
| 5 | `registerNetworkRecovery` → `ensureClientUp` → same as #4 | **Yes** | same |
| 6 | `connectWithRetry` → `ensureClientUp` → same as #4 | **Yes** | same |
| 7 | `healIfUnhealthy` → `fixConnection` → `reregisterOnce` | **Yes** | client must be DEAD + 6 h persisted cooldown |

**No WorkManager, JobScheduler, or AlarmManager job exists** — the old 12 h renewal worker
was deleted (`SmartTxtRepository.kt:132-143`). The only other `register()` entry point,
`bridge.registerWithLogin`, has exactly one non-test caller — `SmartTxtSetupScreen.kt:148`,
the interactive sign-in screen. Unreachable from boot.

So four boot-path branches can register, and all four are gated by state (is there
actually an unregistered handle? is the client actually dead?) plus a 6 h persisted
cooldown. None is gated on *being a boot*.

## 2. rustpush can also register at boot, and we do not gate that

`IMClient::new` → `IdentityResource::new` evaluates rustpush's own `needs_refresh` and
registers immediately if it says so — inside `nativeConnect`, on the boot path. Causes:

- overdue `calculate_rereg_time_s` (the device was off past the deadline)
- a service `data_hash` change (an app upgrade with a new capability set)
- **a service with no stored registration at all** — the predicate is
  `user.registration.get(name)…unwrap_or(true)`, so this is true on *every* client build,
  i.e. a register on every connect and therefore every boot, until Apple returns it
- Apple push command 32 / 66, or an IDS 6005 on a lookup

This is the library's contract and we do not front-run or suppress it.

**Attribution rule.** Every app-initiated register announces itself at info immediately
before acting:

- `reconcile: … — requesting reregister via refresh() (backoff-preserving)` — `lib.rs`
- `registration is stale — self-healing with a silent re-register` — `SmartTxtRepository.kt:744`
- `nativeReregister: forcing IDS re-registration` — `lib.rs:2049` (Settings button)

**`Reregistering now!` with none of those above it ⇒ rustpush decided on its own.**

Caveat: rustpush's *own* reason lines are `debug!` for the hash-change and cmd-32 cases,
and the missing-service branch logs nothing at any level. Our logger caps at Info, so the
sub-reason within a library-initiated register is not recoverable from a bundle. That is
accepted rather than patched — rustpush is kept byte-identical to what OpenBubbles ships.

## 3. What the logs prove about her reboot

**Nothing re-registered across it, and this is positive evidence, not absence.**

The device rebooted between `08-05 06:05:51` (last line of PID 5365) and `08-05 08:44:27`
(first line of PID 1798). The registration countdown ticks straight through:

| | timestamp | N | implied deadline |
|---|---|---|---|
| last before reboot | 08-05 03:23:15.849 (PID 5365) | 3739908 | 2026-09-17 **10:15:03.849** |
| first after reboot | 08-05 11:47:16.698 (PID 10810) | 3709667 | 2026-09-17 **10:15:03.698** |

Wall clock between those lines: 30240.849 s. Countdown decremented: 30241 s.
**Deadline drift across the reboot: 0.151 s.**

`calculate_rereg_time_s()` derives from `registered_at_s`, which a successful `register()`
overwrites (`ids/user.rs:1172`). Had one completed anywhere in that gap — including during
boot — the deadline would have jumped ~45 days, to roughly 09-19. It did not move.

Corpus totals, deduplicated, 51 h across three processes:

| signal | count |
|---|---|
| `Reregistering now!` | **0** |
| `REGSTATE` (identity state transitions) | **0** |
| `reconcile:` | **0** |
| `decide_route` | 15 |
| `validate failed` | 0 |
| `Aps connection failed …(2)` | **436** |
| `IDS DROP` | 38 |

**What is weaker.** The deadline argument covers a *completed* re-registration only. A
failed attempt leaves `registered_at_s` untouched. There, the evidence is indirect: PID
1798 ran 50 min post-reboot with the client provably up (sends at 08:56/08:57/08:58), so
`spawn_regstate_watcher` was running; a failed register sets `ResourceState::Failed` and
the ladder retries at +5 min, both of which are watch-channel transitions logged at info.
Zero `REGSTATE` lines in that window, or the following 25.8 h.

**Honest caveat on that:** `REGSTATE` is 0 in *every* segment, including the initial emit
expected at client build — because that emit lands before the log ring's reader attaches.
So the watcher's liveness is inferred from the client's liveness, not observed directly.
`spawn_startup_summary` (`STARTUP:` at +30 s and +5 min) exists to close that gap in the
next bundle.

## 4. Why there is no boot grace

It was written and then removed. Both halves are worth recording:

**The case for it.** Reboots correlate across a fleet (an OTA, a power cut), and a
per-device cooldown does nothing about every device acting in the same minute. At boot the
radio is often still attaching, so a register launched then is unusually likely to fail —
and a failure is worse than not trying, because it drives the identity resource to
`Failed`, which blocks sends through `ensure_ready`.

**Why it lost.**

1. **No observed problem.** §3 proves no re-registration happened on her reboot. The grace
   would have changed nothing on the one handset with real data.
2. **It delayed recovery on the handsets that need it most.** `healIfUnhealthy` fires only
   when the client is *dead*. Adding a 10–40 min wait in front of it means a phone that
   booted broken stays broken longer, to prevent a burst that has never been observed.
3. **It duplicated a control that already exists.** Every boot-path branch is already
   gated on state plus a 6 h persisted cooldown. A reboot loop cannot produce more than
   one attempt per 6 h per device with or without the grace.
4. **It did not cover the case most likely to actually cause a boot register** — the
   missing-service branch in §2, which is rustpush's decision and not app-initiated.

If fleet-correlated reboots ever show up as real (a spike in registers clustered minutes
after an OTA), the grace is the right answer and this section is the design. It is not
carried speculatively.

## 5. How to verify

```bash
grep -rn "BOOT_COMPLETED" launcher/app/src matrix-app/          # expect nothing
sed -n '/fun startBackgroundSyncIfRegistered/,/^    }$/p' matrix-app/.../SmartTxtRepository.kt
grep -rn "WorkManager\|PeriodicWork\|OneTimeWork" matrix-app/dpad-messenger-backend/smarttxt/src/main
grep -rn "registerWithLogin" --include=*.kt matrix-app/         # one non-test caller

cd matrix-app/dpad-messenger-backend/smarttxt-ffi/policy-tests && cargo test
```

On-device: reboot, wait 60 s, export logs, then
`rg "reconcile:|self-heal|STARTUP:|Reregistering now!" smarttxt-logs/`.
Expect a `reconcile:` verdict and a `STARTUP:` line at +30 s. If `Reregistering now!`
appears without an app-side line above it, it was rustpush — see §2.
