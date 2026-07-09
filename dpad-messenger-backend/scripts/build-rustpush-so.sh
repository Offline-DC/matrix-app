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
MIN_SDK="${MIN_SDK:-24}"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK path}"

HOST_TAG="linux-x86_64"
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

# Rust target triple  ->  (NDK clang prefix, jniLibs ABI dir)
build_one() {
  local rust_target="$1" clang_prefix="$2" abi_dir="$3"
  echo "==> $rust_target -> $abi_dir"
  rustup target add "$rust_target" >/dev/null 2>&1 || true

  local cc="$TOOLCHAIN/${clang_prefix}${MIN_SDK}-clang"
  local ar="$TOOLCHAIN/llvm-ar"
  # Per-target linker/cc via env (cargo reads CARGO_TARGET_<TRIPLE>_LINKER).
  local upper
  upper="$(echo "$rust_target" | tr 'a-z-' 'A-Z_')"
  export "CARGO_TARGET_${upper}_LINKER=$cc"
  export "CC_${rust_target}=$cc"
  export "AR_${rust_target}=$ar"

  ( cd "$CRATE_DIR" && cargo build --release --target "$rust_target" )

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
