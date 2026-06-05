# Running on the TCL Flip 2 (and how to test the UI first)

Two paths. **Do the emulator path first** — it's faster, you don't need to root
anything, and most UI bugs surface there before you touch the real device.

## Device reality check

The TCL Flip 2 (T408DL) is Android 11 AOSP, **240×320 px internal display,
1 GB RAM**, with a 5-way DPAD plus number keys. Two things to know up front:

1. **TCL blocks APK sideloads.** Even with USB debugging enabled, `adb install`
   is rejected by a system property. The documented workaround is rooting via
   a modified `boot.img` + Magisk. NeutronScott's wiki and the DietDroid guide
   are the references. This is not five-minute work.
2. **The screen is small.** The library now sizes message bubbles at 82 % of
   width and makes the reaction picker DPAD-scrollable, so it fits — but
   240×320 is tight. Test on the emulator's 240×320 profile to see what your
   user will actually see.

---

## Path 1 — Android emulator (recommended first)

### One-time setup

1. **Install Android Studio** (Hedgehog 2023.1.1 or newer). Open the project at
   `/Users/jacknugent/repos/dpad-messenger`.
2. Let it sync Gradle. It will prompt to create the Gradle wrapper jar if
   missing — accept.
3. Tools → SDK Manager → install **Android 11 (API 30)** platform + system
   image (`Google APIs Intel x86_64 Atom_64`).

### Create a Flip-2-shaped AVD

Tools → Device Manager → Create Device → **Phone → New Hardware Profile**:

| Field | Value |
| --- | --- |
| Device Name | `Flip2 Like` |
| Screen size | `2.4"` |
| Resolution | `240 × 320` |
| Density | `mdpi` (~167) |
| RAM | `1024 MB` |
| Has hardware buttons | **yes** |
| Has hardware keyboard | **no** |
| **Has D-Pad** | **yes** |

Next → select Android 11 (R) system image → Finish.

Then on the AVD, click the pencil → Show Advanced Settings → **Enable
keyboard input: ON**, **Enable D-Pad: ON**. Boot it.

### Build + install the demo

Either:

* Click the green ▶ next to the `demo` configuration in Android Studio, or
* In a terminal:
  ```bash
  cd /Users/jacknugent/repos/dpad-messenger
  ./gradlew :demo:installDebug
  adb shell am start -n com.offline.dpadmessenger.demo/.MainActivity
  ```

If the wrapper jar is missing, generate it:
```bash
gradle wrapper --gradle-version 8.5
```

### Driving the emulator with DPAD

| Computer key | Phone equivalent |
| --- | --- |
| Arrow keys | DPAD up/down/left/right |
| Enter / Return | DPAD center (OK / select) |
| Esc | Back |
| Ctrl-M (mac: Cmd-M) | Toggle the side panel for hardware buttons |

---

## Path 2 — Real TCL Flip 2

You need root first. The TL;DR is:

1. Enable debug mode: open the dialer, type `*#*#33284#*#*` (`*#*#DEBUG#*#*`).
   That enables developer options and USB debugging.
2. Confirm `adb devices` sees it. `adb install …` will still be rejected.
3. Follow **one** of the two community rooting guides:
   - NeutronScott's repo: `https://github.com/neutronscott/flip2/wiki`
   - DietDroid's writeup: `https://dietdroid.com/2025/06/18/tcl-flip-2-debugging-root-apk-installation/`
4. Once Magisk is installed, `adb install demo-debug.apk` works.

After install:

```bash
cd /Users/jacknugent/repos/dpad-messenger
./gradlew :demo:assembleDebug
adb install demo/build/outputs/apk/debug/demo-debug.apk
adb shell am start -n com.offline.dpadmessenger.demo/.MainActivity
```

The hardware DPAD on the Flip 2 is the central nav cluster around the OK key.
Number keys 1–9 do nothing in the app today (number-row shortcuts are Phase 5).

> **Caveat:** rooting voids warranty and can brick the device if something
> goes wrong. Do not do this on a phone you depend on for service.

---

## DPAD navigation checklist

Walk through this on the emulator with arrow keys + Enter. Every item below
should land focus visibly (blue border + tint).

### Login screen (only shows on first launch if `startAuthenticated = false`;
the demo defaults to `true`, so skip unless you toggle it)

- [ ] Down moves through Homeserver → Username → Password → Continue.
- [ ] Up reverses the order.
- [ ] On Continue, OK navigates to the room list.

### Room list

- [ ] On entry, focus lands on the first conversation row (blue border around
      the row).
- [ ] Up from the first row moves to the search field.
- [ ] Down from the search field returns to the first row.
- [ ] Type a query — list filters live, empty state shows "No chats match…"
      when nothing matches.
- [ ] Right/left from any row doesn't crash (currently no-op, focus stays).
- [ ] OK on a row opens that chat.
- [ ] Up from the search field reaches the Settings cog in the top-right.
      (Compose focus traversal will try; if it skips the cog, that's a known
      gap — currently you can also touch-tap it.)

