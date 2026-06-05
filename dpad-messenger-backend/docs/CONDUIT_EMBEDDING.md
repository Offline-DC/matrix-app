# Phase 4 — Embedding Conduit on device

Goal: run a Matrix homeserver inside the app process so the user's
messages never touch a 3rd-party server. The UI's Matrix client then
points at `http://127.0.0.1:6167` instead of `https://matrix.org`.

Conduit is the only realistic option for on-device: single Rust binary,
RocksDB storage, ~50–100 MB resident, no Postgres/Python needed.

This is the **stub** for that work — the `EmbeddedHomeserverService`,
`ConduitBinaryLoader`, and Android manifest are in place; the actual
binary is not. Below is the recipe to finish.

## 1. Cross-compile Conduit for Android

Conduit's main repo: <https://gitlab.com/famedly/conduit>.

```bash
# Set up Rust + Android cross targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# Install Android NDK if you don't have it
brew install --cask android-ndk    # or download from Google

# Configure cargo to use the NDK clang as the linker
cat >> ~/.cargo/config.toml <<EOF
[target.aarch64-linux-android]
linker = "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android24-clang"
ar     = "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
EOF

git clone https://gitlab.com/famedly/conduit
cd conduit

# Conduit's binary entry point lives in src/main.rs; we want it as a lib.
# Easiest: write a thin FFI wrapper crate that depends on conduit-as-a-library.
# Conduit doesn't currently publish itself as a clean lib crate, so you'll
# likely need to vendor + patch.

cargo build --release --target aarch64-linux-android
```

You should end up with `target/aarch64-linux-android/release/libconduit_ffi.so`.

## 2. Drop the .so into the conduit module

```
dpad-messenger-backend/conduit/src/main/jniLibs/
├── arm64-v8a/
│   └── libconduit_ffi.so
└── armeabi-v7a/
    └── libconduit_ffi.so
```

## 3. JNI surface

Write a Rust FFI wrapper that exposes:

```rust
#[no_mangle]
pub extern "C" fn Java_com_offline_dpadmessenger_backend_conduit_ConduitBinaryLoader_nativeStartServer(
    env: JNIEnv, _class: JClass,
    port: jint, data_dir: JString,
) -> jboolean {
    // 1. Build Conduit Config with bind_address = 127.0.0.1, listen port = port,
    //    database_path = data_dir.
    // 2. Spin up Conduit::serve() on a background tokio runtime.
    // 3. Return true on success.
}

#[no_mangle]
pub extern "C" fn Java_com_offline_dpadmessenger_backend_conduit_ConduitBinaryLoader_nativeStopServer(
    env: JNIEnv, _class: JClass,
) {
    // Tokio runtime shutdown handle stored in a global Once.
}
```

Then in `ConduitBinaryLoader.kt`:

```kotlin
external fun nativeStartServer(port: Int, dataDir: String): Boolean
external fun nativeStopServer()
```

and uncomment the `System.loadLibrary("conduit_ffi")` line.

## 4. Wire up the service

`EmbeddedHomeserverService` already runs as a foreground service. Inside
`onCreate`, after `startInForeground`, call:

```kotlin
val dataDir = File(filesDir, "conduit").apply { mkdirs() }
ConduitBinaryLoader.startServer(port = 6167, dataDir = dataDir)
```

And in `onDestroy`: `ConduitBinaryLoader.stopServer()`.

## 5. Initial admin user

Conduit requires a registered user before federation/login. On first
launch, exec a one-shot admin command via the same FFI:

```rust
#[no_mangle]
pub extern "C" fn Java_..._nativeCreateUser(...) { /* admin API */ }
```

Or pre-seed `users` directly in the DB.

## 6. UI integration

```kotlin
val factory = MatrixBackendFactory()
val repo = factory.create(
    context,
    BackendConfig.EmbeddedConduit(port = 6167),
)
```

`MatrixBackendFactory` already maps `EmbeddedConduit` → `http://127.0.0.1:6167`.

## Open questions (decisions you'll have to make)

- **Federation.** Disabled by default in this design — your homeserver
  isn't reachable from the public internet anyway. If you ever want
  federation, you need a tunnel (Tailscale, Cloudflare Tunnel, …).
- **Push notifications.** Without federation + a public push gateway,
  you only get notifications while the foreground service is alive.
  UnifiedPush is the realistic answer here.
- **Backup.** RocksDB on `filesDir` survives reinstall? **No** — backed
  up only if the user enables Android Auto Backup. Plan a manual export.
- **Database growth.** Conduit doesn't auto-prune; on a small device
  you'll want a "delete messages older than N days" job.
