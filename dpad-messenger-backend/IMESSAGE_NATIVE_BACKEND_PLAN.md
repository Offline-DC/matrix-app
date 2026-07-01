# Native iMessage backend plan (`:imessage` in dpad-messenger-backend)

Companion to `OPENBUBBLES_CLEANUP_PLAN.md` and `STORAGE_PLAN.md`. Those two are
about managing the **upstream OpenBubbles Flutter app** as a black box from the
launcher (cleanup workers, notification coalescing, nightly restarts). This doc
is about **replacing** that black box: bringing iMessage in as a first-class
native backend in `dpad-messenger-backend`, with our own `dpad-messenger`
Compose UI — the same way Signal (`:signal`, direct mode) and Google Messages
(`:gmessages`) already work.

> ⚠ **OPEN DECISION — BLOCKER, unresolved as of 9 Jun 2026.** The full-native
> plan below has a hard dependency that is currently unmet: OpenBubbles'
> validation-data algorithm (absinthe `nac`) is **closed source**, so compiling
> rustpush ourselves leaves iMessage registration non-functional (panics at
> `generate_validation_data()`). See **§2.5** for the proof and **§2.6** for the
> three ways through (hybrid / full-native+relay / reimplement-or-extract).
> **Jack is looking into the validation-data question.** Everything past §2 is
> written as if a working validation-data source exists; it does not yet.

Decision locked in (per chat 9 Jun 2026):

- **Full native.** Copy OpenBubbles' rustpush + dumb-file/hardware-info
  handling into our own `:imessage` module. Goal end-state: the TCL Flips no
  longer ship the upstream OB Flutter app at all. **Caveat:** blocked on the
  validation-data decision above.
- **Auth is *mostly* solved out-of-band** (see §2). We provide a Mac hardware
  identity via a "dumb file"; users register on a real Apple device; no
  SIM/number activation in-app; no live validation relay. Distinct Mac
  identities are already handled and are **out of scope for this doc**. The one
  unsolved piece is the closed-source validation-data engine (§2.5).

## 0. Why native (the motivation is RAM, same as the cleanup plan)

The cleanup-plan diagnostics are the argument for going native rather than
staying hybrid:

- Upstream OB on a 916 MB-RAM TCL Flip 2 sits at **~106 MB PSS + ~78 MB swap
  PSS**, with **three foreground services** (`APNService`,
  `NotificationListener`, `GeolocatorLocationService`) before any message work
  begins. That's a Flutter engine + Dart runtime + rustpush all resident.
- The "first-open glitch" (§0.2 of the cleanup plan) is paged-out working set
  being faulted back from zram. A leaner native client carrying *only* rustpush
  + our Compose UI — no Flutter engine, no Geolocator, one foreground service
  for APNs — is the structural fix, not just the nightly-restart mitigation.
- We already own the chat UI. Hosting iMessage in `dpad-messenger` means one
  consistent messenger experience across Signal / Google Messages / iMessage
  instead of bouncing the user into a separate app.

The cost we are taking on in exchange: a **native Rust build pipeline** and
the job of tracking rustpush as it chases Apple. That is the real price of
"full native" and it is not small — see §8.

## 1. Architecture (direct mode, mirrors `:signal`)

```
┌─────────────────┐      rustpush (in-process, JNI)      ┌──────────────────┐
│  dpad-messenger │ ◄──────────────────────────────────► │ Apple APNs / IDS │
│      UI         │                                       │  courier + ids   │
└─────────────────┘                                       └──────────────────┘
          ▲
          │ MessageRepository
┌─────────┴───────────────────┐
│ IMessageMessageRepository    │  implements the UI contract
│ RustPushBridge (JNI/UniFFI)  │  thin Kotlin ↔ Rust surface
│ APNsForegroundService        │  keeps the push connection alive
│ IMessageAccountStore         │  encrypted creds + hardware info ("dumb file")
│ IMessageRenewalWorker        │  periodic offline re-registration (Mac mode)
└──────────────────────────────┘
```

