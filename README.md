# matrix-app

Messenger work for the dumb-down ecosystem. Three Gradle builds:

- **dpad-messenger/** — the shared chat UI library (Compose screens, navigation,
  `MessageRepository` interface). Consumed as a composite build by both
  dpad-messenger-backend and dumb-down-launcher.
- **dpad-spotify/** — standalone prototype: DPAD-first Spotify client on rust
  librespot via JNI (zeroconf login handoff, search, playback). See its README.
- **dpad-messenger-backend/** — backends: Signal (device-link provisioning,
  chat WebSocket), Matrix, Google Messages (`:gmessages` — QR pairing against
  the user's primary Android phone via Google's "Messages for web" relay, plus
  its Compose pairing/chat UI), and SmartTxt (`:smarttxt` — native rustpush-
  over-JNI in direct mode, currently stub/relay while the closed-source absinthe
  validation engine is worked out). References `../dpad-messenger` (sibling in
  this repo), so cloning this repo alone is self-contained for backend work.
  The SmartTxt backend was migrated in from the standalone `imessage-app` repo;
  its native/reverse-engineering scaffolding (`smarttxt-ffi/`, `relay-reference/`,
  `SMARTTXT_NATIVE_BACKEND_PLAN.md`, `ABSINTHE_REVERSE_ENGINEERING.md`) lives
  under `dpad-messenger-backend/`.

dumb-down-launcher (separate repo) expects this repo cloned next to it. The
launcher composite-includes the backend build (and, transitively through it,
the UI library):

    ~/repos/matrix-app/dpad-messenger
    ~/repos/matrix-app/dpad-messenger-backend
    ~/repos/dumb-down-launcher   (includeBuild("../matrix-app/dpad-messenger-backend"))

## License

matrix-app is licensed under the **GNU Affero General Public License v3.0**
(AGPL-3.0-only) — see [`LICENSE`](LICENSE). The messenger app links Signal's
[`libsignal`](https://github.com/signalapp/libsignal), which is AGPL-3.0, so
the combined/derived work is distributed under the same copyleft terms
(including AGPL §13's network-use source-availability requirement).

The `dpad-spotify/rust` prototype keeps its upstream **MIT** license (MIT is
AGPL-compatible and can be combined into an AGPL work); its `Cargo.toml`
retains the MIT declaration for attribution.
