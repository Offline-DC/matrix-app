# Phase 5 — Signal on device (status & finishing recipe)

Goal: log into your existing Signal account from this app by linking it
as a secondary device (the same way Signal Desktop works), so you can
read/write Signal conversations on the Flip 2.

## Architecture: direct mode (default)

```
┌─────────────────┐                                 ┌────────────────┐
│  dpad-messenger │  libsignal-android (in-process) │  Signal server │
│      UI         │ ◄──────────────────────────────►│ chat.signal.org│
└─────────────────┘                                 └────────────────┘
                            ▲
                            │
                ┌───────────┴────────────┐
                │ SignalMessageRepository│  implements UI's MessageRepository
                │ SignalChatWebSocket    │  receives messages
                │ SignalProvisioning…    │  one-time link flow
                │ SignalAccountStore     │  encrypted credentials
                └────────────────────────┘
```

No Conduit, no Matrix bridge between UI and Signal. The app behaves as a
Signal device. Simpler than the Matrix-bridge variant — fewer moving
parts, but you can't add other protocols (e.g. SMS) alongside in the
same app without significant refactor.

(See the original "via Matrix bridge" architecture if you want
multi-protocol coexistence; the Matrix bridge approach lives behind
`BackendConfig.SignalBridge` and would be Phase 5b.)

## What's wired in this delivery (Layer 2 + 2.5)

Done and runnable on-device:

- **Full link flow end-to-end.** TLS → provisioning WebSocket → ECC
  keypair → QR URL → scan → ProvisionMessage decryption (Layer 2) →
  prekey generation (Curve25519 signed + Kyber-1024 last-resort, for
  both ACI and PNI identities) → `PUT /v1/devices/<provisioningCode>`
  HTTPS confirm call → server-assigned `deviceId` → persisted
  `SignalAccount`. After scanning, the new device **appears in your
  primary phone's Settings → Linked Devices list**.
- **`SignalKeys`** — wraps libsignal's `Curve` and `KEMKeyPair`
  primitives plus the identity-key signature step. Produces the
  JSON-shaped records the server's `confirmDevice` body wants.
- **`SignalApi.confirmDevice`** — real HTTPS call with HTTP-Basic auth,
  ConfirmDeviceRequest JSON body via kotlinx.serialization, parses
  ConfirmDeviceResponse for the server-assigned `deviceId`. Logs both
  the outbound body and inbound response to `SignalApi` logcat tag.
- **Authenticated chat WebSocket.** `SignalChatWebSocket` connects to
  `wss://chat.signal.org/v1/websocket/` with the post-link credentials
  (`<aci>.<deviceId>:<password>`), parses inbound
  `WebSocketMessage` envelopes, acks each request with a 200 response
  so the server keeps streaming, sends `/v1/keepalive` every 30s,
  reconnects with backoff on failure.
- **`SignalAccount` + `SignalAccountStore`** — credential model and
  encrypted SharedPreferences persistence. Now carries both ACI and
  PNI registration IDs and identity-key blobs.
- **`SignalLinkScreen` (in `:app`)** — Compose UI showing the QR with
  instructions, driven off `SignalProvisioningResult`.

## What's wired in this delivery (Layer 3 — partial)

- **`SignalProtocolStore` implementation.** `AndroidSignalProtocolStore`
  implements libsignal's full `SignalProtocolStore` interface
  (IdentityKeyStore + PreKeyStore + SignedPreKeyStore + KyberPreKeyStore
  + SessionStore) backed by `SignalProtocolPrefs`, which uses one
  `EncryptedSharedPreferences` file per category. Trust-on-first-use for
  identity validation.
- **Private prekey persistence at link time.** `registerDevice` now
  saves the signed-prekey and Kyber last-resort records to the protocol
  store immediately after the server-side confirm so PreKeySignalMessages
  from peers can be decrypted.
