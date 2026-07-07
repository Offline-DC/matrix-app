# dpad-messenger-backend

Backend implementations of the [`MessageRepository`](../dpad-messenger/library/src/main/kotlin/com/offline/dpadmessenger/data/MessageRepository.kt)
interface that the [`dpad-messenger`](../dpad-messenger) UI library binds against.

The UI repo stays pure Compose with no network code. This repo is the only
thing that talks Matrix / Signal / Conduit — so the same UI can be reused
for a Google-Messages-Matrix-bridge client later by dropping in a different
backend module.
vvi
## Modules

| Module     | Purpose                                            | Status   |
| ---------- | -------------------------------------------------- | -------- |
| `app/`     | Runnable wired application — UI + backend, mock by default, Matrix opt-in | Done     |
| `core/`    | `MessageRepository` factory, encrypted `SessionStore`, common types | Done     |
| `matrix/`  | `MatrixMessageRepository` via `matrix-rust-sdk` Kotlin bindings; login/restore via `MatrixAuth` | MVP done |
| `conduit/` | Foreground-service host for an embedded Conduit homeserver | **Stub** — see `docs/CONDUIT_EMBEDDING.md` |
| `signal/`  | libsignal-android linking + Matrix↔Signal bridge service | **Stub** — see `docs/SIGNAL_BRIDGE.md` |
| `gmessages/` | Google Messages QR-pairing + relay client, ships its own Compose pairing/chat UI | MVP done |
| `smarttxt/` | Native SmartTxt backend (direct mode, mirrors `:signal`): rustpush-over-JNI + `ValidationDataRelay`, ships its own Compose setup/chat UI. Runs in stub/relay mode until the native `.so` is built. | **Relay/stub** — see `SMARTTXT_NATIVE_BACKEND_PLAN.md` + `ABSINTHE_REVERSE_ENGINEERING.md` |

## How the pieces fit

```
┌────────────────────────────┐
│  dpad-messenger (UI repo)  │   pure Compose, no network code
│  ─────────────────────     │
│  MessageRepository (iface) │
└──────────────┬─────────────┘
               │ contract
               │
┌──────────────▼─────────────┐
│  dpad-messenger-backend    │   this repo
│  ─────────────────────     │
│  core/        — factory     │
│  matrix/      — real client │
│  conduit/     — embedded HS │
│  signal/      — bridge      │
│  gmessages/   — GMessages   │
│  smarttxt/    — SmartTxt     │
└────────────────────────────┘
```

### SmartTxt / absinthe (migrated from the standalone `imessage-app` repo)

`:smarttxt` is the native SmartTxt backend, brought in so SmartTxt lives
alongside Signal and Google Messages against the same `dpad-messenger` UI
instead of a separate app. It talks to Apple's IDS/APNs through OpenBubbles'
`rustpush` compiled to a JNI `.so`. The one piece rustpush can't do open-source
is **validation data** — Apple's closed-source absinthe `nac` engine — so today
the module runs in **stub/relay mode**: registration is fed by a mockable
`ValidationDataRelay` and the chat UI shows SmartTxt demo data. The seam is
explicit (`absinthe/AbsintheStub.kt`) so the real engine drops in without
touching the UI.

The absinthe validation-data engine is **reverse-engineered and working**
(verified end-to-end against the real Apple binary + a real Mac identity —
see `ABSINTHE_RE_FINDINGS.md`). Supporting pieces:

- `nacserver/` — **the working validation-data relay** (Python). Runs the nac
  emulation off-device and serves validation data over the phone's
  `ValidationDataRelay` HTTP contract. This is the production path.
- `absinthe/` — Rust crate reimplementing `open-absinthe::nac` (OABS parse +
  Mach-O load + Unicorn emulation). Core unit-tested against real artifacts;
  emulator behind `--features emulate`.
- `smarttxt-ffi/` — the JNI wrapper crate over `rustpush` (integration points
  marked `RUSTPUSH:`). Build to `.so` with `scripts/build-rustpush-so.sh`.
- `relay-reference/` — a reference relay server for the `RelayProtocol`.
- `SMARTTXT_NATIVE_BACKEND_PLAN.md` — the full native-backend plan.
- `RELAY_PROTOCOL.md` — the app↔relay contract (BlueBubbles-aligned).
- `ABSINTHE_REVERSE_ENGINEERING.md` — operator "how to run it" guide.
- `ABSINTHE_RE_FINDINGS.md` — the reverse-engineering writeup + proof.

**Phase 3a (this commit):** UI + Matrix client against any remote homeserver.
You can log into matrix.org today and the UI works.

**Phase 4:** Conduit on device. UI talks to `http://127.0.0.1:6167` instead
of a remote server. No more trusting a 3rd-party HS.

