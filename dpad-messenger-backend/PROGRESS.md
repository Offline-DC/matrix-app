# Progress — SmartTxt backend + absinthe reverse engineering

Branch: `feat/smarttxt-backend`  ·  last updated: 2026-07-01

Two pieces of work landed on this branch:

1. **Migrated the SmartTxt backend into `dpad-messenger-backend`** so it lives
   next to Signal and Google Messages against the shared `dpad-messenger` UI.
2. **Reverse-engineered Apple's validation-data ("nac" / absinthe) engine** and
   implemented it — verified working end-to-end against the real Apple binary
   with a real Mac identity.

---

## Status at a glance

| Area | State |
|---|---|
| `:smarttxt` module migrated + registered in the Gradle build | ✅ done |
| `BackendConfig.SmartTxtNative` added to `:core` | ✅ done |
| Absinthe/nac reverse engineering | ✅ **working, verified** |
| `nacserver/` — runnable validation-data relay (Python) | ✅ verified (real binary + real identity → valid 389-byte blob) |
| `absinthe/` — Rust crate (drop-in for `open-absinthe::nac`) | ✅ core unit-tested green; emulator behind `--features emulate` |
| Relay deployment (where it runs) | ⬜ **open decision** — see "What's left" |
| Actual SmartTxt registration (Apple ID + 2FA, rustpush side) | ⬜ downstream, not started |
| Committed to git | ⬜ left uncommitted in the working tree (per request) |

---

## Part 1 — the migration

The standalone `imessage-app` repo was built to mirror the gmessages/signal
pattern already (same package namespace `com.offline.dpadmessenger.backend.smarttxt`,
same composite-build wiring, same `:core`/`:app` shape), so it dropped in
cleanly:

- Copied the `:smarttxt` module (rustpush-over-JNI backend + its own Compose
  setup/chat-gate UI) into `dpad-messenger-backend/smarttxt/`.
- `include(":smarttxt")` in `settings.gradle.kts`.
- Merged `SmartTxtNative` into `:core`'s sealed `BackendConfig` (kept every
  existing Matrix/Signal variant).
- Fixed a latent compile bug this exposed: `MatrixBackendFactory`'s
  `when(config)` was exhaustive-without-`else` — added `else -> return null`.
- Brought the native/RE scaffolding across (`smarttxt-ffi/`, `relay-reference/`,
  the plan + protocol docs) and fixed relative paths.
- `imessage-app` left untouched as a backup.

Decisions (all as agreed): new branch, keep `imessage-app` intact, mirror
gmessages (module + core only; not wired into the demo `:app`).

## Part 2 — absinthe (the reverse engineering)

### The problem
IDS registration needs a signed **validation-data** blob proving the request
comes from genuine Apple hardware. It's produced by three routines Apple ships,
heavily obfuscated (a custom bytecode VM), inside `IMDAppleServices`. There is
no clean-room reimplementation of the *algorithm*. The proven method (pypush,
OpenBubbles, Beeper) is to **emulate the untouched Apple binary** and feed it a
captured hardware identity.

### What was built
- **`nacserver/`** (Python) — the runtime-verified reference and the production
  path. Runs the nac emulation off-device and serves validation data over the
  phone's `ValidationDataRelay` HTTP contract (`GET /health`,
  `POST /validation-data`). Clean-room modules: `hardware.py` (OABS parser),
  `macho.py` (fat/Mach-O loader + dyld bind interpreter), `emulator.py`
  (Unicorn harness), `nac.py` (the three nac calls + import hooks + Apple
  round-trips), `server.py` (the relay).
- **`absinthe/`** (Rust) — a drop-in for OpenBubbles' closed `open-absinthe::nac`
  (`ValidationCtx::new/key_establishment/sign`), for a native/server build. The
  non-emulator core (OABS parse, Mach-O load, dyld binds) is std-only and
  unit-tested; the Unicorn emulator + hooks are behind `--features emulate`.

