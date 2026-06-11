# dpad-spotify

Prototype DPAD-first Spotify client for the dumb-down ecosystem (TCL Flip 2
class devices), built on **rust [librespot](https://github.com/librespot-org/librespot) v0.8**
(the maintained client — this replaced an earlier librespot-java spike, see
git history). Proves out: login without typing, search, and real playback.
UI conventions (focus halo, one-action-per-press gate, snap navigation) are
lifted from `../dpad-messenger`.

## How login works

Spotify killed username/password auth in 2023, so there is no password screen.
Instead the native core runs librespot's **zeroconf / Spotify Connect handoff**:

1. App advertises itself on the LAN as a Spotify Connect device ("Offline
   Dpad").
2. User opens Spotify on their regular phone (same Wi-Fi), plays anything,
   taps the devices icon, picks "Offline Dpad".
3. Spotify transfers an encrypted credential blob; librespot logs in with it
   and caches reusable credentials in app-private storage.
4. Every subsequent launch logs in directly from stored credentials — the
   other phone is only needed once.

**A Spotify Premium account is required** (librespot limitation — free
accounts can't stream this way).

## Architecture

```
rust/                  cdylib crate → libdpadspotify.so
  src/lib.rs           librespot session/discovery/player + JNI surface.
                       Pull model: Rust never calls into the JVM. Kotlin pulls
                       JSON events (pollEvent) and PCM (readPcm); commands are
                       fire-and-forget onto a Tokio runtime.
app/src/main/kotlin/com/offline/dpadspotify/
  SpotifyApp.kt        Application: owns the manager
  MainActivity.kt      AppCompatActivity + Compose host
  spotify/
    LibrespotNative.kt JNI bindings (names = part of the contract with lib.rs)
    SpotifyManager.kt  state flows, event pump thread, AudioTrack pump thread,
                       app-side next/prev queue
    WebApi.kt          /v1/search via a session-minted bearer token
  focus/               DpadFocusModifiers — copied from dpad-messenger
  ui/                  Login (handoff instructions) → Search → Now Playing
```

Audio path: librespot decodes (symphonia, in Rust) → custom `Sink` pushes
44.1kHz stereo s16le into a ring buffer → Kotlin audio thread pulls it into
an `AudioTrack`. TLS is rustls with bundled webpki roots — no OpenSSL to
cross-compile, no reliance on the device's (old) system cert store.

Playback queue: rust librespot's `Player` is deliberately a single-track
engine (queueing lives in Spotify Connect's spirc state machine, which this
prototype doesn't run). Next/prev/auto-advance walk the search-result list
app-side instead.

## Build & run

One-time setup:

```
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk
# plus an NDK: Android Studio → SDK Manager → NDK (side by side)
```

Then:

```
cd dpad-spotify
./gradlew :app:cargoNdk          # rust → app/src/main/jniLibs/*/libdpadspotify.so
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`cargoNdk` is not wired into `preBuild` on purpose — a stale .so is fine for
UI iteration, and gradle shouldn't depend on cargo being on PATH. Same JVM
toolchain as dpad-messenger: AGP 8.13.2 / Kotlin 2.1.10 / Gradle 8.13
wrapper, compileSdk 36, minSdk 24.

## Known risks / open questions

- **Zeroconf discovery on Android is the flakiest link.** The app holds a
  `MulticastLock` while advertising (required — Android filters mDNS without
  it), and librespot's libmdns responder is pure Rust so it should run fine,
  but this is the first thing to verify on real hardware. If the device won't
  appear in the Spotify app: same subnet, AP isolation off, 2.4/5 GHz bands
  not split.
- **No spirc (full Spotify Connect device) yet.** After the handoff the
  phone's Spotify app may show a brief "couldn't connect" — expected: we take
  the credentials but don't register as a controllable device. Wiring up
  `librespot-connect`'s `Spirc` would make the device genuinely controllable
  from the phone (and give us Spotify-side queueing) — the natural next step.
- **Web API search with a session token** works today but is not a contract;
  if Spotify tightens it, search can move to spclient through the session.
- No background playback service — audio stops if Android kills the process.
  A `MediaSessionService` + media notification is the obvious next step if
  this graduates.
- No volume-key integration (AudioTrack follows STREAM_MUSIC, so hardware
  volume keys work while the app is foreground), no album art yet.
