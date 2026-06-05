# dpad-messenger

A reusable Android messaging UI library + demo app, designed for **DPAD-driven
dumb-phone launchers** and easily portable to a Matrix client later (the
medium-term target is a custom Signal/Matrix solution forked off of Element
Android).

The library is standalone — there is no fork of Element Android in this repo.
Screen structure (room list, timeline, composer) is intentionally close to
Element's so swapping the mock repo for a real Matrix-backed `MessageRepository`
is a drop-in change.

---

## What you get

- **`com.offline.dpadmessenger:library`** — pure Jetpack Compose UI module.
  - `data/` — `Message`, `Room`, `User`, and a `MessageRepository` interface
    plus an `InMemoryMessageRepository` backed by a JSON seed file.
  - `focus/` — DPAD focus primitives (`dpadFocusHighlight`, `dpadRow`,
    `onDpadAction`) that draw a visible focus halo and intercept OK presses.
  - `ui/rooms/RoomListScreen` — Signal-style conversation list, DPAD navigable.
  - `ui/chat/ChatScreen` — timeline with bubbles + composer at the bottom.
  - `ui/components/DpadComposer` — text field + send button as two distinct
    DPAD focus stops.
  - `ui/MessengerNavigation` — drop-in `DpadMessengerApp(repository = …)`.
- **`com.offline.dpadmessenger.demo:demo`** — runnable app that loads
  `assets/mock_data.json`, drives a background "phantom traffic" coroutine
  that drops a fresh incoming message every ~12s, and renders the library UI.

---

## DPAD key map

| Where you are        | Up / Down                   | Left / Right                | OK / Center                 | Back            |
| -------------------- | --------------------------- | --------------------------- | --------------------------- | --------------- |
| Room list            | Move between conversations  | _(reserved)_                | Open the conversation       | Exit app        |
| Chat timeline        | Move between bubbles        | _(reserved for reactions)_  | Bubble action hook          | Back to rooms   |
| Composer text field  | Bubble (up) / IME           | Right → send button         | Open IME (or insert)        | Back to rooms   |
| Send button          | Bubble                      | Left → text field           | Submit message              | Back to rooms   |

Hardware key codes handled: `KEYCODE_DPAD_*`, `KEYCODE_ENTER`,
`KEYCODE_NUMPAD_ENTER`. That covers every DPAD launcher I've tested against
(Unihertz Jelly, Qin F22, BlackBerry Key2 OEM launchers).

---

## Running locally

### Prereqs
- Android Studio Hedgehog (2023.1) or newer **OR** plain JDK 17 + a system Gradle 8.5+.
- Android SDK 34 installed.

### First-time setup (Gradle wrapper)

This repo doesn't ship the wrapper jar. Generate one from a system Gradle:

```bash
cd dpad-messenger
gradle wrapper --gradle-version 8.5
```

If you don't have system Gradle, just open the folder in Android Studio — the
IDE will offer to create the wrapper automatically.

### Build + install the demo on a device or emulator

```bash
./gradlew :demo:installDebug
adb shell am start -n com.offline.dpadmessenger.demo/.MainActivity
```

### Run on an emulator with a DPAD

The standard Android emulator supports DPAD input out of the box once you
enable it in the AVD config:

1. Tools → Device Manager → edit your AVD → Show Advanced Settings.
2. Set **"Enable keyboard input"** = on, **"Enable D-Pad"** = on.
3. Use arrow keys to steer. `Enter` is OK. `Esc` is Back.

For a faithful dumb-phone-shaped screen, create an AVD with a custom hardware
profile around **320x480 mdpi** (similar to Qin F22) or **480x800 hdpi**
(Unihertz Jelly 2).

---

## Wiring your own data source

The whole UI talks to one interface:

```kotlin
interface MessageRepository {
    val currentUser: User
    fun userById(id: String): User
    fun observeRoomSummaries(): Flow<List<RoomSummary>>
    fun observeMessages(roomId: String): Flow<List<Message>>
    suspend fun getRoom(roomId: String): Room?
    suspend fun sendMessage(roomId: String, body: String): Message
    suspend fun markRoomRead(roomId: String)
    suspend fun simulateIncoming(roomId: String, senderId: String, body: String)
}
```

