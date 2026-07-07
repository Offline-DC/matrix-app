# Signal message store → ObjectBox

The Signal message store was migrated from a single encrypted-JSON blob
(`EncryptedSharedPreferences`) to an **ObjectBox** embedded database. Scope is
**messages/conversations only** — the libsignal protocol/key store
(`SignalProtocolPrefs`) and the linked-account store (`SignalAccountStore`) are
unchanged and still on `EncryptedSharedPreferences`.

The database is **plaintext / unencrypted** (deliberate). The free ObjectBox
core has no built-in at-rest encryption, so message bodies now live unencrypted
in the app's `objectbox/dpad-signal-obx/` files directory.

## What changed

- `signal/.../store/ObjectBoxEntities.kt` — 8 `@Entity` classes: `MessageEntity`,
  `RoomEntity`, `UserEntity`, `ContactEntity`, `GroupKeyEntity`,
  `ExpireTimerEntity`, `MutedRoomEntity`, `MetaEntity`.
- `signal/.../store/SignalObjectBox.kt` — process-wide `BoxStore` holder (ObjectBox
  allows only one open store per directory per process).
- `signal/.../store/ObjectBoxMapping.kt` — mappers between the ObjectBox entities
  and the shared `com.offline.dpadmessenger.data` domain models. The shared models
  are **not** annotated with ObjectBox (they stay backend-agnostic).
- `signal/.../SignalMessageStore.kt` — rewritten internals. **Public API is
  identical** (`loadSnapshot`/`saveSnapshot`/`isAutoDeleteEnabled`/
  `setAutoDeleteEnabled`/`clear`, the nested `Snapshot`/`Persisted*` types, and
  `RETENTION_MS`), so `SignalMessageRepository`, `SignalApp`, `SignalBackendFactory`
  and `SignalRepository` are unchanged.
- Build: `objectbox-gradle-plugin` on the root `buildscript` classpath;
  `kotlin-kapt` + `apply(plugin = "io.objectbox")` in `signal/build.gradle.kts`;
  ObjectBox keep-rules in `signal/consumer-rules.pro`. `armeabi-v7a` ABI filter
  unchanged (ObjectBox ships a v7a native lib, ~2.3 MB).

## Migration of existing users (one-time, automatic)

`SignalMessageStore.ensureMigrated()` runs once on first use (guarded by
`MetaEntity.legacyImported`):

1. Opens the old `dpad_signal_messages` / `snapshot_v1` `EncryptedSharedPreferences`.
2. Decodes the old `Snapshot` JSON and writes every row into ObjectBox in one
   transaction.
3. Carries over the auto-delete flag, then sets `legacyImported = true`.

Failure handling: a TRANSIENT failure (can't open the encrypted prefs, or the
ObjectBox write fails) does NOT set `legacyImported`, so the next launch retries
rather than silently losing history — this is a linked device, so Signal won't
backfill old messages. A genuinely empty store, a successful import, or an
unrecoverable corrupt/undecodable blob all mark migration done so it never loops.
A deliberate `clear()` (unlink) keeps `legacyImported = true`, so the old blob is
never re-imported. The old encrypted prefs file is left in place untouched (safe
to delete manually later if desired).

### Watching it happen (logs)

All migration lines use the `SignalStore` tag. Add `SignalStore:D` to the
launcher's `ROLLING_LOGCAT_FILTERSPEC` so they reach quack (as was done for
`SignalChatWS:D`). Grep target: `obx migration`.

    SignalStore  BoxStore opened (dpad-signal-obx)
    SignalStore  obx migration: legacy found — importing rooms=4 messages=137 users=51
    SignalStore  obx migration: imported OK in 42ms
    SignalStore  obx migration: no legacy store — fresh start
    SignalStore  obx migration: legacy read FAILED — will retry next launch     (W)
    SignalStore  obx migration: legacy store unrecoverable — giving up ...       (E)
    SignalStore  loadSnapshot: 4 rooms / 137 messages (objectbox)

A successful migration is silent in the UI (no spinner) and, for a 3-day store,
effectively instant — the user just sees their conversations. A FAILED migration
is user-visible (missing conversations), which is why it retries and logs W/E.

## Failure behavior (hardening)

The store is best-effort and never crashes the Signal backend:

- **Open failure degrades, not crashes.** `SignalObjectBox.get()` returns null
  (rather than throwing) if the database can't be opened — missing native lib
  (`UnsatisfiedLinkError`), locked directory, disk full. `SignalMessageStore`
  then treats a null store as "persistence unavailable": every method is a
  no-op / default, so the app runs with in-memory-only messages instead of
  crashing. This matters because the store is built inside
  `SignalRepository.create` on the main thread.
- **Saves never throw.** `saveSnapshot` (and `clear`) wrap the ObjectBox write in
  `runCatching`, so a write error can't escape into the repository's debounced
  auto-save coroutine and kill the collector (which would silently stop all
  persistence for the session). The migration import uses the same underlying
  `writeSnapshot` but checks its result so it can retry on failure.
- **Single meta row.** `MetaEntity` uses `@Id(assignable = true)` and is pinned
  to id 1, so concurrent first-access can't create duplicate meta rows (which
  would make `legacyImported` / the auto-delete flag read off the wrong row).

## Build & verify on-device (no Android SDK in the authoring env)

These edits were written without a compile (same constraint as SIGNAL_HANDOFF.md).
First build regenerates the ObjectBox code, so:

1. Build `:signal` (or the launcher). KAPT generates `MyObjectBox` and the
   per-entity `<Entity>_` classes, plus `signal/objectbox-models/default.json`.
   **Commit `objectbox-models/default.json`** — it tracks entity/property UIDs for
   future schema migrations.
2. Verify the **ObjectBox plugin ↔ AGP 8.13** compatibility. Pinned to
   `io.objectbox:objectbox-gradle-plugin:4.0.3` (predates ObjectBox requiring AGP
   9). If the plugin errors against AGP 8.13, bump to the newest 4.x that still
   supports AGP 8. Keep the runtime the same version as the plugin.
3. Confirm KAPT runs cleanly under Kotlin 2.1.10 (a "kapt falling back to 1.9"
   warning is expected/harmless).
4. Existing-user check: install over a build that has the old blob store and
   confirm conversations appear (migration ran). Fresh-install check: confirm a
   clean start with no rows loads as empty.

## Follow-ups (not done)

- **First load + migration run on the main thread.** The repository reads the
  store during construction (`isAutoDeleteEnabled()` at field init, then
  `loadFromStore()`), and that construction happens in `SignalApp`'s
  `remember { SignalRepository.create(context) }` — i.e. on the UI thread. Open
  failure no longer crashes (see hardening above), but a large first import is
  still synchronous work on the main thread (jank/ANR risk for a near-cap
  history). Fix would move first-load/migration onto `Dispatchers.IO`, which
  touches the repository's init/threading — deferred as a larger change.
- Incremental per-row upserts + a query-based retention delete
  (`timestampMs` is already indexed) instead of the current transactional
  full-replace in `saveSnapshot`. This would only touch `SignalMessageStore`
  (callers unchanged) and removes the rewrite-everything-every-1.5s pattern.
- Optionally delete the orphaned `dpad_signal_messages` prefs after a successful
  import.
