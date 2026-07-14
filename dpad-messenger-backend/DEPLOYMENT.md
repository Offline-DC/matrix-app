# Smart Txt — deployment plan

How the on-device iMessage engine (Smart Txt) gets from your Mac into a shipped
`matrix-app` build, and what needs to live where.

## 1. What's where today

| Piece | Repo / location | Versioned? |
|---|---|---|
| App + migrator Kotlin (`OpenBubblesMigrator.kt`, …) | `Offline-DC/matrix-app` | ✅ in matrix-app |
| FFI Rust **source** (`smarttxt-ffi/src/lib.rs`) | `matrix-app/dpad-messenger-backend/smarttxt-ffi` | ✅ in matrix-app |
| Prebuilt native lib `libsmarttxt_ffi.so` (arm64 + armv7) | `smarttxt/src/main/jniLibs/**` | ✅ committed blob |
| Decrypt tool `dec.jar` | `smarttxt/src/main/assets/dec.jar` | ✅ in matrix-app |
| **rustpush** (native engine) | `~/repos/rustpush` — **sibling** of matrix-app, `path` dep | ❌ **only on your Mac** |
| **omnisette** anisette fix | `~/repos/rustpush/apple-private-apis/` (a **submodule**) | ❌ **only on your Mac** |

`smarttxt-ffi/Cargo.toml` references the engine by **relative path** to a sibling
folder:

```
rustpush  = { path = "../../../rustpush", … }
omnisette = { path = "../../../rustpush/apple-private-apis/omnisette", … }
keystore  = { path = "../../../rustpush/keystore" }
```

The `.so` is cross-compiled from that source by
`dpad-messenger-backend/scripts/build-rustpush-so.sh` and the result is committed
into `jniLibs`. There is **no CI** yet.

## 2. The core problem

Your native changes live in **three** places, all only on your Mac, all forked
from `OpenBubbles/*` (which you cannot push to):

1. `rustpush` — top-level `src/*` + `keystore/*`
2. `apple-private-apis` submodule — the `omnisette` anisette rebuild
3. minor: `open-absinthe` stub / nested `clearadi`

If this Mac dies, the `.so` cannot be rebuilt. **Fix that first**, independent of
whichever build model you pick.

## 3. Step 0 — get the native source into repos you own (do this now)

Fork on GitHub:
- `OpenBubbles/rustpush` → `Offline-DC/rustpush`
- `OpenBubbles/apple-private-apis` → `Offline-DC/apple-private-apis`

Then, bottom-up (submodule first, so its commit is pinnable):

```bash
# --- omnisette / apple-private-apis submodule ---
cd ~/repos/rustpush/apple-private-apis
git remote rename origin upstream
git remote add origin https://github.com/Offline-DC/apple-private-apis.git
git checkout -b smarttxt
git add -A && git commit -m "omnisette: anisette v3 header logging + de-panic for Smart Txt"
git push -u origin smarttxt

# --- rustpush itself ---
cd ~/repos/rustpush
# point the submodule at YOUR fork so clones resolve
git config -f .gitmodules submodule.apple-private-apis.url https://github.com/Offline-DC/apple-private-apis.git
git remote rename origin upstream
git remote add origin https://github.com/Offline-DC/rustpush.git
printf '.DS_Store\n*.bak\n' >> .gitignore
git checkout -b smarttxt
git add -A && git commit -m "Smart Txt: dumb->config, keystore de-panic, proxy removal, IDS refresh, submodule->fork"
git push -u origin smarttxt
```

Now the exact source that builds your `.so` is backed up and pinnable by commit.
(Use `https://` submodule URLs, not the `git@github.com:` SSH ones, so CI and
fresh clones work without keys.)

## 4. Pick a build model

### Option A — ship the prebuilt `.so` (recommended now)

Keep doing what already works:

1. Build the `.so` on your Mac whenever `rustpush`/`smarttxt-ffi` changes:
   `ANDROID_NDK_HOME=… ./scripts/build-rustpush-so.sh`