No Matrix, no Conduit between UI and Apple — exactly the `:signal` direct-mode
shape from `docs/SIGNAL_BRIDGE.md`, with rustpush playing the role libsignal
plays for Signal. The difference from Signal/gmessages: the protocol library is
**Rust compiled to a `.so`**, not Kotlin. That makes the embedding mechanics a
copy of `docs/CONDUIT_EMBEDDING.md`, not the gmessages Go→Kotlin port.

New Gradle module: `dpad-messenger-backend/imessage`, namespace
`com.offline.dpadmessenger.backend.imessage`, consumed by `dumb-down-launcher`
through the existing composite build. It ships Compose UI (a setup/status
screen), so it applies `org.jetbrains.kotlin.plugin.compose` like `:gmessages`,
unlike the headless `:signal`/`:matrix` modules.

## 2. The auth model (settled) — and exactly how rustpush implements it

This is the part that differentiates iMessage from Signal/gmessages. The good
news from reading the source: **rustpush already contains the entire method**,
and it all hangs off one trait — `OSConfig` (`src/lib.rs`), implemented by
`MacOSConfig` (`src/macos.rs`). The "dumb file" *is* a serialized `MacOSConfig`.

### 2.1 The dumb file = a serialized `MacOSConfig`

```rust
pub struct MacOSConfig {
    pub inner: HardwareConfig,   // from open-absinthe::nac
    pub version: String,         // e.g. "13.6.4"
    pub protocol_version: u32,
    pub device_id: String,
    pub icloud_ua: String,
    pub aoskit_version: String,
    pub udid: Option<String>,
}
// HardwareConfig: platform_serial_number, mlb, rom, product_name, os_build_num, ...
```

The struct derives `Serialize`/`Deserialize`, so OB persists and reloads it
straight to/from a file — that's the existing mechanism we copy, not something
we invent. Our `IMessageAccountStore` just holds this blob (encrypted) and
hands a deserialized `MacOSConfig` to rustpush as the `&dyn OSConfig`.

### 2.2 The four library calls that do the auth (all driven off that config)

1. **Device activation** — `activate(os_config)` (`src/activation.rs`). Builds a
   CSR, signs it with **FairPlay certs/keys bundled in rustpush**
   (`certs/fairplay/…`), POSTs to
   `https://albert.apple.com/deviceservices/deviceActivation`, parses back the
   `DeviceCertificate` (the APNs push cert). Fully automatic from the config —
   this is what makes *deviceless* activation possible.
2. **Apple ID login** — `authenticate_apple(...)` (`src/auth.rs`), GrandSlam
   (GSA) + Anisette via `omnisette`/`icloud_auth`. Uses
   `OSConfig::get_gsa_hardware_headers()` → `X-Apple-I-MLB`, `X-Apple-I-ROM`,
   `X-Apple-I-SRL-NO` (the same hardware identity). **Requires the Apple ID
   password + a 2FA code at least once** (`TrustedPhoneNumber`, `VerifyBody`).
   Produces the account tokens.
3. **Validation data** — `MacOSConfig::generate_validation_data()`
   (`src/macos.rs`). Builds an `open_absinthe::nac::ValidationCtx` from the
   hardware config, **and makes live calls to Apple's IDS validation endpoints**
   (`id-validation-cert`, `id-initialize-validation` from the IDS bag),
   key-establishes, and signs. See §2.3 — this is the bit that's commonly
   mis-stated as "offline."
4. **IDS registration** — `register(...)` (`src/ids/user.rs`). Combines
   validation data + activation cert + Apple ID tokens into the IDS identity
   that actually authorizes iMessage. **Renewal = re-run steps 3–4 on a
   schedule** (`IMessageRenewalWorker`).

### 2.3 "No Mac needed" ≠ "offline" (correction)

