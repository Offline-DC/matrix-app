# matrix-app

Messenger work for the dumb-down ecosystem. Two Gradle builds:

- **dpad-messenger/** — the shared chat UI library (Compose screens, navigation,
  `MessageRepository` interface). Consumed as a composite build by both
  dpad-messenger-backend and dumb-down-launcher.
- **dpad-messenger-backend/** — backends: Signal (device-link provisioning,
  chat WebSocket), Matrix, and Google Messages (`:gmessages` — QR pairing
  against the user's primary Android phone via Google's "Messages for web"
  relay, plus its Compose pairing/chat UI). References `../dpad-messenger`
  (sibling in this repo), so cloning this repo alone is self-contained for
  backend work.

dumb-down-launcher (separate repo) expects this repo cloned next to it. The
launcher composite-includes the backend build (and, transitively through it,
the UI library):

    ~/repos/matrix-app/dpad-messenger
    ~/repos/matrix-app/dpad-messenger-backend
    ~/repos/dumb-down-launcher   (includeBuild("../matrix-app/dpad-messenger-backend"))
