# dpad-spotify

Prototype DPAD-first Spotify client for the dumb-down ecosystem (TCL Flip 2
class devices), built on [librespot-java](https://github.com/librespot-org/librespot-java).
Proves out: login without typing, search, and real playback. UI conventions
(focus halo, one-action-per-press gate, snap navigation) are lifted from
`../dpad-messenger`.

## How login works

Spotify killed username/password auth in 2023, so there is no password screen.
Instead the app runs librespot's **zeroconf / Spotify Connect handoff**:

1. App advertises itself on the LAN as a Spotify Connect device ("Offline
   Dpad").
2. User opens Spotify on their regular phone (same Wi-Fi), plays anything,
   taps the devices icon, picks "Offline Dpad".
3. Spotify transfers an encrypted credential blob; librespot logs in with it
   and writes reusable credentials to app-private storage
   (`files/credentials.json`).
4. Every subsequent launch logs in directly from stored credentials — the
   other phone is only needed once.

**A Spotify Premium account is required** (librespot limitation — free
accounts can't stream this way).

## Architecture

```
app/src/main/kotlin/com/offline/dpadspotify/
  SpotifyApp.kt        Application: registers MediaCodec decoders, owns manager
  MainActivity.kt      AppCompatActivity + Compose host
  spotify/
    SpotifyManager.kt  Session/Player lifecycle, zeroconf login, state flows
    WebApi.kt          /v1/search via the session's Login5 bearer token
  focus/               DpadFocusModifiers — copied from dpad-messenger
  ui/                  Login (handoff instructions) → Search → Now Playing
app/src/main/java/xyz/gianlu/librespot/
  android/sink/AndroidSinkOutput.java        AudioTrack sink (vendored, see below)
  player/decoders/AndroidNativeDecoder.java  MediaCodec Vorbis/MP3 decoder (vendored)
```

The two Java files are vendored from
[devgianlu/librespot-android](https://github.com/devgianlu/librespot-android)
(Apache-2.0). `AndroidSinkOutput` is loaded **reflectively** by librespot —
if minification is ever enabled, it must be kept (along with `com.spotify.**`
and `xyz.gianlu.librespot.audio.decoders.**`).

## Why librespot is a JitPack commit hash, not 1.6.5

librespot-java's last Maven Central release (1.6.5, Dec 2024) broke in Aug
2025 when Spotify sunset part of the apresolve spclient pool — every spclient
request 500s ([#1098](https://github.com/librespot-org/librespot-java/issues/1098)).
The fix ([PR #1097](https://github.com/librespot-org/librespot-java/pull/1097))
only exists on the `dev` branch, so `app/build.gradle.kts` pins a JitPack
build of the dev tip (`52a8c24`, Nov 2025). That branch also moved token
minting from the dying Mercury keymaster to Login5 (`session.tokens().get()`,
no scopes) — which is what `WebApi.kt` assumes.

Note librespot-java is **officially deprecated** (development moved to
go-librespot). It still works as of this commit, but it's living on borrowed
time; if this prototype graduates, plan for either go-librespot (gomobile
binding) or rust librespot (NDK) as the long-term core.

## Build & run

```
cd dpad-spotify
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Same toolchain as dpad-messenger: AGP 8.13.2 / Kotlin 2.1.10 / Gradle 8.13
wrapper, compileSdk 36, minSdk 24.

## Known risks / open questions

- **Zeroconf discovery on Android is the flakiest link.** We hold a
  `MulticastLock` (required — Android filters mDNS without it), but
  librespot-android's tracker has reports of "Connecting to device…" stalls
  ([#23](https://github.com/devgianlu/librespot-android/issues/23)). If the
  device won't appear in the Spotify app: confirm both devices are on the
  same subnet, AP isolation is off, and 2.4/5 GHz bands aren't split. The
  fallback path would be an OAuth-PKCE login (librespot dev supports
  `AUTHENTICATION_SPOTIFY_TOKEN` credentials) with the code entered on
  another device.
- **Web API search with a Login5 token** works today but is not a contract;
  if Spotify starts requiring client tokens on `/v1/search`, switch to
  librespot's native `session.search()` (Mercury — also deteriorating) or an
  spclient search call.
- No background playback service yet — audio stops if Android kills the
  process. A `MediaSessionService` + media notification is the obvious next
  step if this graduates.
- No volume-key integration, queueing, or album art (the metadata wrapper
  exposes cover image IDs; trivial to add via `https://i.scdn.co/image/<id>`).