OpenBubbles' renewal docs say a Mac identity can "register and renew
indefinitely with no connection to the Mac." The source backs that up — the
absinthe/nac crypto runs purely from the serialized `HardwareConfig`, so the
physical Mac is genuinely out of the loop. **But `generate_validation_data()`
still hits Apple's network every renewal.** So the accurate framing is: *no Mac,
no ValidationRelay, no SIM — but Apple connectivity is required on each renew.*
The iPhone path, by contrast, needs the phone + a live relay online at all times
(why we chose Mac identities).

### 2.4 What we still have to supply

- **The dumb file** (serialized `MacOSConfig`) — copied from OB's hardware-info
  store. Distinct Mac identities across the fleet are handled elsewhere; out of
  scope here.
- **Apple ID credentials + 2FA**, once, for step 2. Whether that happens in-app
  (rustpush can drive `authenticate_apple` itself) or is captured from a
  real-device registration and seeded as tokens, the resulting account state
  must land in `IMessageAccountStore` alongside the dumb file. Registration is
  not just number activation.
- **No SIM / number registration in-app.** Out of scope by decision.

Net: the in-app auth flow reduces to "load the `MacOSConfig` + account tokens,
run activate → authenticate → generate_validation_data → register, then
re-register on schedule." No QR link flow (Signal), no cookie/GAIA arms race
(gmessages). Ongoing obligations: the renewal schedule (`IMessageRenewalWorker`,
§4), Apple connectivity at renew time, and surviving Apple-initiated
invalidation (§8).

### 2.5 ⚠ BLOCKER (resolved 9 Jun 2026): the validation-data algorithm is closed source

Investigated and confirmed — this changes the project. The `open-absinthe`
submodule rustpush references (`OpenBubbles/OpenAbsinthe-Stub`, pinned
`1f8dc73`) is a **deliberate mock**. Its README states verbatim: *"This
repository is closed source, so a mock dependency is present to allow for
development and testing. This is a placeholder and does not contain any actual
functionality."* The code confirms it:

```rust
impl ValidationCtx {
    pub fn new(...) -> Result<ValidationCtx, AbsintheError> { todo!() }
    pub fn key_establishment(&mut self, ...) -> Result<(), AbsintheError> { todo!() }
    pub fn sign(&self) -> Result<Vec<u8>, AbsintheError> { todo!() }
}
impl HardwareConfig {
    pub fn from_validation_data(data: &[u8]) -> Result<HardwareConfig, AbsintheError> {
        panic!("Not supported with binary!");
    }
}
```

**Consequence.** rustpush is open, the dumb-file/`MacOSConfig` format is open
(the `HardwareConfig` fields are visible in the stub), but the engine that turns
those hardware identifiers into Apple-accepted validation data — the absinthe
`nac` — is **not published**. Build rustpush yourself against the public
submodule and `generate_validation_data()` panics at runtime; IDS registration
never completes; no iMessage. This is OpenBubbles' withheld secret sauce (the
thing their paid "hosted" tier is built on). The dumb file alone is necessary
but inert without it.

**This invalidates the naive "copy rustpush in" version of full-native.** The
three ways through it are in §2.6.

### 2.6 Options for getting valid validation data (pick before Phase B)

1. **Hybrid after all (lowest risk).** OB's *shipped app* contains the real
   absinthe compiled into its Rust `.so` — the working closed component already
   lives on the phone. Keep OB headless to own validation/registration, build
   only the `dpad-messenger` UI over its store/IPC. Sidesteps the blocker
   entirely; costs the RAM win that motivated full-native (§0).
2. **Full-native with a validation *relay/server* (medium).** Skip
   open-absinthe; use rustpush's `RelayConfig` / `remote-anisette` path and get
   validation data from a genuine Apple device or a Mac-backed validation
   service we run. This reintroduces an online dependency — exactly the
   "relay must stay reachable" property we chose Mac identities to avoid — but
   it can be *one* shared Mac serving the fleet rather than per-phone hardware.
