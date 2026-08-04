# Smart Txt: first-boot-after-update storm, and the missing catch-up indicator

**Date:** 2026-08-04
**Device:** TCL Flip 2 (4058-series), Android 11, 938 MB RAM, rooted (Magisk)
**App:** launcher `com.offlineinc.dumbdownlauncher`, uid 10094, v5.54.0-beta.0
**Evidence:** `~/repos/log_issues.log` (bad boot, 08-04 14:11:45 → 14:14:48) plus a
healthy-boot logcat pasted in chat (same handset, pid 1938, 14:16:57 → 14:19:15)

Two unrelated things happened on this handset. Neither is a crash. Neither is caused
by the OpenBubbles sweep shipped in `6aa10ff`.

1. The **first boot after the app update** got the launcher low-memory-killed.
2. The **catch-up indicator** next to "Messages" never appeared, even though 216
   messages were being back-filled.

---

## 1. The boot storm

### What actually happened

Not a crash. Nothing threw, there is no stack trace, and the app did not `System.exit`.
The kernel's low-memory killer took the launcher **while it was the foreground app**:

```
14:12:21.244  lowmemorykiller: Kill 'com.android.dialer' (2961), uid 10024, oom_adj 925
                to free 23056kB; reason: device is in direct reclaim and thrashing (32%)
14:12:21.572  lowmemorykiller: Kill 'com.tct.entitlement' (4054), uid 1000, oom_adj 915 …
14:12:21.977  lowmemorykiller: Kill 'com.android.mmitest'  (4085), uid 1000, oom_adj 905 …
14:13:03.984  lowmemorykiller: Kill 'com.offlineinc.dumbdownlauncher' (1874), uid 10094,
                oom_adj 0 to free 7636kB; reason: device is not responding
14:13:05.053  IPCThreadState: binder thread pool (31 threads) starved for 16260 ms
14:13:07.574  lowmemorykiller: Kill 'com.topjohnwu.magisk' (4013), uid 10092, oom_adj 0
                to free 4264kB; reason: device is low on swap (0kB < 68684kB)
                and thrashing (1243%)
14:13:19.609  lowmemorykiller: lmkd data connection dropped / EPOLLERR on event #0
14:13:23.325  Zygote: Preloading classes...
```

Read that top to bottom: lmkd walked *up* the priority ladder. It started at
`oom_adj 925` (cached), and 42 seconds later it was killing things at `oom_adj 0` —
foreground. Swap was fully exhausted (**0 kB** free against a 68 MB watermark) and the
system was thrashing at **1243%**. The `Zygote: Preloading classes...` line at 14:13:23
is a **runtime restart**, not an app relaunch.

`oom_adj 0` + `reason: device is not responding` is the signature to remember: the
launcher was not leaking and did not blow its heap. It was foreground, it stopped
answering, and the system reclaimed it.

### Why *that* boot and not the next one

The bad boot was the **first boot after the app update**, so ART still had to compile
the new APK. The compile is in the log, and it names the launcher explicitly:

```
14:12:10.403  dex2oat32: avc: denied { search } for name="mm" …        ← dex2oat starts
14:13:05.639  dex2oat32: /apex/com.android.art/bin/dex2oat32 … -j3 --cpu-set=0,1,2,3
              --classpath-dir=/data/app/~~DnziXBbZoBNzQhyNiQ0jQA==/
                             com.offlineinc.dumbdownlauncher-_CspAh_11eMLfJJWTKZNjA==
              --compilation-reason=unknown
14:13:19.445  dex2oat32: setting boot class path to …
```

`dex2oat32` ran with `-j3` across all four cores for **at least 70 seconds**, straddling
the 14:13:03 kill. On a phone already at 0 kB swap that is the difference between the
two boots. The healthy boot 3 minutes later did not pay that cost again — same code,
same data, cheaper boot.

So five things were competing at once on boot #1:

| what | where |
|---|---|
| ART recompiling the launcher (`-j3`, all cores) | system |
| whole-system cold start (dialer, entitlement, mmitest all still coming up) | system |
| `DumbDownApp.onCreate` one-shots | `DumbDownApp.kt` |
| OpenBubbles launch sweep | `SmartTxtRepository.startBackgroundSyncIfRegistered` |
| APNs connect pulling a 216-message backlog | `NativeRustPushTransport` |

**The battery pull fixed nothing.** It just produced a second boot that didn't have to
compile. This will reproduce on any handset that takes the update and then reboots into
a real backlog.

### What was *not* the cause

- **Not the OpenBubbles sweep.** It runs off-main and took ~21s on the other handset;
  it is not what starved binder.
- **Not a Smart Txt memory leak.** The heap never came near its limit — see below,
  the native queue peaked at 51 of a 2000 cap.