- **One-time prekey generation + upload.** 100 one-time Curve25519
  prekeys generated, persisted to the store, and uploaded via
  `PUT /v2/keys?identity=aci` so first-contact peers can fetch a usable
  bundle. Best-effort — failures here log but don't fail the link.
- **Inbound decryption.** `SignalChatWebSocket.handleIncomingMessage`
  now wires `SessionCipher` over the protocol store: handles
  `PREKEY_MESSAGE` (PreKeySignalMessage → session bootstrap + decrypt)
  and `DOUBLE_RATCHET` (SignalMessage → decrypt). `UNIDENTIFIED_SENDER`
  (sealed sender) is logged and skipped — see TODO below.
- **Dynamic conversation creation.** `SignalMessageRepository
  .receiveIncoming(senderServiceId, …)` creates a direct-chat `Room`
  the first time we hear from a peer, upserts a placeholder `User`,
  appends the decrypted message, and bumps the room to the top of the
  list with an incremented unread count.

## Status update — core messenger is now implemented

The list below is the ORIGINAL Phase-5 gap list; most of it has since
landed. Current reality:

- **Outbound send — DONE.** `SignalMessageRepository.sendMessage` →
  `SignalSender.sendDirectMessage`: prekey fetch (cached), session
  establish via `SessionBuilder.process`, sealed-sender encrypt per
  device, `PUT /v1/messages`, plus a `SyncMessage.Sent` transcript to
  our own devices.
- **Sealed sender (`UNIDENTIFIED_SENDER`) decrypt — DONE.**
  `SignalChatWebSocket.decryptSealedSender` walks the production trust
  roots and uses `SealedSessionCipher`; SenderKey distribution messages
  are processed so group/multi-recipient follow-ups decrypt.
- **Interactive features — DONE.** Reactions, replies (Quote), edits
  (`Content.editMessage`), "delete for everyone" (`DataMessage.delete`),
  and read/delivery receipts (`ReceiptMessage`) all send and apply
  inbound. See `SignalSender.send{Reaction,RemoteDelete,Edit,ReadReceipt}`
  and the `applyIncoming*` hooks on the repository.
- **Media — DONE (send needs on-device CDN verification).** Inbound:
  `AttachmentPointer` → download (`SignalApi.downloadAttachment`) +
  decrypt (`SignalAttachmentCrypto.decrypt`) + cache, behind
  `MediaDownloader`. Outbound: `SignalAttachments.upload` encrypts and
  uploads via the v4 form + TUS resumable upload, then
  `SignalSender.sendAttachment` sends the pointer, behind
  `AttachmentSender`. The download/decrypt half is the same path contact
  sync uses in production; the upload half can't be exercised in CI and
  needs a device test against the live CDN.
- **Contact sync — DONE.** `SyncMessage.Contacts` downloaded, decrypted,
  parsed, and applied to room/contact names.

### Primary↔Flip sync — DONE

`dispatchSentTranscript` now applies `SyncMessage.Sent` transcripts for
outgoing messages, reactions, edits, deletes, and media — in both DMs and
groups — so actions taken on the primary phone reflect on the Flip and
vice-versa. (DM own-sent *media* transcripts still render as text-only;
group own-sent media is carried.)

### Groups (GroupsV2) — DONE, needs on-device verification

Read + send both implemented:

- **Identity.** `SignalGroups.groupIdForMasterKey` derives a stable group
  id from `GroupContextV2.masterKey` via zkgroup
  (`GroupSecretParams` → `GroupIdentifier`); group rooms are
  `sig:group:<base64 id>`.
- **State.** `SignalGroups.getOrFetch` pulls weekly auth credentials
  (`GET /v2/auth`), builds a zkgroup auth presentation, fetches the
  encrypted `Group` from `storage.signal.org/v1/groups/`, and decrypts the
  title + member ACIs with `ClientZkGroupCipher`. Cached ~10 min.
- **Inbound.** Group text/media/reactions/edits/deletes route to the group
  room (`receiveIncomingGroup` + `applyIncomingGroup*`).