### The mechanism (short version)
The three functions live at fixed offsets in the binary's **x86_64** slice
(`nac_init`@`0xB1DB0`, `nac_key_establishment`@`0xB1DD0`, `nac_sign`@`0xB1DF0`;
sha1 `e1181cca…`). We map the slice under a CPU emulator, redirect all 257
imported pointers to a hook page, and service the 37 imports that get called —
crucially `IORegistryEntryCreateCFProperty`, which returns our spoofed hardware
values (serial, board-id, MLB, ROM, MAC, UUIDs, and the five obfuscated
17-byte keys) from the dumb file. Two live Apple round-trips (cert fetch +
`initializeValidation`) bracket `nac_key_establishment`. Full writeup:
[`ABSINTHE_RE_FINDINGS.md`](ABSINTHE_RE_FINDINGS.md).

### Verification (not theoretical)
Against the pinned `IMDAppleServices` + the provided dumb file (Mac17,2 /
macOS 15.3.1 / build 24D70):

```
nac_init:              338-byte request
initializeValidation:  698-byte session-info   (live Apple round-trip)
nac_key_establishment: ok
nac_sign:              389-byte validation-data  ✅
```

Over the relay:
```
GET  /health          -> 200 {"status":"ok"}
POST /validation-data  -> 200 {"validationDataB64": "...389 bytes..."}
```

Rust core: `cargo test` green, including tests that parse the **real** dumb file
(byte-exact round-trip) and the **real** binary (nac prologues + IOKit imports).

---

## Architecture: why a relay (and "Mac doesn't have to be on")

The phone talks to Apple directly for everything (APNs, `register`). It offloads
**only** the validation-data computation, because that means running Apple's
**x86-64 macOS binary** — which an ARM Android flip phone can't run natively and
shouldn't emulate (RAM) or ship (Apple's copyright). So a small relay runs the
emulation and returns the blob.

The Mac is needed **once** — to capture the hardware identity (the `dumb` file,
already done) and, optionally, the binary. After that the Mac can be off
forever. The relay is any small always-on Linux host; it still needs Apple
internet access at registration/renewal (that's the one online dependency —
"no Mac" ≠ "fully offline").

```
  phone (ARM)  ──APNs / register──────────────►  Apple
     │  POST /validation-data
     ▼
  relay (any Linux box)  ──cert / initializeValidation──►  Apple
     └─ emulates IMDAppleServices (x86-64) with the captured identity
```

---

## What's left / open decisions

- **Where the relay runs (the one real decision).** Recommendation: a separate
  small service — a dedicated Heroku app (Python buildpack) or a $5 box /
  Fly.io / Railway — **not** merged into `offline-dc-twilio` (different runtime,
  Heroku only routes the `web` process, and it's a production Stripe/Twilio
  service — keep blast radius isolated). Optionally add a thin
  `POST /smarttxt/validation-data` proxy route in `offline-dc-twilio` so the
  phone uses the existing `offline.community` domain with existing auth, while
  the risky workload stays in its own service.
- **Binary provenance.** Using the pinned build (already verified) works today.
  Extracting the Mac mini's own build would be cleaner provenance but a newer/
  arm64 build → re-derive the three offsets. Optional.
- **Identity.** Confirm the `dumb` file is a valid Mac fingerprint you're OK
  registering with (ideally the Mac mini's own). Rotate/add more if wanted.
- **Actual registration (downstream, rustpush side).** Apple ID + one-time 2FA,
  the `register` call signing (push + auth keys), and the `SmartTxtRenewalWorker`
  cadence. Validation data is done; this is the next layer.
- **Rust `emulate` feature** hasn't been compiled here (needs libunicorn/cmake);
  it mirrors the verified Python 1:1 and should be built + smoke-tested on a dev
  machine before relying on the native path.

## Secrets — do not commit
The `dumb` file (a real Mac fingerprint) and `IMDAppleServices` (Apple's binary)
are `.gitignore`d. Keep it that way.

## File index
- `nacserver/` — runnable relay (README inside).
- `absinthe/` — Rust crate.
- `ABSINTHE_RE_FINDINGS.md` — the RE writeup + proof.
- `ABSINTHE_REVERSE_ENGINEERING.md` — operator "how to run it" guide.
- `SMARTTXT_NATIVE_BACKEND_PLAN.md` — the full native-backend plan (context).
- `smarttxt/` — the migrated Android backend module.
