# Matrix backend — what's done, what's stubbed

This module wraps `matrix-rust-sdk` (Element X's underlying client library)
behind the `MessageRepository` interface the UI consumes. Below is the
honest state of each method, ordered roughly by importance.

## Working

- **Login / logout / restore.** `MatrixAuth.login` runs `m.login.password`
  against any homeserver URL, persists the session via encrypted
  `SessionStore`, returns a live `Client`. `restore()` rehydrates from
  disk on cold start.
- **Room list.** `observeRoomSummaries()` subscribes to the SDK's
  `RoomListService` sliding-sync stream. Emits a sorted list of
  `RoomSummary` as the SDK pushes updates.
- **Timeline.** `observeMessages(roomId)` subscribes to the per-room
  `Timeline` and maps each item via `MessageMapping`.
- **Send.** `sendMessage(roomId, body, replyToId)` posts a text event via
  the timeline's `send()`. Returns an optimistic local-echo `Message` so
  the UI can render it instantly; the subscriber replaces it with the
  server-confirmed event when `/send` completes.
- **Edit / delete / react / paginate.** Wired to the corresponding
  `Timeline` methods. Verify against your SDK version's exact API names.
- **Mark-as-read.** `markRoomRead` calls `Room.markAsRead()`.

## Stubbed / minimal

- **Media messages.** `MessageMapping.toMessage` only handles
  `MessageType.Text` and skips everything else. Adding `Image`, `Video`,
  `Audio`, `File`, and `Sticker` means:
  1. Extending the UI's `Message` model with `attachments: List<Attachment>`.
  2. Resolving media via `Client.session().mediaSource()` for thumbnails.
  3. A media viewer composable in the UI.
- **Reply quote rendering.** `Message.replyToId` is set, but the UI
  resolves the parent snippet itself by calling `getMessage`. The Matrix
  SDK exposes the in-reply-to event content directly on
  `EventTimelineItem.inReplyTo()` — eventually we should plumb that
  through to avoid the round-trip.
- **E2EE error rendering.** A timeline event whose decryption fails comes
  through as `MessageType.Unknown` or `MessageType.Notice` depending on
  SDK version. We currently skip those. Should render as "⚠ Couldn't
  decrypt — verify your devices."
- **Reactions as first-class.** We expose `Message.reactions`, but the
  UI's `toggleReaction` round-trips through the timeline. Newer SDK
  versions expose reactions directly on the timeline item — wire that
  through.
- **Typing indicators / read receipts.** Not exposed yet. The SDK has
  both (`Room.startTyping()`, `Timeline.readReceipts()`).
- **Device verification UI.** The cross-signing / emoji-SAS flow is not
  implemented. Without it, E2EE rooms display "unverified" warnings.
  This is the single biggest piece of UX work that separates this from
  a usable Element-class client.

## Things to verify against your matrix-rust-sdk version

`matrix-rust-sdk` Kotlin bindings (`org.matrix.rustcomponents:sdk-android`)
churn fast between minor versions. The code in this module was written
against `0.2.74` and uses these symbols — verify each on bump:

- `ClientBuilder.homeserverUrl().sessionPath().build()`
- `Client.login(username, password, initialDeviceName, deviceId)`
- `Client.session()` returning `Session(userId, accessToken, deviceId, refreshToken, …)`
- `Client.restoreSession(Session(...))`
- `Client.roomListService().allRooms().entriesFlow { ... }`
- `Client.getRoom(roomId)` returning `Room?`
- `Room.timeline()` returning `Timeline`
- `Timeline.subscribeToTimelineDiffs { items -> ... }`
- `Timeline.send(MessageType.Text(body))`
- `Timeline.edit(eventId, newBody, formattedBody)`
- `Timeline.redact(eventId, reason)`
- `Timeline.toggleReaction(eventId, emoji)`
- `Timeline.paginateBackwards(limit)` and `Timeline.paginationStatus()`
- `Room.markAsRead()`

If any of these have been renamed, the compiler will tell you. Search
the bindings JAR (or
[matrix-rust-sdk repo](https://github.com/matrix-org/matrix-rust-sdk))
for the new name.

## Testing without a real account

For development you can run a local Synapse + register a test user, or
use [matrix.org] with a throwaway account. The
[`dpad-messenger` mock repo](../../dpad-messenger/library/src/main/kotlin/com/offline/dpadmessenger/data/InMemoryMessageRepository.kt)
is sufficient for UI iteration so you don't burn a real account during
development.