- **Not the catch-up drain, on this boot.** During the whole 3-minute window there are
  **235** `aps_client: recieved` lines and **zero** `recv msg` lines and **zero**
  `CATCHUP` lines. The Kotlin side produced nothing at all. APNs was receiving; the
  drain never got scheduled, because the process was being starved and then killed.

> **Correction to an earlier claim in chat:** I said the bad log contained
> `ProfileInstaller: Installing profile for com.offlineinc.dumbdownlauncher`. It does
> not — the only `ProfileInstaller` line in that window is `Skipping profile
> installation for com.topjohnwu.magisk`. The dex2oat evidence above stands on its own
> and is explicit about which package it was compiling.

---

## 2. The missing catch-up indicator

### What the user saw

216 messages back-filled correctly over ~90 seconds on the healthy boot, and the small
loading indicator to the left of "Messages" in the room-list header never appeared.

### Why

The indicator is fed by `SyncActivityAware.isCatchingUp` → `RoomListViewModel.kt:60`,
which is driven by `CatchUpPacer` in
`smarttxt/…/transport/NativeRustPushTransport.kt:310`:

```kotlin
/** Full batches in a row before we call it a catch-up. One full batch is just a busy
 *  moment (a group thread waking up); two in a row means a real backlog. */
private val catchUpThreshold: Int = 2,
…
val full = size >= batchLimit          // batchLimit = POLL_BATCH_MAX = 50
fullStreak = if (full) fullStreak + 1 else 0
val nowActive = if (full) fullStreak >= catchUpThreshold else false
```

And the actual drain, from the healthy log:

```
14:18:49.361  smarttxt_ffi::inbound: CATCHUP native: done delivered=51 batches=2
              batchMax=50 peakDepth=51 dropped=0 capHeadroom=1949
              lifetime(queued=216 delivered=216 dropped=0)
```

Batches were **50, then 1**. `fullStreak` reached 1 and reset. `catchUpActive` never
flipped, so no `TransportEvent.CatchUpChanged(true)` was emitted and the header was fed
`false` — which every `UIFLOW emit` line in the healthy log confirms (`catchUp=false`).

**This is working as written.** The mismatch is definitional:

- `CatchUpPacer` defines catch-up as **native queue depth** — messages arriving faster
  than Kotlin drains them.
- The user defines catch-up as **old messages being back-filled**, regardless of rate.

216 messages spread over 90 seconds never made the queue deep (`peakDepth=51` against
`INBOUND_CAP = 2000`), so by the pacer's definition there was no backlog. By the user's
definition there obviously was.

### Second-order consequence: the diagnostics didn't fire either

`CatchUpStats` — the class that records heap and native-heap peaks, i.e. the evidence
you actually want when a handset dies mid-catch-up — is started inside the same gate
(`NativeRustPushTransport.kt`, `startPolling`):

```kotlin
if (step.catchUpChanged && step.catchUpActive) {
    Log.i(TAG, stats.begin())
    _events.emit(TransportEvent.CatchUpChanged(true))
}
if (pacer.catchUpActive) stats.onBatch(batchSize)?.let { Log.i(TAG, it) }
```

So `CATCHUP start:` / `CATCHUP progress:` / `CATCHUP done:` — every heap number in the
Kotlin layer — only exist for drains that already trip the 2-full-batch threshold. The
instrumentation cannot fire during the incidents it was written to explain. That is the
single most valuable thing to change, and it is free.

---

## 3. What is already right — don't re-solve these

Worth writing down, because two of the obvious "fixes" are already in the tree:

- **The native inbound queue is already bounded.** `smarttxt-ffi/src/inbound.rs`:
  `INBOUND_CAP = 2_000`, with drop accounting, and — importantly — dropped guids are
  removed from the `seen` replay guard so Apple's next backlog replay re-delivers them.
  Field evidence: `dropped=0 capHeadroom=1949`. Adding back-pressure here is **not**
  needed.
- **The drain is already chunked.** `POLL_BATCH_MAX = 50`, so no single
  `nativePollEvents` allocates a giant JSON string / UTF-16 copy / object tree. This is
  the fix that stopped the original freeze.
- **`su`-heavy boot work is already serialised.** `DumbDownApp.bootExecutor` is a
  single-threaded executor, added precisely because "4–5 concurrent `su` forks
  saturated the eMMC and starved the main thread."
- **One-time migrations already wait.** `MIGRATION_BOOT_DELAY_MS = 30_000` — 30s after
  `onCreate`, chosen to let SIM init and first frame finish.

The gap is that the **network** side has no equivalent restraint.
`startBackgroundSyncIfRegistered` is called straight from `DumbDownApp.onCreate` and
connects APNs immediately, in parallel with everything `bootExecutor` is carefully
serialising.

---

## 4. Recommendations

Ordered by value-per-unit-risk.

### R1 — Log heap unconditionally (LOW risk, do first)

