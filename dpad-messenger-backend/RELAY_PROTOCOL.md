# SmartTxt relay protocol

The contract between the app (`RelayWebSocketTransport`) and a relay server.
A reference implementation is in [`relay-reference/server.js`](relay-reference/server.js);
the canonical types are in
[`transport/SmartTxtTransport.kt`](smarttxt/src/main/kotlin/com/offline/dpadmessenger/backend/smarttxt/transport/SmartTxtTransport.kt)
and [`transport/RelayProtocol.kt`](smarttxt/src/main/kotlin/com/offline/dpadmessenger/backend/smarttxt/transport/RelayProtocol.kt).

The relay holds the parts that must run next to Apple — the closed-source
absinthe validation engine and a live Apple/IDS connection (e.g. rustpush on a
Mac, or a BlueBubbles server). The phone is a thin authenticated client. The
field shapes are deliberately **BlueBubbles-aligned** so an existing
BlueBubbles/OpenBubbles relay maps in with a thin adapter.

## Connection

- One **WebSocket** per device: `GET {baseUrl}/smarttxt/v1/socket`, `http(s)` is
  upgraded to `ws(s)` by the client.
- Auth: `Authorization: Bearer <token>` header if `relayAuthToken` is set.
- The client sends `{"type":"ping"}` every ~25s; the relay may ignore it.
- The client auto-reconnects with exponential backoff (1s→30s) and **re-syncs**
  (`get_chats` + `get_messages` per chat) on every (re)connect, so the relay can
  stay simple — it does not need to replay missed events, just answer syncs.

## Frames

All frames are single JSON objects.

**Request (client → relay):** `{"type":<string>, "id":<int>, ...fields}`
**Response (relay → client):** `{"type":"ack", "id":<same int>, "ok":<bool>, "data":<object>, "error":<string?>}`
**Push (relay → client, unsolicited):** `{"type":<push>, ...fields}` (no `id`)

## Requests

| type | fields | ack `data` |
|---|---|---|
| `register` | `appleId`, `config` (serialized MacOSConfig / dumb file) | `{ handles: string[] }` |
| `get_chats` | — | `{ chats: Chat[] }` |
| `get_messages` | `chatGuid`, `limit`, `beforeMs?` | `{ messages: Message[] }` |
| `send_text` | `chatGuid`, `text`, `tempGuid`, `replyToGuid?` | `{ guid: string }` |
| `send_tapback` | `chatGuid`, `targetGuid`, `emoji`, `remove`, `associatedMessageType?` | `{}` |
| `edit_message` | `chatGuid`, `targetGuid`, `newText` | `{}` |
| `unsend_message` | `chatGuid`, `targetGuid` | `{}` |
| `mark_read` | `chatGuid` | `{}` |
| `set_typing` | `chatGuid`, `typing` | `{}` |
| `get_contacts` | — | `{ contacts: Contact[] }` |
| `create_chat` | `addresses: string[]`, `title?` | `{ chat: Chat }` |
| `send_attachment` | `chatGuid`, `tempGuid`, `mimeType`, `name`, `dataB64` | `{ guid: string }` |
| `download_attachment` | `attachmentGuid` | `{ dataB64: string }` |
| `reauth` | — | `{}` |

## Pushes

| type | fields |
|---|---|
| `new_message` | `{ message: Message }` |
| `message_status` | `{ chatGuid, guid, tempGuid?, status }` — status ∈ `sent\|delivered\|read\|failed` |
| `tapback` | `{ chatGuid, targetGuid, emoji, senderAddress, isFromMe, remove }` |
| `typing` | `{ chatGuid, typing }` |
| `chat_updated` | `{ chat: Chat }` |
| `auth_expired` | — (client shows the reconnect screen) |

## Types

**Chat**
```jsonc
{ "guid": "SmartTxt;-;+15551234567",   // or "SmartTxt;+;chat<id>" for groups
  "displayName": "Mom", "isGroup": false,
  "participants": [{ "address": "tel:+15551234567", "displayName": "Mom",
                     "avatarColor": "", "isMe": false }],
  "unread": false }
```

**Message** (BlueBubbles-shaped)
```jsonc
{ "guid": "abc", "chatGuid": "SmartTxt;-;+1555", "tempGuid": "tmp_…",
  "senderAddress": "tel:+1555", "isFromMe": false,
  "text": "hi", "timestampMs": 1736000000000,
  "dateDelivered": 0, "dateRead": 0,        // status derived: read>delivered>sent
  "replyToGuid": "", "service": "iMessage", // or "SMS" (green)
  "associatedMessageType": 0,               // non-zero ⇒ this msg IS a tapback…
  "associatedMessageGuid": "",              // …on this target guid
  "expressiveSendStyleId": "",              // message effect bundle id
  "reactions": [{ "emoji": "❤️", "senderAddress": "…", "isFromMe": false }],
  "attachments": [{ "guid": "att1", "mimeType": "image/jpeg", "name": "IMG.jpg", "kind": "image" }],
  "editedAtMs": 0, "isUnsent": false }
```

**Contact** `{ "name": "Mom", "address": "tel:+15551234567", "avatarColor": "" }`

## Tapbacks

The six classic tapbacks use BlueBubbles `associatedMessageType` codes; the
client sends both `emoji` and the code (`send_tapback`), and accepts tapbacks
either as a `tapback` push **or** as a `new_message` whose `associatedMessageType`
is set (folded onto `associatedMessageGuid`):

| emoji | add | remove |
|---|---|---|
| ❤️ love | 2000 | 3000 |
| 👍 like | 2001 | 3001 |
| 👎 dislike | 2002 | 3002 |
| 😂 laugh | 2003 | 3003 |
| ‼️ emphasize | 2004 | 3004 |
| ❓ question | 2005 | 3005 |

Arbitrary-emoji tapbacks (modern SmartTxt stickers) carry no classic code — pass
the raw emoji through.

## Timestamps

All times on the wire are **Unix milliseconds**. SmartTxt/rustpush internally
use Apple Cocoa-epoch nanoseconds (2001-01-01); convert at the relay
(`unixMs = cocoaNs/1e6 + 978307200000`). The client has the same helper
(`AppleEpoch`) for the native path.

## Notes for the production relay

- `register` is where validation data + IDS registration happen, server-side.
- Status: send `message_status` pushes (or set `dateDelivered`/`dateRead` on a
  re-pushed `new_message`) so outgoing bubbles advance sending→sent→delivered→read.
- Echo outgoing sends back as `new_message` (with the same `tempGuid`) if other
  devices need them; the client de-dups by `tempGuid`.
- On credential failure, push `auth_expired`; the client surfaces a reconnect
  prompt and calls `reauth`.
