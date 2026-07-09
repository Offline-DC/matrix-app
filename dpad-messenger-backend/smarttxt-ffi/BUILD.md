# Building `libsmarttxt_ffi.so` (the real on-device rustpush)

This crate is now a **real** rustpush wrapper (not a stub). Once built and bundled,
`RustPushNative.loaded` flips true and the Smart Txt app registers with iMessage
**on-device**, pulling device identity + validation data from the OpenBubbles relay
(`https://hw.openbubbles.app`) — no daemon, no mock.

## What the native flow does

```
nativeInit(filesDir, https://hw.openbubbles.app, CFOT-…)  build RelayConfig (get_versions) + keystore
nativeConnect()                                           APNs activate/connect (resume if registered)
nativeAuthenticate(appleId, password)                     GrandSlam login (omnisette anisette, out of the box)
   → "logged_in" | "needs_2fa"
nativeSubmit2fa(code)                                     verify 2FA, collect the PET
nativeRegister(appleId)                                   IDS delegates → authenticate → register → IMClient
nativeSendText / nativePollEvents                         send + drain inbound
```

Kotlin drives it: `SmartTxtRepository.ensureNativeInit` → `NativeRustPushTransport`
→ `RustPushBridge.registerWithLogin` (which calls the JNI methods, using your
sign-in screen's `twoFactorProvider` for the code).

## Prerequisites (on your Mac — this can't build in the cowork sandbox)

- Rust toolchain (https://rustup.rs)
- Android NDK r26+ with `ANDROID_NDK_HOME` set
- The vendored `~/repos/rustpush` present **with submodules**
  (`git -C ~/repos/rustpush submodule update --init --recursive`)
- `cargo install cargo-ndk` (strongly recommended — see the unicorn note)

## Step 1 — shake out the Rust first (host build, fast)

I could not compile this crate in the authoring sandbox (no Rust/NDK there), so
expect a compile-iteration pass. Do the **host** build first — it's much faster
than the NDK cross-build and surfaces the same Rust errors:

```bash
cd ~/repos/matrix-app/dpad-messenger-backend/smarttxt-ffi
cargo check 2>&1 | tee /tmp/ffi-check.log
```

Send me the errors and I'll fix them. The spots most likely to need a tweak are
marked `// VERIFY:` in `src/lib.rs` — the receive path (`messages_cont.subscribe`
/ `client.handle`), the `MessageInst.id` server-guid field, and the `LoginState`
variants — since those move between rustpush revisions. The auth/register/send
calls are ported verbatim from the compiled `imessage-register` harness.

## Step 2 — cross-compile to Android

```bash
cd ~/repos/matrix-app/dpad-messenger-backend
ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/26.1.10909125 \
  ./scripts/build-rustpush-so.sh
```

### unicorn is excluded (don't let it get pulled in)

`open-absinthe` hard-depends on `unicorn-engine` (a C library whose cmake build
does NOT configure for the Android NDK — it fails with *"Neither the NDK nor a
standalone toolchain was found"*). We avoid it entirely: `Cargo.toml` builds
rustpush with **`default-features = false`**, which drops the `macos-validation-data`
feature → open-absinthe (and unicorn) is never compiled. rustpush already gates its
only open-absinthe reference (`error.rs`) behind that feature, and we use
`RelayConfig`, not `MacOSConfig`, so nothing needs it.

Build with cargo-ndk (install it + the Rust Android targets first — that's the
`error: no such command: ndk` fix):

```bash
cargo install cargo-ndk
rustup target add armv7-linux-androideabi
cd smarttxt-ffi
cargo ndk -t armeabi-v7a -o ../smarttxt/src/main/jniLibs build --release
```

Target is **armeabi-v7a** (32-bit — the device this ships to). That's exactly why
anisette is `remote-anisette-v3` (a remote server), NOT ClearADI: ClearADI only
vendors Apple's ADI libs (`libCoreADI.so`, `libstoreservicescore.so`) for
`arm64-v8a`/`x86_64` and hits a `compile_error!` on armv7. remote-anisette-v3 is
arch-independent (pure Rust — tokio-tungstenite + reqwest), so it builds for armv7.
(An armeabi-v7a `.so` also runs on arm64 devices via 32-bit compat, so this one ABI
covers everything.)

If you ever see `unicorn-engine-sys` compiling in the output, `default-features =
false` isn't taking effect — check the rustpush dep line in `Cargo.toml`.

Note: this still cross-compiles `openssl` (vendored) and `aws-lc-rs`/`ring` (via
rustls) — both have Android NDK support and build fine under cargo-ndk, unlike
unicorn.

## Step 3 — rebuild the app

```bash
cd ~/repos/launcher
./gradlew :app:assembleDebug
```

`RustPushNative` will now load the `.so`; open **smart txt** → you land on the
iCloud sign-in screen and register for real (no more `MockRelayTransport`).

## Anisette

Used straight from omnisette (`default_provider`, `remote-anisette-v3`) — not
reimplemented. This talks to a remote anisette server (default
`https://ani.sidestore.io`, baked into omnisette's `DEFAULT_ANISETTE_URL_V3`), so
login/2FA needs that server reachable at runtime. ClearADI is NOT an option here —
it only vendors ADI libs for arm64/x86_64 and won't build for armeabi-v7a. To point
at your own anisette server instead of SideStore's, change `DEFAULT_ANISETTE_URL_V3`
in `omnisette/src/lib.rs` (or wire a custom `RemoteAnisetteProviderV3::new(url, …)`).
