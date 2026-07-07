# Dumb Signal — Status & Handoff

Forked-Signal backend embedded in the Offline "dumbphone" launcher
(`com.offlineinc.dumbdownlauncher`). This module is the `:signal` Gradle
project under `matrix-app/dpad-messenger-backend/signal/`. The launcher hosts it
via composite build (see `launcher/settings.gradle.kts` →
`includeBuild("../matrix-app/dpad-messenger-backend")`), so building the launcher
recompiles `:signal` from source.

---

## TL;DR — where we are

- The **Signal reconciliation work** (PNI↔ACI thread unification, Storage Service
  sync, sent-transcript fixes, diagnostics) is on **`main`**, commit
  `b4c35dd "Add signal reconsiliation fixes"`. Verified working on-device via
  quack rolling-log captures (identity links learned, threads unified, storage
  sync clean).
- **This session added more fixes on top** (reply-"You", image/attachment sync
  on the DM transcript path, extra diagnostics). These are **uncommitted in the
  working tree** and still need to be committed + pushed. See "Uncommitted
  changes" below.
- ⚠️ **Nothing in this session was compiled** — it was done in an environment
  without the Android SDK. First step for the next session/owner: build and fix
  any compile errors (edits use existing APIs/fields, so risk is low but
  non-zero).
- ⚠️ **Two repos are involved.** Signal code is in `matrix-app`; one diagnostics
  change is in the `launcher` repo. Both must be committed/pushed.

---

## Architecture quick map

- `SignalProvisioningClient.kt` — QR linking / device provisioning. Captures the
  `accountEntropyPool` (AEP) needed for Storage Service.
- `SignalChatWebSocket.kt` — inbound socket. Decodes envelopes; routes incoming
  DataMessages (`receiveIncoming*`) and our own `SyncMessage.Sent` transcripts
  (`dispatchSentTranscript` → `receiveOwnSent*`), reactions, edits, deletes.
- `SignalMessageRepository.kt` — in-memory state (rooms, messages, users,
  contacts). Owns identity/thread reconciliation:
  - `pniToAci` alias map + `canonicalId()` — PNI→owning-ACI canonicalization.
  - `learnIdentityLink(aci, pni)` — records the pairing + merges a split PNI
    thread into the ACI thread.
  - `mergeRecipient(from, to)` — folds one thread into another.
  - `updateContact()` — name + E.164→ACI canonicalization (merges on learn).
  - `dumpThreadKeys()` — diagnostic dump of how every DM thread is keyed.
- `SignalStorageService.kt` + `SignalStorageCrypto.kt` — Storage Service contact
  sync (modern replacement for `SyncMessage.Contacts`). Derives keys from AEP,
  fetches/decrypts the manifest + ContactRecords, feeds them into the repo
  (`updateContact` + `learnIdentityLink`). Crypto verified end-to-end.
- `SignalSender.kt` — outbound sends + `SyncMessage.Sent` transcripts to our own
  siblings (includes the `destinationServiceIdBinary` + `unidentifiedStatus`
  fields modern Signal receivers require).
- UI lives in `matrix-app/dpad-messenger/library/` (`ChatScreen.kt`,
  `MessageBubble.kt`, `ReplyOrEditBanner.kt`, `ui/util/ImageDecode.kt`, …).

---

## What already works (landed on `main`, verified on-device)

1. **QR device linking** — fresh-QR lifecycle (keepalive ping + timed refresh,
   stale-socket guard). Captures AEP for Storage Service.
2. **Storage Service contact sync** — one-shot at connect
   (`SignalRepository.createIfPaired`). Last good capture: `manifestContacts=873
   applied=856 decryptFail=0`. Needs AEP → devices linked before AEP capture must
   re-link (logged as `no accountEntropyPool — re-link to enable`).
3. **PNI↔ACI thread unification** — Storage carries both ids per contact;
   `learnIdentityLink` records `pni→aci` and merges split threads. Confirmed:
   `THREAD-DUMP: 4 DM threads, 111 PNI->ACI aliases`, all threads keyed by ACI
   with real names, zero "Unknown"/PNI-keyed splits.
4. **Sent-transcript to siblings** — DM transcript sets
   `destinationServiceIdBinary` + `unidentifiedStatus`, so messages sent from the
   dumbphone show up on the user's other linked Signal clients (iOS/macOS/Beeper).