2. Commit the two `.so` files + `dec.jar` into `matrix-app` (already are).
3. `matrix-app`'s build stays **pure Android** — no Rust, no NDK, no rustpush in CI.

If you want app CI, all it needs is Gradle:

```yaml
# .github/workflows/app.yml
name: app
on: { push: { branches: [main] } }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: ./gradlew :app:assembleRelease   # packages the committed .so + dec.jar
```

- **Pros:** least moving parts; fastest to ship; good for a small fleet.
- **Cons:** manual `.so` rebuilds; a binary blob in git; the *source* that
  produced it is only guaranteed reproducible because of Step 0.

### Option B — build the `.so` in CI (graduate to this later)

Make the native engine part of the `matrix-app` checkout and let Actions
cross-compile it.

1. Add the engine as a submodule **inside** matrix-app, pinned to your fork:
   ```bash
   cd ~/repos/matrix-app
   git submodule add -b smarttxt https://github.com/Offline-DC/rustpush.git rustpush
   git submodule update --init --recursive
   ```
2. Repoint the three `path` deps in `smarttxt-ffi/Cargo.toml` (one level shorter
   now that rustpush is inside the repo):
   ```
   rustpush  = { path = "../../rustpush", … }
   omnisette = { path = "../../rustpush/apple-private-apis/omnisette", … }
   keystore  = { path = "../../rustpush/keystore" }
   ```
3. Add a native-build workflow:
   ```yaml
   # .github/workflows/build-so.yml
   name: build-so
   on: { workflow_dispatch: {}, push: { paths: ['rustpush/**','**/smarttxt-ffi/**'] } }
   jobs:
     so:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
           with: { submodules: recursive }
         - uses: dtolnay/rust-toolchain@stable
           with: { targets: 'aarch64-linux-android,armv7-linux-androideabi' }
         - uses: nttld/setup-ndk@v1
           with: { ndk-version: r26d }
         - run: cargo install cargo-ndk
         - run: ANDROID_NDK_HOME=$ANDROID_NDK_LATEST_HOME ./dpad-messenger-backend/scripts/build-rustpush-so.sh
         - uses: actions/upload-artifact@v4
           with: { name: libsmarttxt_ffi, path: '**/jniLibs/**/libsmarttxt_ffi.so' }
   ```
   (Either upload the `.so` as an artifact the app job consumes, or commit it back.)

**Gotchas for Option B**
- Submodule URLs must be `https://` (done in Step 0), and the workflow needs
  `submodules: recursive` (rustpush → apple-private-apis → clearadi is nested).
- `openssl` for Android: the build script must use the NDK's or a vendored
  openssl; confirm it sets the right `OPENSSL_*`/`AR`/`CC` for each target.
- `unicorn`/`open-absinthe` stay excluded — already feature-gated in
  `smarttxt-ffi/Cargo.toml` (`macos-remote-validation`, no `macOS` feature). Don't
  add anything that turns them on; unicorn's cmake won't cross-compile to Android.
- NDK builds take several minutes per ABI — cache `~/.cargo` and `target/`.

## 5. Recommendation

1. **Do Step 0 today** — it's the only thing protecting the native work.
2. **Ship on Option A.** It matches your current, working flow; the app repo stays
   a normal Android project.
3. **Move to Option B** only when `.so` rebuilds get tedious or a second person
   needs to build. The submodule + workflow above is the whole delta.

## 6. Release checklist (Option A)

- [ ] Native change made in `Offline-DC/rustpush` (and/or the omnisette fork); pushed.
- [ ] `./scripts/build-rustpush-so.sh` → both ABIs rebuilt.
- [ ] `libsmarttxt_ffi.so` (arm64 **and** armv7) + `dec.jar` committed in matrix-app.
- [ ] Bump `versionCode`; `./gradlew :app:assembleRelease`.
- [ ] Install over the top (update-install keeps data); if testing migration again,
      clear the flag: force-stop launcher, then remove `smarttxt_flags.xml`.