- **Outbound.** `SignalSender.sendGroup*` builds the `groupV2` context and
  fans the message out to each member as an individual sealed-sender
  message (the simple, universally-accepted fallback to SenderKey
  multi-recipient fan-out), plus an own-device transcript.

⚠️ **Two things to verify on a device** (neither can run in CI):
  1. `SignalGroups.SERVER_PUBLIC_PARAMS_B64` MUST be filled with Signal's
     real production zkgroup server public params (a public constant from
     Signal-Android `BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS`). It's blank
     on purpose so it fails loudly rather than silently mis-deriving.
  2. The exact zkgroup method names against libsignal-android 0.86.5
     (`receiveAuthCredentialWithPniAsServiceId`,
     `createAuthCredentialPresentation`, `decryptServiceId`, `decryptBlob`).

### Large-group SenderKey fan-out — DONE, needs on-device verification

Group sends past `SignalSender.SENDERKEY_THRESHOLD` (5 other members) take
the SenderKey multi-recipient fast path: encrypt the Content ONCE with
`GroupCipher`, distribute our `SenderKeyDistributionMessage` to members that
need it (tracked per group+revision so later sends skip it), wrap as an
`UnidentifiedSenderMessageContent`, `SealedSessionCipher
.multiRecipientEncrypt` the whole group, and deliver in a single
`PUT /v1/messages/multi_recipient` authorized by a zkgroup
`Group-Send-Token` (from `GroupResponse.groupSendEndorsementsResponse`).

Crucially this is an **optimization with graceful fallback**: the entire
SenderKey path runs under `runCatching`, and ANY failure (or a small group)
falls back to the proven per-member fan-out. So even if the version-sensitive
libsignal calls (`GroupCipher`, `multiRecipientEncrypt`,
`UnidentifiedSenderMessageContent`) or the zkgroup endorsement API differ on
0.86.5, group send still works — it just isn't using the single-PUT path
until those are verified on-device.

### Read receipts — intentionally NOT sent

By product decision we do not send Signal read receipts: `markRoomRead` only
clears the local unread badge. (Inbound delivery/read receipts that peers
choose to send US are still reflected on our own sent bubbles.)

### Still open (nice-to-haves, not blockers)

- **Group membership changes / `groupChange`.** We refetch full state on a
  TTL; we don't apply incremental `groupChange` blobs.
- **Persisted SenderKey distribution ids.** The per-group distributionId is
  in-memory, so the first group send after an app restart re-distributes the
  SKDM. Persisting it would avoid that.
- **Periodic one-time prekey top-up.** Server drains the uploaded bundle
  as peers consume keys; a background job should refill once the
  available count drops below ~10.
- **Stale-device handling.** A `409/410` from `PUT /v1/messages` should
  invalidate the cached prekey bundle and retry; today it surfaces as a
  failed send.

## What needs filling in to actually exchange messages

All marked `TODO(proto)` or `TODO(signal)` in source. The work is
concentrated in three files:

### 1. Protobuf glue (~one afternoon, mostly mechanical)

Signal's wire protocol uses protobuf. The schemas you need:

- `WebSocketResources.proto` — the WebSocketMessage envelope used by
  both the provisioning and chat sockets.
- `Provisioning.proto` — `ProvisionEnvelope` (outer, encrypted) and
  `ProvisionMessage` (inner, plaintext after `ProvisioningCipher`).
- `SignalService.proto` — `Envelope` (outer for chat messages),
  `DataMessage` (text/edit/reaction/delete/attachment), `SyncMessage`
  (read receipts, contact sync), `Content` (top-level union).

