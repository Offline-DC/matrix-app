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
2. Decodes the old `Snapshot` JSON and writes every row into ObjectBox.
3. Carries over the auto-delete flag, then sets `legacyImported = true`.

It is idempotent across crashes (the import is one transaction; the flag is set
after). A deliberate `clear()` (unlink) keeps `legacyImported = true`, so the old
blob is never re-imported. The old encrypted prefs file is left in place
untouched (safe to delete manually later if desired).

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

- Incremental per-row upserts + a query-based retention delete
  (`timestampMs` is already indexed) instead of the current transactional
  full-replace in `saveSnapshot`. This would only touch `SignalMessageStore`
  (callers unchanged) and removes the rewrite-everything-every-1.5s pattern.
- Optionally delete the orphaned `dpad_signal_messages` prefs after a successful
  import.