3. **Reimplement / obtain absinthe (high).** Port the `nac` algorithm ourselves
   (the JJTech pypush "Absinthe" lineage — reverse-engineering Apple's
   `IMDAppleServices` nac routines), or extract OB's compiled absinthe from their
   APK. The first is weeks-to-months of ban-prone RE and ongoing maintenance;
   the second is legally/ethically dubious and fragile across OB updates.
   Neither is "open" in any clean sense.

Recommendation pending your call: option 1 (hybrid) recovers most of the goal
for a fraction of the risk; option 2 is the only *full-native* path that doesn't
require solving Apple's nac ourselves, at the cost of a shared online relay.

### 2.7 Other source-level flag

- **Bundled FairPlay keys** (`certs/fairplay/…`, ten of them, chosen at random
  per activation) are what make deviceless *activation* (the Albert step) work.
  They're a single point of failure / legal exposure if Apple rotates or
  challenges them — note it, don't depend on it being permanent. (Separate from
  the absinthe blocker above: activation ≠ validation data.)

## 3. Embedding rustpush (the hard, new-to-us part)

Follow `docs/CONDUIT_EMBEDDING.md` step-for-step; rustpush substitutes for
Conduit. Upstream OB consumes rustpush from Flutter via `flutter_rust_bridge`,
so the public Rust API exists and is exercised — but it is **not** a clean C
ABI we can call from JNI directly. Two routes:

1. **UniFFI wrapper crate (recommended).** A thin crate that depends on
   `rustpush` as a library and exposes a `.udl`-defined surface; UniFFI
   generates the Kotlin bindings + the JNI glue. Less hand-written `extern "C"`
   than the Conduit recipe, and it survives rustpush API churn better because
   the wrapper is the only thing that has to track it.
2. **Hand-rolled `extern "C"` + `RustPushBridge.kt`** with
   `external fun nativeXxx(...)`, exactly like `ConduitBinaryLoader`'s
   `nativeStartServer`/`nativeStopServer`. More control, more boilerplate, more
   breakage on every rustpush bump.