Move `CatchUpStats.begin()` out of the `catchUpActive` gate: start it on the first
non-empty batch of any drain, sample on every batch, and emit `CATCHUP done:` when the
queue empties. Keep the *pacing* behaviour exactly as-is; this changes only what gets
logged.

- **Risk:** none to behaviour. Worst case is log volume, bounded by
  `progressEvery = 10`.
- **Verification:** reboot, `logcat | grep CATCHUP` — expect `start`/`done` on ordinary
  drains, not just big ones. Confirms in one boot.
- **Why first:** every other diagnosis on this page had to be reconstructed from system
  logcat because the app's own instrumentation was silent. Fix that before chasing
  anything else.

### R2 — A boot gate with a visible setup screen (MEDIUM risk, highest value)

**Rejected alternative:** gating the APNs connect on `BOOT_COMPLETED`. That introduces
a gate that can fail to *open* — broadcast missed, app started by the user first, OEM
battery manager eating it — and the failure mode is *the app never connects and
messages silently stop*. That is the exact bug class we have spent weeks chasing. Not
worth it.

**Proposal instead:** a short `BootTasks` runner in the launcher that owns startup work
and shows it, duck icon plus one line of status:

```
   🦆
"Updating your apps…"      ← DumbDownApp one-shots
"Moving your messages…"    ← OpenBubbles sweep / migration
"Getting ready…"           ← cache / db work
```

What this actually buys is **serialisation**, not memory: it stops five expensive
things from running at the same instant, and it gives the network work a defined place
to start — after the local work, not alongside it. Secondary but real: on a flip phone,
"Setting things up" beats a frozen home screen, and support can ask "what does the
screen say?"

Three non-negotiable rules:

1. **Usually skipped.** Runs only when a task reports work pending (version bump,
   migration flag unset). If nothing is pending it never draws, and Home stays instant.
   Otherwise every home press grows a duck.
2. **Hard timeout that always releases.** This is the home app. If a task wedges, the
   user has no phone. Tear down after ~15s regardless and let the unfinished task
   continue in the background.
3. **No network on this screen, at all** — and the APNs connect starts *after* release,
   ideally staggered rather than everything firing at once. Otherwise a bad cell signal
   can hold the home screen hostage.

Good candidates to move inside: the OB sweep, the OB→Smart Txt migration, the
`DumbDownApp` one-shot disables, cache/db pruning. Explicitly **not**: APNs connect,
update checks, anything touching `hw.openbubbles.app`, the catch-up drain.

**Honest ceiling:** this reduces the collision, it does not raise the memory ceiling.
A phone at 0 kB swap during a first-boot dex2oat is fragile no matter what we do. The
durable win is doing *less* synchronously at boot; the screen is the scaffolding that
makes that easy to reason about.

**Open scope question:** launcher-side boot gate (bigger win, touches the home screen,
needs care) vs. a Smart Txt-only version (safer, smaller, but misses most of the
offending work, which is launcher-side).

### R3 — Drive the indicator off the backfill signal (LOW/MEDIUM risk)

Feed the header from "am I currently importing messages older than this session"
instead of native queue depth. The repository already computes exactly this — the
healthy log has dozens of:

```
IMsgRepo: notify skip: backfill ts=… sessionStart=1785867428164
```

Set `isCatchingUp` while backfill messages are in flight, with a short trailing
debounce so it doesn't flicker. Leave `CatchUpPacer`'s throttling alone — this changes
only what the header reads, not how fast we poll.

- **Verification:** reboot a handset with a real backlog; indicator should show for the
  duration of the import and clear when it ends.

### R4 — Trim `DumbDownApp.onCreate` (follow-on)

Independent of the screen: audit what genuinely must run at process start vs. what can
be deferred or made lazy. `onCreate` currently fires ~10 startup responsibilities, some
already deferred (`bootExecutor`, `MIGRATION_BOOT_DELAY_MS`) and some not
(`startBackgroundSyncIfRegistered`, `UpdateCheckWorker.schedule`,
`repostOpenBubblesUpdateIfPending`, the self-update check thread).

---

## Appendix — file references

| concern | file |
|---|---|
| native queue, cap, drop accounting, `CATCHUP native:` | `dpad-messenger-backend/smarttxt-ffi/src/inbound.rs` |
| poll loop, `CatchUpPacer`, `CatchUpStats` wiring | `dpad-messenger-backend/smarttxt/…/transport/NativeRustPushTransport.kt` |
| heap / native-heap measurement | `dpad-messenger-backend/smarttxt/…/transport/CatchUpStats.kt` |
| `isCatchingUp` flow | `dpad-messenger/library/…/data/SyncActivityAware.kt` |
| header consumption | `dpad-messenger/library/…/ui/rooms/RoomListViewModel.kt:60` |
| launch sweep + background sync entry | `dpad-messenger-backend/smarttxt/…/SmartTxtRepository.kt` |
| boot work, `bootExecutor`, migration delay | `launcher/app/…/DumbDownApp.kt` |