**Phase 5:** Signal bridge alongside Conduit. The bridge registers as a
Matrix appservice in the local Conduit and proxies Signal events into
Matrix rooms. UI never knows it's not talking to "regular" Matrix.

## Building

This repo uses a Gradle **composite build** to pull in the UI library from
the sibling `../dpad-messenger` directory. Clone both side by side:

```
~/repos/
├── dpad-messenger/             # UI repo (mock-only demo + library)
└── dpad-messenger-backend/     # this repo (wired :app + backend modules)
```

Build the wired app:

```bash
cd ~/repos/dpad-messenger-backend
gradle wrapper --gradle-version 8.5         # one-time
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.offline.dpadmessenger.app/.MainActivity
```

The app launches with the **mock backend** (full UI features, no network
needed). Looks identical to the existing `dpad-messenger/demo` because it
runs the same `InMemoryMessageRepository` against the same `mock_data.json`.

## Enabling the real Matrix backend

Two-step opt-in:

1. **Uncomment the dependency** in `app/build.gradle.kts`:
   ```kotlin
   implementation(project(":matrix"))
   ```

2. **Verify matrix-rust-sdk version.** The `matrix/` module was written
   against `org.matrix.rustcomponents:sdk-android:0.2.74` and assumes
   certain symbol names (`Client.session()`, `Timeline.subscribeToTimelineDiffs`,
   etc.). When you `./gradlew :matrix:compileDebugKotlin`, expect to see
   compile errors if the version on Maven Central has churned — fix the
   imports/method names in `MessageMapping.kt`, `MatrixAuth.kt`, and
   `MatrixMessageRepository.kt` against the actual bindings JAR.

   See [`docs/MATRIX_BACKEND_STATUS.md`](docs/MATRIX_BACKEND_STATUS.md)
   for the full symbol checklist.

3. **Wire the login call site.** `AppViewModel.signInViaReflection`
   throws by default — once `:matrix` is in the classpath, swap it for
   a direct call:
   ```kotlin
   val auth = MatrixAuth(appContext, File(appContext.filesDir, "matrix-sessions"))
   auth.login(homeserverUrl, username, password)
   ```

Once those three are done, the existing `LoginScreen` in the UI library
becomes functional: type a homeserver + creds, press Continue, the
session persists, the app rehydrates Matrix on next launch.

## The mock-only demo in dpad-messenger

Still works independently:

```bash
cd ~/repos/dpad-messenger
./gradlew :demo:installDebug
```

That demo doesn't pull in the backend modules — useful for fast UI
iteration without touching the wired app.

## Roadmap docs

- [`docs/MATRIX_BACKEND_STATUS.md`](docs/MATRIX_BACKEND_STATUS.md) — what's
  done in the `matrix/` module and what still needs filling in before
  shipping (E2EE error rendering, media, sticker support, etc.)
- [`docs/CONDUIT_EMBEDDING.md`](docs/CONDUIT_EMBEDDING.md) — the Phase 4
  recipe: cross-compile Conduit for arm64-android, JNI shim, foreground
  service lifecycle, sliding-sync proxy bundling.
- [`docs/SIGNAL_BRIDGE.md`](docs/SIGNAL_BRIDGE.md) — the Phase 5 recipe:
  link-as-device flow, libsignal-android usage, appservice registration,
  history sync.

## Important caveats

- **matrix-rust-sdk API churns.** The library is pre-1.0 and renames
  classes between minor versions. The current `MessageMapping.kt` assumes
  the API surface around `org.matrix.rustcomponents:sdk-android:0.2.74`.
  If you bump the version, expect to fix imports/type names.
- **Signal does not officially permit 3rd-party clients.** Beeper Mini's
  approach (linking as a secondary device via libsignal-android) is what
  this design points at, but accounts have historically been suspended
  for non-official client use. There is no path to "production safe"
  here — only "works at your own risk."
- **Embedded server on a flip phone is RAM-tight.** Conduit's resident
  memory on a small homeserver is ~50–100 MB. On 1 GB-RAM devices this
  is tolerable but you should not run anything else memory-heavy in the
  same process.

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0-only) —
see [`../LICENSE`](../LICENSE). The `signal/` module links
`libsignal-android`, which is AGPL-3.0; because the wired `:app` combines that
module with the rest of the backend into a single work, the whole is conveyed
under AGPL-3.0. If you deploy a modified version that users interact with over
a network, AGPL §13 requires you to offer them its corresponding source. The
Rust crates `absinthe/` and `smarttxt-ffi/` carry `license = "AGPL-3.0-only"`
in their `Cargo.toml`.
