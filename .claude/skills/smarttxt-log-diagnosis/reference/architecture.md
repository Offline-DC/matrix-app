# Smart Txt architecture (for diagnosis)

Just enough of the system to place a log line and know which layer to fix.

## Repos

- **`rustpush`** — the Rust iMessage engine: IDS registration, APNs, identity/key
  management, group logic, send/receive. This checkout is `Offline-DC/rustpush`,
  **forked from `OpenBubbles/rustpush`** (`git remote -v` shows `upstream` =
  `github.com/OpenBubbles/rustpush`). Pinned to a specific rev. This is your
  OpenBubbles source of truth for protocol behaviour.
- **`launcher`** — the flip-phone launcher app, package `com.offlineinc.dumbdownlauncher`.
  Hosts Smart Txt, owns device registration + diagnostics.
- **`matrix-app`** — the chat UI library and the Smart Txt backend (this repo).
- **`apple-private-apis`** — anisette / GSA provisioning dependency (validation data).

## Module map (matrix-app + launcher)

- **`launcher` (APP module, `com.offlineinc.dumbdownlauncher`)**
  - Hosts `SmartTxtActivity`. Is the `android:persistent` HOME app.
  - Diagnostics: `RollingLogcatTail` (full-device rolling logcat, opt-in, root),
    `DiagLogUploader` ("Submit logs" → presigned S3), `DiagPaths`,
    `DeviceRegistrar` (caches IMEI in SharedPreferences `device_registration` /
    key `last_imei` — this is the `deviceId` support looks bundles up by).
- **`matrix-app/dpad-messenger/library` (LIBRARY, `com.offline.dpadmessenger`)**
  - The shared Compose chat UI: `DpadMessengerApp` + `MessengerNavigation`,
    `RoomListScreen`, `ChatScreen`, `SettingsScreen`, `DpadButton`, etc.
  - Backend-agnostic (also used by the gmessages/signal/mock backends). A LIBRARY:
    it can't depend on the launcher app module.
- **`matrix-app/dpad-messenger-backend/smarttxt` (LIBRARY, `…backend.smarttxt`)**
  - Smart Txt backend + its UI gate. Key files:
    - `SmartTxtApp` (the REGISTERED-vs-setup gate), `SmartTxtSetupScreen`
      (sign-in state machine: INTRO→CREDENTIALS→TWO_FACTOR→FSA→REGISTERING→SUCCESS),
      `SmartTxtErrorScreen` (native can't-start), `SmartTxtReconnectScreen`.
    - `SmartTxtRepository` (lifecycle: restoreStatus/create/register/reregister,
      the boot-connect ladder, self-heal, `fixConnection`), `SmartTxtRenewalWorker`
      (periodic re-registration via WorkManager), `RustPushNative`/`RustPushBridge`
      (JNI glue), transports (`NativeRustPushTransport`, relay/mock),
      `OpenBubblesMigrator`, `SmartTxtAccountStore`, `SmartTxtContacts`.
    - Always-on Smart Txt logging: `SmartTxtLogRing` (rolling ~5 MB ring, Smart Txt
      tags only), `SmartTxtLogExporter` ("Export logs" upload), `ReportErrorButton`.
- **`matrix-app/dpad-messenger-backend/smarttxt-ffi` (Rust crate → `libsmarttxt_ffi.so`)**
  - JNI bridge over rustpush. Exports
    `Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_native*`.
  - `android_logger` tag **`SmartTxtRust`**, max level **Info**, module target in
    the message body. Installs a panic hook → `RUST PANIC` in logcat.

## Runtime model (why all logs are one process)

The launcher is `android:persistent`, so the APNs socket and the Rust runtime live
**in the launcher process itself** — no foreground service. Consequence for
diagnosis: every Smart Txt log line (Rust `SmartTxtRust` + Kotlin `IMsg*` /
`RustPush*` / `ObMigrator`) is emitted from **one UID/process**. That is why the
always-on `SmartTxtLogRing` can capture everything with a plain (non-root) `logcat`
filtered to those tags.

## The JNI surface (native functions you'll see in logs)

`nativeInit` (build identity from the "dumb"/os_config, NAC) → `nativeConnect`
(APNs) → receive loop. Sign-in: `nativeAuthenticate` → `nativeSubmit2fa` /
`nativeSubmitFsa` → `nativeRegister` (IDS auth + activation, NAC validation).
Maintenance: `nativeReregister` (force IDS re-registration, reusing identity),
`nativeReconcileHandles` (possible-vs-registered check → refresh if drifted),
`nativeSetSendHandle`. Messaging: `nativeSendText` / `nativeSendAttachment` /
`nativeSendTapback` / `nativeMarkRead`. Teardown: `nativeLogout`.

## Key flows and the log line that marks each

- **Cold start / connect:** `restoreStatus` → `create()` → `nativeInit`
  (`nativeInit ok`) → `nativeConnect` (`nativeConnect ok`) → receive loop. Retry
  ladder: `boot connect attempt N/…`; watcher: `network recovery callback registered`.
- **Login:** `SmartTxtSetupScreen.register()` → `nativeAuthenticate`
  (`login_email_pass → …`) → 2FA/FSA (`2FA verify → …`, `FSA verify → …`) →
  `nativeRegister: [1/3]…[3/3]` → `REGISTERED with iMessage ✅ handles=[…]` →
  `reconcile: …`.
- **Receive / threading:** receive loop reads a message, calls
  `push_relay_event(msg, my_handles)` where `my_handles` is fetched **live**
  (`client.identity.get_handles().await`) → `counterparts = participants − my` →
  `chat_guid` → `recv msg: … counterparts=[…] cv_name=… chat_guid=…`. Group id is
  Apple's stable gid; same members + different gid stay distinct rooms by design.
- **Send / route:** `decide_route[recipient]` validates IDS targets →
  `Route::IMessage` or SMS → `decide_route[…]: on_imessage=… valid=[…]`.
- **Renewal / recovery ladder:** `SmartTxtRenewalWorker` (`running SmartTxt renewal`)
  runs periodically; launch self-heal (`registration is stale — self-healing…`);
  `fixConnection` ladder behind Settings → "Re-register now"; network-callback
  reconnect; and in the receive loop, `identity closed (6005) — recovering…`.

## OpenBubbles relationship (for the "how does OB do it" question)

The OpenBubbles *app* is a separate Dart/Flutter client (github OpenBubblesApp) —
not in these repos. The shared, load-bearing part is **rustpush**, which you have.
So:
- Protocol / IDS / handle / group behaviour → read `rustpush/src/` directly; it is
  literally the upstream OpenBubbles engine.
- The one app-layer OB detail that matters repeatedly: rustpush exposes a
  `keys_updated` callback (fired on every re-registration with the fresh user/handle
  set). OB consumes it to stay current. Smart Txt historically ignored it and kept a
  login-time snapshot — the divergence behind the handle-drop bug class. Smart Txt
  now reads live instead (see `handles-registration-openbubbles.md`).
