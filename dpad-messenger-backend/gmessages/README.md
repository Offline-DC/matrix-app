# :gmessages

In-app Google Messages backend **and** its pairing/chat UI. Pairs an Android
device as a linked device against the user's primary Android phone (the one
running the actual Google Messages app), then sends and receives RCS/SMS
through it. Backend (protocol, crypto, session, repository) plus the Compose
pairing screens live here so any host app can plug in.

Lives in `dpad-messenger-backend` alongside the Signal and Matrix backends.
`dumb-down-launcher` consumes it via Gradle composite build — its
`MessengerActivity` is a thin shell that hosts `GoogleMessagesApp()` from this
module. (The backend was originally developed inside the launcher's
`:gmessages` module and moved here so the matrix/non-launcher code is shared.)

Same architectural pattern as the Signal direct-mode backend in
`dpad-messenger-backend/signal` — see that module's source for what a
finished port looks like.

## Status

- [x] **Phase A — plumbing.** Gradle module wired into the launcher;
      `MessengerActivity` hosts `DpadMessengerApp` from the UI lib; mock
      backend so the chat screens render with demo data.
- [ ] **Phase B — pairing.** Port `mautrix-gmessages/pkg/libgm/pairing.go`
      to Kotlin. QR code, ECDH with primary, persisted device pair.
- [ ] **Phase C — receive + send + sync.** Port the relay WebSocket, the
      RPC layer for outbound, contact + read-receipt sync.

## Why the architecture this way

A host app that only needs *one* messenger backend — Google Messages — can
skip the `BackendConfig` / `BackendFactory` indirection: this module's
`GoogleMessagesRepository.create(context)` returns a `MessageRepository`
directly and `GoogleMessagesApp()` plugs it into `DpadMessengerApp`. Less
code, no runtime backend switching, easier to follow. The launcher does
exactly this; `MessengerActivity` is just a window around `GoogleMessagesApp()`.

Host-app glue is kept out of the module: the incoming-message notification's
tap target is supplied by the host through `GoogleMessagesConfig
.messengerActivityClassName` rather than hardcoding an Activity name.

The UI library (`com.offline.dpadmessenger:library`) is consumed via Gradle
composite build from the sibling `../dpad-messenger` repo. Wiring lives in
`dpad-messenger-backend/settings.gradle.kts` (and, for the launcher, in
`dumb-down-launcher/settings.gradle.kts`, which composite-includes this whole
backend build).

## Phase B — pairing port

mautrix-gmessages reference (Go): https://github.com/mautrix/gmessages

Key files to port:

| Go file | Kotlin target |
|---|---|
| `pkg/libgm/pairing.go` | `GoogleMessagesPairingClient.kt` |
| `pkg/libgm/types/pairing.go` | (inline data classes inside above) |
| `pkg/libgm/crypto/aes_ctr.go` | `GMCrypto.kt` (AES-CTR helpers) |
| `pkg/libgm/util/proto.go` | (drop; use protobuf-javalite directly) |
| protos in `pkg/libgm/binary/` | drop alongside our SignalService.proto |

Pairing flow at a glance:

1. Generate a fresh ECDH keypair (Curve25519, like Signal).
2. Open WebSocket to `instantmessaging-pa.googleapis.com` (a pubsub relay).
3. Generate a QR URL containing the public key + a random session-id.
4. User scans QR with their primary phone's Google Messages app
   (Settings → Device pairing).
5. Primary sends back a `PairingResponse` over the pubsub channel containing:
   - their device identity proto (browser_id, user_agent, etc.)
   - their own ECDH public key
   - a refresh token bound to this pair
6. We derive a shared secret via X25519, persist
   `(tachyon_refresh_token, browser_id, user_agent, primary_pubkey)` in
   `EncryptedSharedPreferences`.

Implementation order:
1. **Protos first** — copy the `.proto` files out of mautrix-gmessages,
   register them in `gmessages/build.gradle.kts` the same way
   `dpad-messenger-backend/signal/build.gradle.kts` registers SignalService.proto.
2. **`GMAuthInterceptor`** that adds the Tachyon bearer token to outgoing
   HTTPS calls.
3. **`GoogleMessagesPairingClient`** — mirrors `SignalProvisioningClient`:
   - `start()` opens the WS and returns a flow of states
     (`Connecting → WaitingForScan(qrUrl) → Linked(account) | Failed(...)`)
   - `cancel()` tears down the WS
4. **`GoogleMessagesAccountStore`** — mirrors `SignalAccountStore`:
   `EncryptedSharedPreferences`-backed save/load of the pair info.

Time estimate: 3–5 focused days.

## Phase C — receive + send + sync

| Go file | Kotlin target |
|---|---|
| `pkg/libgm/client.go` | `GMRelayWebSocket.kt` (long-poll receive) |
| `pkg/libgm/sending.go` | `GMSender.kt` |
| `pkg/libgm/messages.go` | wire into `GMMessageRepository.kt` |
| `pkg/libgm/contacts.go` | contact resolution (call sites in the repo) |

Key things mautrix already solved that we'll need:

- **Token refresh** — the Tachyon bearer expires; `pkg/libgm/auth.go`
  refreshes it via a sibling-RPC. Mirror as `GMSender.refreshTokenIfNeeded`.
- **Server-side ack** — every received message needs an HTTP-200-style ack
  back through the pubsub channel or the server retries. Same shape as
  `SignalChatWebSocket.sendOk`.
- **Per-conversation read state** — Google tracks per-thread read cursors
  rather than per-message receipts.

Time estimate: 1–2 focused weeks.

## Local dev pointers

Build / test just this module (from the `dpad-messenger-backend` repo root):
```
./gradlew :gmessages:assembleDebug
./gradlew :gmessages:testDebugUnitTest   # X25519 + protobuf + pblite + session + crypto
```

Build the launcher with the messenger wired in (from `dumb-down-launcher`):
```
./gradlew :app:assembleDebug
```

The composite build pulls in `../dpad-messenger` automatically — make sure
that sibling repo exists at the expected path before building.

Versions (Kotlin, AGP, etc.) are pinned in this repo's root
`build.gradle.kts`. If you bump the Kotlin version, also bump
`dpad-messenger/library/build.gradle.kts` to match (composite builds
require the consumer's Kotlin ≥ producer's).
