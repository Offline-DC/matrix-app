# matrix-app

Messenger work for the dumb-down ecosystem. Two Gradle builds:

- **dpad-messenger/** — the shared chat UI library (Compose screens, navigation,
  `MessageRepository` interface). Consumed as a composite build by both
  dpad-messenger-backend and dumb-down-launcher's `:gmessages` module.
- **dpad-messenger-backend/** — backends: Signal (device-link provisioning,
  chat WebSocket) and Matrix. References `../dpad-messenger` (sibling in this
  repo), so cloning this repo alone is self-contained for backend work.

dumb-down-launcher (separate repo) expects this repo cloned next to it:

    ~/repos/matrix-app/dpad-messenger
    ~/repos/dumb-down-launcher   (includeBuild("../matrix-app/dpad-messenger"))
