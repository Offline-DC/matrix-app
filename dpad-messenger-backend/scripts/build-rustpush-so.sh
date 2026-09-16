#!/usr/bin/env bash
#
# Phase B: cross-compile the smarttxt-ffi crate (rustpush wrapper) to Android
# .so files and drop them into the :smarttxt module's jniLibs. After this runs,
# RustPushNative.loaded flips true automatically and the app uses the native
# SmartTxt path. (SMARTTXT_NATIVE_BACKEND_PLAN.md §3.)
#
# Requirements (NOT available in the cowork sandbox — run on your dev machine):
#   - Rust toolchain (https://rustup.rs)
#   - Android NDK (r26+), with $ANDROID_NDK_HOME set
#
# Usage:
#   ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/26.1.10909125 \
#     ./scripts/build-rustpush-so.sh
#
set -euo pipefail

CRATE_DIR="$(cd "$(dirname "$0")/../smarttxt-ffi" && pwd)"
JNILIBS="$(cd "$(dirname "$0")/.." && pwd)/smarttxt/src/main/jniLibs"
LIB_NAME="libsmarttxt_ffi.so"
<<<<<<< Updated upstream
MIN_SDK="${MIN_SDK:-24}"
=======
# --- The API floor, and how to lower it --------------------------------------
#
# FACETIME=1 (default): builds the FaceTime in-call media pipeline
# (facetime_av.rs), which links AAudio and MediaCodec. It calls
# AAudioStreamBuilder_setUsage / _setContentType and
# AMediaCodec_setAsyncNotifyCallback, all added in API 28, so the .so must be
# built against the API-28 sysroot or the link fails with
# `unable to find library -laaudio`. This matches the
# `ndk = { features = ["api-level-28", ...] }` in smarttxt-ffi/Cargo.toml.
#
# The catch, and it is not a small one: Android resolves EVERY relocation at
# dlopen — there is no lazy binding. So on a device below API 28 those symbols
# fail to resolve and the WHOLE library is rejected:
#
#   dlopen failed: cannot locate symbol "AAudioStreamBuilder_setContentType"
#     referenced by ".../lib/arm/libsmarttxt_ffi.so"
#
# `RustPushNative.loaded` goes false, `RustPushBridge.NATIVE_AVAILABLE` with it,
# and the app reports "iMessage engine isn't available on this device". iMessage
# is dead, not merely FaceTime — a pipeline that handset can't use anyway takes
# the whole native path down. That is what broke the HIT slider (API 27), which
# had worked back when this script built at MIN_SDK 24.
#
# FACETIME=0: drops the `facetime-av` feature, which drops the `ndk`/`evs`/
# `ringbuf` deps and all 35 AAudio/AMediaCodec/ANativeWindow imports with them,
# so the floor falls to the app's own minSdk (24). No FaceTime in-call audio —
# and on API 27 there was none regardless, because nothing loaded.
#
#   FACETIME=0 ./scripts/build-rustpush-so.sh      # API 24+, no FaceTime
#   ./scripts/build-rustpush-so.sh                 # API 28+, with FaceTime
#
# MIN_SDK follows FACETIME unless set explicitly, so the sysroot and the feature
# set cannot drift apart — building at 28 without the feature would silently keep
# the floor at 28 for no reason, and building at 24 with it fails to link.
FACETIME="${FACETIME:-1}"
if [ "$FACETIME" = "0" ]; then
  MIN_SDK="${MIN_SDK:-24}"
  CARGO_FEATURE_ARGS=(--no-default-features)
  echo "==> FaceTime AV: OFF  (API ${MIN_SDK}+ — required for the HIT slider on API 27)"
else
  MIN_SDK="${MIN_SDK:-28}"
  # Spelled out rather than left empty. `facetime-av` is already in `default`, so
  # this changes nothing about what cargo builds — but macOS ships bash 3.2, where
  # expanding an EMPTY array under `set -u` is "unbound variable" rather than zero
  # arguments. Keeping the array non-empty on both paths avoids that entirely.
  CARGO_FEATURE_ARGS=(--features facetime-av)
  echo "==> FaceTime AV: ON   (API ${MIN_SDK}+ only — the .so will NOT load below that)"
fi
>>>>>>> Stashed changes