A future `MatrixMessageRepository` wraps the Matrix Rust SDK (or
matrix-android-sdk2 if you fork off Element) and emits the same shapes. Once
that exists, replace this in `MainActivity`:

```kotlin
val repo = InMemoryMessageRepository.fromAssets(applicationContext)
```

with this:

```kotlin
val repo = MatrixMessageRepository(matrixClient)
```

and the UI keeps working unchanged.

---

## Editing the mock data

`demo/src/main/assets/mock_data.json` is plain JSON, hand-editable:

- `currentUserId` — which user in `users` is "me".
- `users[]` — id, display name, hex avatar color.
- `rooms[]` — `isGroup: true` shows sender names above incoming bubbles.
- `messages[]` — id, room, sender, body, `timestampMs` (epoch ms), optional
  `status` (`SENT`, `DELIVERED`, `READ`, `FAILED`).

`isOutgoing` is auto-derived from `senderId == currentUserId` on load, so you
don't need to set it.

The demo app also generates simulated incoming messages every ~12 seconds via
`DemoViewModel.startSimulatedTraffic()` — handy for watching unread badges
tick up and bubbles animate in.

---

## Project layout

```
dpad-messenger/
├── settings.gradle.kts
├── build.gradle.kts
├── library/                                       # the reusable UI
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/strings.xml
│       └── kotlin/com/offline/dpadmessenger/
│           ├── data/
│           │   ├── Models.kt
│           │   ├── MessageRepository.kt
│           │   └── InMemoryMessageRepository.kt
│           ├── focus/
│           │   └── DpadFocusModifiers.kt
│           └── ui/
│               ├── MessengerNavigation.kt
│               ├── theme/ (Color.kt, Theme.kt, Type.kt)
│               ├── components/ (Avatar, RoomListItem, MessageBubble, DpadComposer)
│               ├── rooms/ (RoomListScreen + ViewModel)
│               ├── chat/  (ChatScreen + ViewModel)
│               └── util/  (TimeFormat.kt)
└── demo/                                          # runnable mock app
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/mock_data.json
        ├── res/...
        └── kotlin/com/offline/dpadmessenger/demo/
            ├── MainActivity.kt
            └── DemoViewModel.kt
```

---

## Design notes

**Why androidx.tv.foundation on a phone?**  `TvLazyColumn` does what
`LazyColumn` doesn't: it automatically scrolls focused items into view when
the DPAD moves past the visible edge. On a phone-shaped DPAD device the
behavior is exactly what you want — no special TV affordances kick in.

**Why no nested focusables?**  Every list row is one focus stop. A nested
"avatar focusable / name focusable / timestamp focusable" inside a row makes
DPAD-Down feel laggy on cheap devices (the user has to press Down N times to
escape the row). One row = one press.

**Why hand-built ViewModelFactory?**  Hilt and Koin both pull in a lot of
weight that a focused messaging library doesn't need. The factory in
`MessengerNavigation.kt` is ~10 lines and easy to replace with whatever DI
container your app already uses.

**Avatars are initials, not images.**  No Coil/Glide dep, no network calls,
and a circle with initials reads great on a 320x480 screen. The data model
already carries an `avatarUrl` field so a future image loader is purely
additive.

---

## Roadmap toward the Signal/Matrix solution

1. **Now (this repo):** UI library + mock repo, runnable on real devices.
2. **Next:** add a `MatrixMessageRepository` implementation against the
   Matrix Rust SDK (or matrix-android-sdk2). The interface won't change.
3. **Later:** lift Element Android's session/key-storage code as a separate
   `crypto` module so the UI library can remain Matrix-implementation-agnostic.
4. **Eventually:** publish `library` as a Maven artifact and let consumers ship
   their own dumb-phone launcher + Matrix client.