### Chat screen

- [ ] Focus lands on a message bubble on entry.
- [ ] Up/Down moves between bubbles.
- [ ] Date dividers ("Today" / "Yesterday" / "Wed 14 May") are skipped — they
      are not focusable, which is correct.
- [ ] Down from the last bubble jumps to the text field. The IME opens.
- [ ] Right from the text field focuses the round Send button.
- [ ] Left from Send returns to the field.
- [ ] OK on the Send button submits the message (when non-empty).
- [ ] Enter / IME-Send on the keyboard also submits.
- [ ] Up to the very top of the timeline reveals a loading spinner — wait a
      tick and four older messages page in.
- [ ] Back returns to the room list.

### Message context sheet (DPAD-OK on a bubble)

- [ ] Sheet opens, focus lands on the first emoji chip.
- [ ] Right/Left moves through chips. Past the right edge the row scrolls
      (this is the fix for narrow screens).
- [ ] Down from the chips moves to Reply → Copy → (Edit) → (Delete) — Edit
      and Delete only appear for your own outgoing messages.
- [ ] OK on an emoji applies the reaction (chip with count appears under the
      bubble; tapping the same emoji again removes it).
- [ ] OK on Reply closes the sheet and shows a "Replying to X" banner above
      the composer.
- [ ] OK on Copy copies the message body to clipboard.
- [ ] OK on Edit closes the sheet and fills the composer with the body; send
      replaces the message in place and stamps "edited".
- [ ] OK on Delete redacts the message — bubble shows "Message deleted" in
      italic.
- [ ] Back dismisses the sheet without doing anything.

### Settings

- [ ] Cog in the room-list top bar opens Settings.
- [ ] Dark theme switch toggles the whole app.
- [ ] Log out returns to Login and clears the back stack — Back from Login
      should not return to chat.

---

## Demo feature walkthrough

The mock data is pre-seeded so every Phase 2 feature has something to look at.

1. **Reactions already on bubbles.** In the Alice Cooper room, the first
   message shows a 👍 reaction (from "me"). In Family, "I'll bring the wine"
   has 🍷 reactions from Alice and Carol.
2. **Reply quoting.** In Alice Cooper, "Thursday morning?" is a reply to
   "Coffee this week?" — bubble shows the parent snippet in an accented
   quote header. Same with "I'll send the agenda tonight" in Work Crew.
3. **Edited message.** In Dave Grohl, "Caught the last hour. Incredible." has
   an `edited · 14:01` timestamp.
4. **Deleted message.** In Family, "Wrong room, sorry." renders as italic
   "Message deleted".
5. **Pagination.** Alice Cooper and Family rooms have four and two
   pre-seeded historical messages respectively. Up-DPAD past the top of the
   visible timeline → spinner → older messages slide in. Do it again until
   the spinner stops appearing (means you've reached the start).
6. **Phantom traffic.** Every ~12 seconds, an incoming message lands in one
   of the rooms. Watch the unread badge in the room list increment. The
   sequence in `DemoViewModel.kt` is the canned set you can edit.
7. **Search.** Type `fam` in the room list — only "Family" remains.
8. **Theme toggle.** Settings → Dark theme. Whole app re-themes; bubble
   colors, focus halo, status text colors all switch.
9. **Send + reply + edit.** Open any chat. Type something, send it. OK on
   your sent bubble → Reply → type a reply. Edit your sent bubble → composer
   prefills, change text, send — your bubble updates in place with "edited".

---

## What to expect at 240×320

- Bubbles fill ~196 dp of the 240 dp width — comfortable on the inside, very
  little breathing room on the outside. This is correct.
- The reaction picker shows ~5 chips visible; DPAD-Right scrolls to the rest.
- The room list TopAppBar + search field + first row fit; you'll see ~2.5
  rows before needing to scroll.
- The chat screen shows ~3 short bubbles between the top bar and composer.
- Bottom sheet covers most of the screen. That's intentional — `skipPartiallyExpanded`
  is on so it doesn't open in a half state.
- Font legibility is OK at 167 ppi but not great. If you want larger, bump
  `DpadMessengerTypography` values in `library/src/main/kotlin/com/offline/dpadmessenger/ui/theme/Type.kt`.

## When something breaks

- `Gradle sync failed: SDK location not found` → set `ANDROID_HOME` or open
  in Android Studio so it writes `local.properties`.
- `Could not find androidx.tv:tv-foundation:1.0.0-alpha10` → SDK Manager →
  Android SDK → SDK Tools → Google APIs Atom 64 — make sure it's installed.
  Or bump to `1.0.0-alpha11` if alpha10 was unpublished.
- Emulator boots black on M-series Mac → AVD → Show Advanced → Graphics:
  Software, Boot option: Cold Boot.
- "Install rejected" on real device → you haven't rooted yet. Re-read Path 2.