# rustpush lives here per smarttxt-ffi/Cargo.toml (`path = "../../../rustpush"`).
RUSTPUSH_DIR="$(cd "$CRATE_DIR/../../../rustpush" 2>/dev/null && pwd || true)"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK path}"

# rustpush's cloudkit-proto compiles .proto files via prost-build, which needs the
# `protoc` binary on the HOST. Fail early with an actionable hint rather than the
# raw "Could not find `protoc`" that prost-build emits deep in the build.
command -v protoc >/dev/null 2>&1 || {
  echo "error: 'protoc' (protobuf compiler) not found — rustpush's cloudkit-proto needs it to build." >&2
  echo "       macOS:  brew install protobuf" >&2
  echo "       Debian: sudo apt-get install -y protobuf-compiler" >&2
  echo "       (or set PROTOC to a protoc binary; see https://docs.rs/prost-build)" >&2
  exit 1
}

# --- Fairplay activation certs ------------------------------------------------
# rustpush's src/activation.rs does `include_bytes!("../certs/fairplay/<name>.crt"
# + .pem)` for a fixed list of cert IDs, so those 20 files must exist at COMPILE
# time or the build dies with "couldn't read .../certs/fairplay/...: No such file".
# They're gitignored (never committed) because the real ones are Apple Fairplay
# secrets — but our validation is offloaded to the NAC server, so activation-time
# Fairplay signing is never actually exercised on this path. rustpush's OWN CI
# stubs them by copying the committed `certs/legacy-fairplay/fairplay.{crt,pem}`
# placeholder into each expected name; we do exactly the same so local and CI
# builds match. The cert names are grepped straight out of activation.rs rather
# than hardcoded, so if upstream changes the FAIRPLAY_KEYS list this follows.
setup_fairplay_stubs() {
  local src_pem="$RUSTPUSH_DIR/certs/legacy-fairplay/fairplay.pem"
  local src_crt="$RUSTPUSH_DIR/certs/legacy-fairplay/fairplay.crt"
  local dst="$RUSTPUSH_DIR/certs/fairplay"

  if [ ! -f "$src_pem" ] || [ ! -f "$src_crt" ]; then
    echo "error: legacy-fairplay placeholder not found under $RUSTPUSH_DIR/certs/legacy-fairplay" >&2
    echo "       (is RUSTPUSH_DIR right? got: $RUSTPUSH_DIR)" >&2
    exit 1
  fi

  local names
  names="$(grep -oE 'include_cert!\("[0-9]+"\)' "$RUSTPUSH_DIR/src/activation.rs" \
             | grep -oE '[0-9]+')"
  if [ -z "$names" ]; then
    echo "error: no include_cert! names found in $RUSTPUSH_DIR/src/activation.rs" >&2
    exit 1
  fi

  mkdir -p "$dst"
  local n count=0
  for n in $names; do
    cp "$src_pem" "$dst/$n.pem"
    cp "$src_crt" "$dst/$n.crt"
    count=$((count + 1))
  done
  echo "==> stubbed $count Fairplay cert(s) in $dst"
}
setup_fairplay_stubs

