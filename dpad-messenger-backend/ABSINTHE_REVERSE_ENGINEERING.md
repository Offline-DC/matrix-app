# Absinthe reverse-engineering — entry point & setup

> **Status: IMPLEMENTED & VERIFIED.** The validation-data ("nac"/absinthe)
> engine is reverse-engineered and working — it was run end-to-end against the
> real `IMDAppleServices` binary and a real Mac identity and produced valid
> validation data. See [`ABSINTHE_RE_FINDINGS.md`](ABSINTHE_RE_FINDINGS.md) for
> the writeup + proof, `nacserver/` for the runnable relay, and `absinthe/` for
> the Rust crate. This document is the operator-facing "how to run it" guide.
> Read §0 first. You still supply your own `IMDAppleServices` + dumb file;
> neither is shipped here.

This is the launch pad for the one unsolved piece of the native SmartTxt
backend (`:smarttxt`): getting **valid validation data** without OpenBubbles'
closed-source absinthe `nac` engine. The full context is in
[`SMARTTXT_NATIVE_BACKEND_PLAN.md`](SMARTTXT_NATIVE_BACKEND_PLAN.md) §2.5–§2.6;
this doc is the shorter "here's where the seams are and here's how to start"
map.

## 0. Legal / ethical / account-risk note (read first)

- Registering against Apple IDS with reverse-engineered validation data can get
  Apple IDs and hardware identities **flagged or banned**. Use throwaway Apple
  IDs and expect to burn them.
- **Do not** extract, decompile, or vendor OpenBubbles' compiled absinthe from
  their APK/binaries. It is closed source and paid-tier secret sauce; §2.6
  option 3b in the plan calls this out as legally/ethically dubious and fragile.
  This repo's approach is a *clean-room reimplementation* of the `nac`
  algorithm (option 3a) or a *relay* (option 2) — not extraction.
- Keep everything behind the existing seams. Nothing that talks to Apple lands
  outside `smarttxt-ffi/` (Rust) and the `absinthe/` package (Kotlin).

## 1. What "absinthe" actually is

Apple's SmartTxt registration (`IDS register`) requires **validation data**: a
signed blob proving the request comes from genuine Apple hardware. It's produced
by an Apple routine (historically in `IMDAppleServices` / `identityservicesd`)
that takes hardware identifiers (serial, MLB, ROM, board id, OS build) and does
a key-establishment + signing handshake against Apple's IDS validation
endpoints.

OpenBubbles' `rustpush` calls this through the `open-absinthe::nac` crate. But
the public `open-absinthe` submodule (`OpenBubbles/OpenAbsinthe-Stub`, pinned
`1f8dc73`) is a **deliberate mock** — its own README says so, and every method
is `todo!()` / `panic!()`. So a from-source rustpush build panics at
`generate_validation_data()` and registration never completes. The real engine
is not published.

The reverse-engineering lineage the plan points at (§2.6 option 3a) is
**JJTech's `pypush`** — the project that reverse-engineered Apple's `nac*`
routines ("Absinthe") in Python. That is the reference body of work to port
from, plus later community continuations. OpenBubbles' maintainers have a
working native absinthe; we are *not* copying theirs.

## 1.5 The working implementation (option 3a + option 2, done)

This is no longer just a plan. Two implementations exist and the approach is
verified (real binary, real identity, real 389-byte validation data):

- **`nacserver/`** — a Python relay that runs the emulation off-device and
  serves validation data over the `ValidationDataRelay` HTTP contract the phone
  already speaks. This is the production path (the flip phone can't emulate an
  x86-64 binary): one shared Mac identity serves the fleet (option 2 transport,
  option 3a engine). **Runnable today** — see `nacserver/README.md`.