These live in
[Signal-Android's `libsignal-service/src/main/proto/`](https://github.com/signalapp/Signal-Android/tree/main/libsignal-service/src/main/proto).
Copy them into `signal/src/main/proto/`. Add the protobuf-javalite
plugin:

```kotlin
// signal/build.gradle.kts
plugins {
    id("com.google.protobuf") version "0.9.4"
    // ...
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.25.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                register("java") { option("lite") }
                register("kotlin") { option("lite") }
            }
        }
    }
}
```

Once these compile, fill in `SignalProvisioningClient.handleBinaryFrame`
to parse `WebSocketMessage`, dispatch by request path, and call
`onUuidReceived` / `onProvisionMessage`.

### 2. Provisioning decrypt (~half a day)

Replace the `TODO(proto)` in `SignalProvisioningClient.onProvisionMessage`:

```kotlin
val envelope = ProvisionEnvelope.parseFrom(envelopeBytes)
val cipher = ProvisioningCipher(keys)        // libsignal class
val plaintext = cipher.decrypt(envelope)
val msg = ProvisionMessage.parseFrom(plaintext)
// ... pull aci, pni, profile_key, number, provisioning_code,
//     identityKeyPrivate, etc. out of msg.
```

Then call Signal's `/v1/devices/<provisioning_code>` HTTPS endpoint with
our chosen password + capabilities to finalize. The server returns the
`deviceId` (an integer ≥ 2). Persist the full `SignalAccount`.

mautrix-signal reference: `pkg/signalmeow/provisioning.go`.

### 3. Receive + send (~one week)

`SignalChatWebSocket.handleFrame`: parse the inbound envelope, decrypt
via `SignalServiceCipher` / `SealedSessionCipher` (libsignal classes),
build a `Message`, push to `repository.receiveIncoming()`.

`SignalMessageRepository.sendMessage`: prekey fetch, session establish,
encrypt, PUT to `/v1/messages/<recipient_aci>`. mautrix-signal reference:
`pkg/signalmeow/sending.go::SendMessage`.

The trickiest piece is per-recipient `SignalProtocolStore` — the local
database of session/identity/prekey state. Reference Signal-Android's
`SignalProtocolStoreImpl` for the schema; you'll likely want SQLite
(Room) here instead of SharedPreferences.

## How to test what's working today

The wired app already runs end-to-end with these caveats:

1. **Boot.** App launches in mock mode (since no Signal account exists
   yet). UI works fully against the mock data.
2. **Link button.** In mock mode you'll see a small "Link Signal device"
   button at the top. Tap it.
3. **Provisioning.** App opens a WebSocket to `chat.signal.org` —
   reaches the server, generates real ECC keys.
4. **Where it currently fails.** The first inbound binary frame from
   Signal triggers `handleBinaryFrame`, which `_state.value =
   SignalProvisioningResult.Failed(...)` because protobuf decoding
   isn't wired up. UI shows the failure message — that's expected
   until you complete step 1 above.

After protobuf glue is in, the same flow will:
- Show a real scannable QR code.
- Wait for the user to scan it from their primary Signal device.
- Decrypt the provisioning message and persist the account.
- Switch the UI into Signal mode.

## Hard caveats

- **Signal explicitly forbids 3rd-party clients.** Beeper Mini's
  experience: their accounts were repeatedly blocked by Signal. There
  is no "production safe" path here.
- **No history sync without extra work.** Newly-linked devices don't
  get past messages by default. Signal sends a one-time contact +
  group sync after linking; you can request it via SyncMessageRequest.
- **No voice/video calls.** Signal calling is a separate WebRTC stack;
  out of scope for this bridge.
- **libsignal-android version churn.** Class names move between
  versions. Verify `ProvisioningCipher`, `SignalProtocolStore`,
  `SessionCipher`, `SealedSessionCipher` paths against
  [the published Maven javadocs](https://central.sonatype.com/artifact/org.signal/libsignal-android).

## References

- mautrix-signal source (Go reference for protocol handling):
  <https://github.com/mautrix/signal>
- Signal-Android (Kotlin reference for libsignal-android usage):
  <https://github.com/signalapp/Signal-Android>
- libsignal docs:
  <https://central.sonatype.com/artifact/org.signal/libsignal-android>
- Provisioning URL format (sgnl://linkdevice):
  See Signal-Android `ProvisioningSocket.java`.