# Don't guess the host tag — ask the NDK. Google has shipped `darwin-x86_64`
# (x86_64 binaries, Rosetta on Apple Silicon) for years, but that's a naming
# convention, not a contract, and hardcoding it means a future NDK that ships
# `darwin-arm64` breaks this script for a reason nobody will enjoy debugging.
# There is exactly one prebuilt dir per NDK, so globbing for it is both simpler
# and more correct than a uname case.
PREBUILT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
TOOLCHAIN=""
for d in "$PREBUILT"/*/; do
  if [ -d "${d}bin" ]; then
    TOOLCHAIN="${d}bin"
    break
  fi
done

if [ -z "$TOOLCHAIN" ]; then
  echo "error: no NDK toolchain found under $PREBUILT" >&2
  echo "       (ANDROID_NDK_HOME=$ANDROID_NDK_HOME — is that the right NDK dir?)" >&2
  echo "       Installed NDKs:" >&2
  ls "$(dirname "$ANDROID_NDK_HOME")" 2>/dev/null | sed 's/^/         /' >&2 || true
  exit 1
fi
echo "==> NDK toolchain: $TOOLCHAIN"

# rustpush depends on `openssl` with the `vendored` feature, so openssl-src
# compiles OpenSSL from source as part of this build. It doesn't go through
# cargo's CC_<triple>/linker env vars — it shells out to OpenSSL's own Configure
# script, which reads ANDROID_NDK_ROOT and expects the NDK's clang on PATH.
# Without these two lines the build dies inside openssl-sys's build script with a
# message that looks like a Rust error but isn't. Set here (rather than asking
# every caller to) so local runs and CI behave identically.
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_NDK_HOME}"
export PATH="$TOOLCHAIN:$PATH"

# Rust target triple  ->  (NDK clang prefix, jniLibs ABI dir)
build_one() {
  local rust_target="$1" clang_prefix="$2" abi_dir="$3"
  echo "==> $rust_target -> $abi_dir"
  rustup target add "$rust_target" >/dev/null 2>&1 || true

  local cc="$TOOLCHAIN/${clang_prefix}${MIN_SDK}-clang"
  local ar="$TOOLCHAIN/llvm-ar"

  if [ ! -x "$cc" ]; then
    echo "error: no compiler at $cc" >&2
    echo "       (MIN_SDK=$MIN_SDK — does this NDK support that API level?)" >&2
    exit 1
  fi

  # Cargo reads CARGO_TARGET_<TRIPLE>_LINKER; the cc crate (which builds the C
  # deps, incl. vendored OpenSSL) reads CC_<triple> / AR_<triple>.
  #
  # Both want the triple with '-' → '_'. That's not cosmetic: a shell variable
  # name cannot contain a hyphen, so `export CC_armv7-linux-androideabi=...`
  # is a syntax error ("not a valid identifier"), which is what this used to do.
  # cc-rs looks up both the hyphenated and underscored spellings, so the
  # underscored one is the only form that's simultaneously legal and understood.
  local under upper
  under="$(echo "$rust_target" | tr '-' '_')"
  upper="$(echo "$under" | tr 'a-z' 'A-Z')"
  export "CARGO_TARGET_${upper}_LINKER=$cc"
  export "CC_${under}=$cc"
  export "AR_${under}=$ar"

  ( cd "$CRATE_DIR" && cargo build --release --target "$rust_target" "${CARGO_FEATURE_ARGS[@]}" )

  mkdir -p "$JNILIBS/$abi_dir"
  cp "$CRATE_DIR/target/$rust_target/release/$LIB_NAME" "$JNILIBS/$abi_dir/$LIB_NAME"
  echo "    -> $JNILIBS/$abi_dir/$LIB_NAME"
}

# Target is armeabi-v7a (32-bit). Anisette is remote-anisette-v3 (a remote server),
# which is arch-independent — unlike ClearADI, which only has arm64/x86_64 ADI libs.
# Change the triple if you target a different ABI.
build_one armv7-linux-androideabi armv7a-linux-androideabi armeabi-v7a

echo
echo "Done. Rebuild the app (./gradlew :app:assembleDebug) — RustPushNative will"
echo "now load the .so and the native SmartTxt transport becomes available."
echo
# Check what we actually produced rather than trusting the flags. `readelf` is GNU
# binutils and does NOT exist on macOS — a "command not found" here produces no
# output and looks exactly like a clean result, which is a great way to ship a
# broken .so. llvm-readelf ships with the NDK, and $TOOLCHAIN is already resolved.
"$TOOLCHAIN/llvm-readelf" --dyn-syms -W "$JNILIBS/armeabi-v7a/$LIB_NAME" \
  | awk '$7=="UND"{print $8}' | grep -E '^(AAudio|AMedia)' | sort > /tmp/smarttxt-api28-syms.txt || true
if [ -s /tmp/smarttxt-api28-syms.txt ]; then
  echo "API-28 symbols imported ($(wc -l < /tmp/smarttxt-api28-syms.txt | tr -d ' ')):"
  sed 's/^/    /' /tmp/smarttxt-api28-syms.txt
  echo "-> this .so will NOT dlopen below API 28. For the HIT slider (API 27):"
  echo "   FACETIME=0 $0"
else
  echo "No AAudio/AMediaCodec imports — this .so loads on API ${MIN_SDK}+."
fi