5. **"Unknown" label** for unresolvable contacts (Signal's own term).
6. **Rolling-log capture for quack** — `RebootLoggingConfig.ROLLING_LOGCAT_
   FILTERSPEC` allow-lists Signal tags at DEBUG so quack uploads carry the Signal
   diagnostics. (This constant lives in the **launcher** repo.)

---

## Uncommitted changes in the working tree (THIS SESSION — commit these)

Confirmed by the user on-device: **images sent from another linked device were
being dropped** in Dumb Signal. Root cause found + fixed, plus two smaller items.

### 1. Image/attachment sync on the DM transcript path (the main fix)
Files: `SignalMessageRepository.kt`, `SignalChatWebSocket.kt`

`dispatchSentTranscript` built the `Attachment` and passed it to the **group**
path (`receiveOwnSentGroup`) but the **DM** path (`receiveOwnSent`) had no
attachment parameter and early-returned on blank body — so an image-only message
sent from another device (e.g. the user's iPhone) was silently discarded.

- `receiveOwnSent(...)` now takes `attachment: Attachment? = null` and
  `quotedTimestamp: Long? = null`; drop guard changed to
  `if (body.isBlank() && attachment == null) return`; resolves `replyToLocalId`
  from the quote and sets `attachment` + `replyToId` on the `Message`
  (mirrors `receiveIncoming` / `receiveOwnSentGroup`).
- `dispatchSentTranscript` now passes `attachment` + `quotedTimestamp` to
  `receiveOwnSent`.
- This should also relieve the "reply cut off / stuck" symptom, since those
  replies quoted an image that previously never got created locally.

### 2. Reply-to-own-message shows "You" (not your phone number)
File: `dpad-messenger/library/.../ui/chat/ChatScreen.kt` (reply banner)

`senderName = if (replyTarget.isOutgoing) "You" else senderNameFor(replyTarget.senderId)`

### 3. Diagnostics for the transcript/attachment path
Files: `SignalChatWebSocket.kt`, `SignalMessageRepository.kt`, and the launcher's
`RebootLoggingConfig.kt`.

- `SignalChatWS`: `sync.sent dest=… body=… attachments=N builtAttach=… quote=…`
  on every DM transcript, and `sync.sent dropped: N attachment(s), none buildable`
  when a media pointer can't be built.
- `SigRepo`: `own-sent applied room=… body=… attach=… reply=…` on success.
- Launcher `ROLLING_LOGCAT_FILTERSPEC` gained `SignalChatWS:D` so these reach
  quack.

Modified files (working tree):
```
matrix-app:
  dpad-messenger-backend/signal/.../SignalMessageRepository.kt
  dpad-messenger-backend/signal/.../SignalChatWebSocket.kt
  (SignalSender.kt / SignalStorageService.kt show as modified because main's
   b4c35dd reconciliation code was pulled into this branch's working tree)
  dpad-messenger/library/.../ui/chat/ChatScreen.kt
launcher:
  app/src/main/java/.../diagnostics/RebootLoggingConfig.kt   (SignalChatWS:D)
```

### Branch note
These edits were made while checked out on `feat/smarttxt-backend`, with `main`'s
Signal files pulled into the working tree (no merge commit). To land on `main`:
commit the Signal + ChatScreen changes there (they originate from `main`'s code),
and separately commit the launcher `RebootLoggingConfig.kt` change in the launcher
repo. Keep the uncommitted **SmartTxt** changes on `feat/smarttxt-backend` out of
the Signal commit.

---

## How to test the image fix (repro confirmed)

Send an image from the user's iPhone (primary) → it transcripts to the linked
Dumb Signal. Android Studio Logcat (set level to **Debug**):

- Tag filter: `tag:SignalChatWS | tag:SigRepo | tag:SignalSender`
- Or message regex: `message~:"sync\.sent|own-sent applied|sent-transcript delivered"`

Read the result:
- **Success:** `sync.sent … attachments=1 builtAttach=true` then
  `own-sent applied … attach=true`.
- `sync.sent dropped: 1 attachment(s), none buildable` → pointer missing
  cdn/key/digest (pointer-build issue, not the drop bug).
- `sync.sent … builtAttach=true` but no `own-sent applied` → failing inside
  `receiveOwnSent` (look for `repository.receiveOwnSent failed`).
- **No `sync.sent` line at all** → transcript isn't reaching Dumb Signal
  (upstream: iPhone not sending it / not decrypting). Not covered by this fix.

---

## Open issues / next steps

1. **Compile the working-tree changes** (Android SDK env). Not yet built.
2. **HEIC images.** Signal iOS usually transcodes photos to JPEG, so most images
   arrive as `image/jpeg`. If a real `image/heic` arrives and the TCL Flip 2
   lacks an HEVC/HEIF decoder, `ImageDecode.decodeDownscaled` returns null and the
   bubble shows "can't preview" (already handled gracefully — not a crash/drop).
   If testing shows this, add **transcode-on-download (HEIC→JPEG)** in
   `SignalAttachments.download` (contained change).
3. **"Stuck reply / can't scroll" UI symptom** — hypothesized downstream of the
   missing quoted image; should improve with the image fix. If it persists, it's a
   separate `ChatScreen` compose-state bug (untouched).
4. **Storage sync is one-shot at connect.** An already-split thread only heals on
   next app start, and the `dumpThreadKeys` dump can race the local-store load on a
   cold connect (harmless; alias count is the reliable signal). Consider a
   **re-sync on socket reconnect and/or periodic** so splits heal without a cold
   start.
5. **Separate broken-session decrypt failures** seen earlier from one contact
   device (`invalid PreKey message: decryption failed`) — unrelated to sync; not
   addressed.
6. **PniSignatureMessage** capture + ACI-preferred sending for full PNP merge —
   deferred/offered, not implemented.
7. Consider making re-link **preserve history / heal in place** instead of wiping.

---

## Diagnostic tags (allow-listed in the rolling log / quack)

`SigRepo`, `SignalRepo`, `SignalStorage`, `SignalSender`, `SignalChatWS`,
`SignalContactSync`, `SignalCDSI`. Grep a submitted log for: `THREAD-DUMP`,
`identity link`, `storage sync:`, `sync.sent`, `own-sent applied`,
`sent-transcript`.