- **`absinthe/`** — a Rust crate (`ValidationCtx::new/key_establishment/sign`,
  API-compatible with OpenBubbles' closed `open-absinthe`) that mirrors the same
  emulation for a native/on-device build. Its non-emulator core (OABS parse,
  Mach-O load, dyld binds) is unit-tested against the real artifacts; the
  emulator itself is behind `--features emulate` (needs libunicorn).

Full technical writeup + the mapping to your real dumb file:
[`ABSINTHE_RE_FINDINGS.md`](ABSINTHE_RE_FINDINGS.md).

## 2. Where the seams already are (what's wired, so you don't hunt)

Everything is shaped so the real engine is a drop-in. The stubs are explicit on
both sides of the JNI boundary:

| Layer | File | What it is |
|---|---|---|
| Kotlin stub | `smarttxt/src/main/kotlin/.../absinthe/AbsintheStub.kt` | Mirrors the Rust stub: `ValidationCtx.new/keyEstablishment/sign` + `hardwareConfigFromValidationData` all throw `AbsintheUnavailableException`. `IS_REAL_IMPLEMENTATION = false`. Catching that exception is the signal to fall back to the relay. |
| Kotlin bridge | `smarttxt/src/main/kotlin/.../RustPushBridge.kt`, `RustPushNative.kt` | The `external fun native*` surface. `RustPushNative.loaded` flips true once the `.so` is present. |
| Rust FFI | `smarttxt-ffi/src/lib.rs` | JNI plumbing is complete; integration points are marked `RUSTPUSH:`. `nativeRegister(...)` already takes `validation_data: JByteArray` — i.e. the seam accepts externally-produced validation bytes. |
| Relay fallback | `smarttxt/src/main/kotlin/.../relay/ValidationDataRelay.kt` (+ `Http`/`Stub` impls) | The §2.6 **option 2** path: get validation bytes from a Mac-backed service instead of computing them on-device. |
| Relay contract | [`RELAY_PROTOCOL.md`](RELAY_PROTOCOL.md), `relay-reference/server.js` | BlueBubbles-aligned wire format + a reference server to test against. |
| Native build | `scripts/build-rustpush-so.sh`, `smarttxt-ffi/Cargo.toml` | Cross-compiles the FFI crate to `arm64-v8a` + `armeabi-v7a`, drops into `smarttxt/src/main/jniLibs/`. |

The three decision paths (pick before writing code) are the plan's §2.6:
**1) hybrid** (keep OB headless), **2) full-native + validation relay**,
**3) reimplement/obtain absinthe**. This doc's setup supports **2 and 3** —
they share the same FFI/build wiring; they differ only in *where the validation
bytes come from*.

## 3. Getting the codebase ready to attempt it (setup checklist)

None of this runs in the Cowork sandbox — it needs a dev machine with Rust + the
Android NDK. This is the "get set up to try" checklist; each step is inert until
you actually fill in the marked integration points.

1. **Toolchain**
   ```bash
   # Rust + Android targets
   rustup target add aarch64-linux-android armv7-linux-androideabi
   # Android NDK r26+; export ANDROID_NDK_HOME
   ```
2. **Vendor rustpush at a pinned commit** (do NOT float on `main` — plan §3):
   ```bash
   git clone https://github.com/OpenBubbles/rustpush vendor/rustpush
   git -C vendor/rustpush checkout <PIN>      # record the rev
   ```
   Then uncomment and point the `rustpush = { git = ..., rev = "<PIN>" }` line in
   `smarttxt-ffi/Cargo.toml`.
3. **Pick the validation-data source:**
   - *Option 2 (relay):* stand up a Mac/BlueBubbles-backed relay implementing
     `RELAY_PROTOCOL.md`. Test the client against `relay-reference/server.js`
     first. Nothing in absinthe needs solving — validation bytes arrive over the
     socket and flow straight into `nativeRegister(..., validation_data)`.
   - *Option 3 (reimplement):* create a real `open-absinthe` replacement crate
     that implements `nac` (port from the pypush "Absinthe" lineage). Wire it in
     place of the stub submodule, and flip `AbsintheStub.IS_REAL_IMPLEMENTATION`
     semantics by routing through it instead of the relay.
4. **Fill the `RUSTPUSH:`-marked integration points** in `smarttxt-ffi/src/lib.rs`
   (activate → authenticate_apple → validation → register; send/recv; poll). The
   JNI signatures already match `RustPushNative`'s `external fun`s, so the Kotlin
   side needs no changes.
5. **Build the `.so`:**
   ```bash
   ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/26.x.x ./scripts/build-rustpush-so.sh
   ```
   Outputs land in `smarttxt/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libsmarttxt_ffi.so`;
   `RustPushNative.loaded` then flips true and the app takes the native path.
6. **Register a throwaway Apple ID** end-to-end and watch for IDS invalidation
   (plan §8). Iterate.

## 4. What NOT to do yet

- Don't uncomment the `rustpush` dependency or run the build on CI/shared infra
  until a commit is pinned and reviewed.
- Don't wire real Apple credentials anywhere in this repo; account state belongs
  in `SmartTxtAccountStore` (EncryptedSharedPreferences) at runtime only.
- Don't attempt option 3b (extracting OB's compiled absinthe). See §0.

## 5. Pointers

- Plan & blocker analysis: `SMARTTXT_NATIVE_BACKEND_PLAN.md` §2.5 (proof it's
  closed source), §2.6 (the three ways through), §3 (embedding rustpush).
- Relay path: `RELAY_PROTOCOL.md` + `relay-reference/`.
- Stubs to replace: `absinthe/AbsintheStub.kt`, the `RUSTPUSH:` markers in
  `smarttxt-ffi/src/lib.rs`.
- Upstream: OpenBubbles `rustpush`, `OpenBubbles/OpenAbsinthe-Stub` (mock),
  JJTech `pypush` (the Absinthe RE reference).