Build steps (from the Conduit doc, retargeted):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
# NDK clang as linker in ~/.cargo/config.toml (see CONDUIT_EMBEDDING.md §1)
git clone https://github.com/OpenBubbles/rustpush     # pin a commit
# write imessage-ffi/ wrapper crate depending on rustpush
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
```

Drop outputs into the module the same way Conduit does:

```
dpad-messenger-backend/imessage/src/main/jniLibs/
├── arm64-v8a/      libimessage_ffi.so
└── armeabi-v7a/    libimessage_ffi.so
```

`RustPushBridge` calls `System.loadLibrary("imessage_ffi")` and owns a tokio
runtime started/stopped from `APNsForegroundService.onCreate/onDestroy` — same
lifecycle pattern as `EmbeddedHomeserverService`.

**Pin a rustpush commit and vendor it.** Like the Conduit note ("you'll likely
need to vendor + patch"), we do not float on rustpush `main`; we pin a known-good
commit, build against it, and bump deliberately.

## 4. Module skeleton (mirror `:signal` / `:gmessages` file-for-file)

| File | Role | Analogue |
|---|---|---|
| `IMessageBackendFactory.kt` | `create(ctx, BackendConfig.IMessageNative) → MessageRepository?`; null until seeded | `SignalBackendFactory` |
| `IMessageConfig.kt` | host-set `messengerActivityClassName` for notif tap target | `GoogleMessagesConfig` / `SignalConfig` |
| `IMessageAccountStore.kt` | EncryptedSharedPreferences: hardware info + IDS keys + auth token + renewal timestamp | `SignalAccountStore` |
| `RustPushBridge.kt` | `external fun` JNI surface (or UniFFI-generated) over rustpush | `ConduitBinaryLoader` |
| `IMessageMessageRepository.kt` | implements `MessageRepository`; maps rustpush events ↔ UI model | `SignalMessageRepository` |
| `APNsForegroundService.kt` | foreground service hosting the rustpush tokio runtime + APNs socket | `EmbeddedHomeserverService` / `SignalBridgeService` |
| `IMessageRenewalWorker.kt` | periodic re-registration from hardware info (re-runs `generate_validation_data` + `register`; needs Apple connectivity, not the Mac) | (new — no analogue) |
| `IMessageNotifier.kt` | posts incoming-message notifications, opens host Activity | `GoogleMessagesNotifier` |
| `ui/IMessageApp.kt` | `IMessageApp()` composable: status/setup gate → `DpadMessengerApp` | `GoogleMessagesApp` |
| `ui/IMessageSetupScreen.kt` | "import dumb file + account state" / iMessage status screen | `SignalLinkScreen` |

Add `BackendConfig.IMessageNative` to `:core`'s sealed `BackendConfig`, and an
`OpenBubbles/IMessage` `Target` enum value if we reuse `StorageCleanupOps`.

## 5. Mapping rustpush ↔ `MessageRepository`

The UI contract (`MessageRepository`) is already iMessage-shaped — it has
reactions, edits, deletes, replies, read receipts, pagination. Mapping:

- **`observeRoomSummaries` / `observeMessages`** ← rustpush chat + message
  streams. iMessage "chats" (1:1 + group) → `Room`/`RoomSummary`.
- **`sendMessage(replyToId)`** → rustpush send; iMessage inline replies map to
  `replyToId`.
- **`toggleReaction(emoji)`** ↔ iMessage **tapbacks**. Note: tapbacks are a
  fixed set (love/like/dislike/laugh/emphasize/question) plus, on modern
  iMessage, arbitrary emoji. Map the six classic tapbacks to fixed emoji and
  pass arbitrary ones through.
- **`editMessage` / `deleteMessage`** ↔ iMessage **edit** / **unsend**
  (15-min / 2-min windows; surface failures as no-ops per the interface
  contract).
- **`markRoomRead`** → iMessage read receipts (respect the per-chat
  send-read-receipts setting from the seeded account state).
- **Attachments** → rustpush handles MMCS upload/download; render via the
  existing UI attachment path (mirror `SignalAttachments`).
- **Typing indicators / delivered vs read** → optional polish, Phase E.

iMessage-specific things with no UI slot yet (defer / extend the model later):
Contact Posters & profile images (the avatar cache the cleanup plan already
prunes), message effects, FaceTime, GamePigeon/OpenPigeon extensions.

## 6. Phasing (mirrors the gmessages port plan's A/B/C cadence)

- **Phase A — plumbing.** Create `:imessage`, wire into launcher composite
  build, `MessengerActivity` hosts `IMessageApp()`, mock `MessageRepository` so
  the chat screens render with demo data. No native code yet. (Same as
  gmessages Phase A.)
- **Phase B — rustpush `.so` + APNs connect.** Write the `imessage-ffi`
  wrapper crate, cross-compile, drop into `jniLibs`, `RustPushBridge` loads it,
  `APNsForegroundService` brings up the APNs connection. Success = a live
  APNs socket, no messaging yet.
- **Phase C — seed + register.** Import the dumb file + account state into
  `IMessageAccountStore`; drive rustpush registration (`activate` →
  `authenticate_apple` → `generate_validation_data` → `register`);
  `IMessageRenewalWorker` schedules periodic re-registration (Mac mode; needs
  Apple connectivity, not the Mac). Success = "registered with iMessage"
  status, device shows on the Apple ID's device list.
- **Phase D — receive + send.** Wire rustpush inbound events → repo streams →
  UI; `sendMessage` outbound; `IMessageNotifier` for incoming. Success =
  two-way text with a real iMessage contact in our UI.
- **Phase E — parity polish.** Tapbacks, edits/unsend, attachments, read
  receipts, group chats, typing.
- **Phase F — retire upstream OB.** Migrate history if wanted (read OB's
  ObjectBox `data.mdb` once, import into our store — or just resync from the
  relay and start clean, per the §1.1 wipe option in the cleanup plan). Then
  remove the OB Flutter app and the launcher's OB cleanup workers
  (`OpenBubblesAttachmentCleanupWorker`, the nightly restart, notification
  coalescing) — all of that exists only to babysit the app we're deleting.

## 7. Launcher integration

Same pattern the launcher already uses for Google Messages:

- `MessengerActivity` (or a sibling `IMessageMessengerActivity`, like
  `SignalMessengerActivity`) is a thin shell hosting `IMessageApp()`.
- `DumbDownApp.onCreate` sets
  `IMessageConfig.messengerActivityClassName =
  "com.offlineinc.dumbdownlauncher.messenger.MessengerActivity"` so
  notifications have a tap target without a compile dependency on `:app`.
- Foreground-service notification + battery: the APNs service replaces OB's
  `APNService`; we lose Geolocator and the Flutter engine entirely, which is
  the whole point.

## 8. Risks & open questions

- **rustpush API churn.** Same warning as `matrix-rust-sdk` in the backend
  README, sharper: rustpush is actively reverse-engineering a hostile target.
  Pin a commit, isolate all churn in the `imessage-ffi` wrapper crate, budget
  for periodic forced bumps when Apple changes something.
- **Native build pipeline is new.** We've cross-compiled nothing for Android
  yet (Conduit is still a stub). The first arm64 + armv7 rustpush build is the
  riskiest single task; do it in Phase B before committing to the rest.
- **`flutter_rust_bridge` vs UniFFI.** Upstream's bindings are FRB-shaped and
  Flutter-coupled. Confirm rustpush's public API is callable from a plain Rust
  wrapper crate without dragging in FRB/Flutter; if it isn't cleanly separable,
  the wrapper crate gets bigger. **Open question — verify in Phase B.**
- **Validation renewal correctness.** Mac-mode renewal (re-running
  `generate_validation_data` + `register`, which needs Apple connectivity) is
  the linchpin.
  If `IMessageRenewalWorker` misses its window (Doze, killed worker), the device
  silently de-registers. Needs the same robustness as the existing WorkManager
  chains, plus a visible "iMessage status" surface so a stuck renewal is
  diagnosable in the field.
- **Apple invalidation.** Mac mode is durable but not immune; Apple can force
  re-registration or invalidate an identity. We need graceful "re-register from
  stored hardware info" recovery, and the fleet-level identity rotation (handled
  elsewhere) as the backstop.
- **APNs foreground service battery** on the TCL Flips. Should be *lighter*
  than OB's three services, but measure — a persistent socket on a flip phone
  is exactly the kind of thing the cleanup plan's diagnostics caught.
- **History migration.** OB stores messages in ObjectBox (`data.mdb`). Reading
  it from our process has the same "don't touch ObjectBox bytes from outside the
  runtime" hazard the cleanup plan flagged. Simplest safe path: resync from the
  relay on first launch and don't migrate.

## TL;DR

The UI half is a solved, repeatable pattern (`MessageRepository` +
`dpad-messenger`). The auth half is **not** as solved as we assumed: the dumb
file + `MacOSConfig` are real and open, but the validation-data engine that
consumes them (absinthe `nac`) is **closed source** (§2.5) — so the naive
"compile rustpush ourselves" full-native path is blocked at registration. Pick a
§2.6 option first:

- **Hybrid** — keep OB headless (it has the real absinthe baked in), our UI on
  top. Lowest risk; gives up the RAM win.
- **Full-native + relay** — only full-native path that avoids reimplementing
  Apple's nac; costs a shared online validation relay/Mac.
- **Reimplement/extract absinthe** — high effort, ban-prone, or legally dubious.

Once a validation-data source is chosen, the *rest* of full-native is tractable:
rustpush is FRB-free and embeds as a `.so` via the Conduit recipe, and the UI is
wiring we've done twice. But Phase B's real go/no-go is now **"do we have a
working validation-data path,"** not the cross-compile.
