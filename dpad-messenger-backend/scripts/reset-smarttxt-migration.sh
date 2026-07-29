#!/usr/bin/env bash
#
# Reset Smart Txt back to its pre-migration state on a rooted test device, so the
# OpenBubbles → Smart Txt migration runs again from scratch. For demo/QA only.
#
# Why more than one flag: OpenBubblesMigrator.available() shows the migration
# screen only when ALL of these hold —
#   • not already registered   (SmartTxtAccountStore.isRegistered() == false)
#   • ob_migration_done flag NOT set
#   • the native lib is present, the device is rooted, and OpenBubbles still has
#     its login files on disk           (the SOURCE — we do NOT touch those)
# so clearing just `ob_migration_done` does nothing if you're still signed in.
# This wipes the Smart Txt-owned state that makes the app look "already set up",
# WITHOUT nuking the rest of the launcher (home layout, other settings). It does
# NOT touch com.openbubbles.messaging, so there's still something to migrate FROM.
#
# HEADS UP — this script can no longer put OpenBubbles back. A migration run now
# UNINSTALLS com.openbubbles.messaging on its way out (every outcome, success or
# not), so on a device that has already migrated once there is no OpenBubbles data
# left to migrate FROM: resetting Smart Txt state just lands you on the normal
# sign-in screen. To re-test the migration end to end you must reinstall
# OpenBubbles and sign it back in first — the check below tells you which case
# you're in before anything is deleted.
#
# Usage:
#   ./reset-smarttxt-migration.sh                 # default package, surgical reset
#   PKG=com.offlineinc.dumbdownlauncher ./reset-smarttxt-migration.sh
#   ./reset-smarttxt-migration.sh --nuclear       # = pm clear (resets the WHOLE launcher app)
#
# Requires: adb on PATH, a rooted device (adb + su), USB debugging on.
set -euo pipefail

PKG="${PKG:-com.offlineinc.dumbdownlauncher}"
NUCLEAR="${1:-}"

# One su -c invocation (Magisk grant is per-call; batching avoids a re-prompt race).
run_su() { adb shell su -c "$1"; }

# Confirm the device is reachable and rooted before we start deleting.
adb get-state >/dev/null 2>&1 || { echo "error: no device via adb (USB debugging on?)"; exit 1; }
run_su 'id' | grep -q 'uid=0' || { echo "error: su did not return root — is this device rooted?"; exit 1; }

# The migration uninstalls OpenBubbles, so warn (don't fail) when it's already gone:
# the reset still works, it just won't have anything to migrate from.
if adb shell pm path com.openbubbles.messaging 2>/dev/null | grep -q '^package:'; then
  echo "==> OpenBubbles is installed — migration has a source to run against"
else
  echo "WARNING: com.openbubbles.messaging is NOT installed (a previous migration"
  echo "         uninstalled it). Resetting Smart Txt will land on manual sign-in,"
  echo "         not the migration screen. Reinstall + sign into OpenBubbles first"
  echo "         if you want to re-test the transfer."
fi

if [ "$NUCLEAR" = "--nuclear" ]; then
  echo "==> pm clear $PKG  (resets the ENTIRE launcher app, not just Smart Txt)"
  adb shell pm clear "$PKG"
  echo "Done. Relaunch the app — migration will be offered again."
  exit 0
fi

D="/data/data/$PKG"
echo "==> force-stopping $PKG (so it can't rewrite state on exit)"
run_su "am force-stop $PKG"

echo "==> deleting Smart Txt state under $D"
# The three categories that make available() short-circuit to "no migration":
run_su "
  set -e
  # 1) Registration/account (EncryptedSharedPreferences) — the isRegistered() gate.
  rm -f  $D/shared_prefs/dpad_smarttxt_account.xml
  # 2) The migration-done flag. Removing the whole file also drops the harmless
  #    'purged_demo_cache_v2' flag (the mock-chat cache just re-purges next launch).
  rm -f  $D/shared_prefs/smarttxt_flags.xml
  # 3) Staged native identity in filesDir, so the migrator re-imports from
  #    OpenBubbles instead of nativeInit resuming the already-migrated identity.
  rm -f  $D/files/dumb $D/files/os_config.plist $D/files/config.plist \
         $D/files/id_cache.plist $D/files/creds.json $D/files/thread_handles.json
  rm -rf $D/files/anisette
  echo '   removed account prefs, flags, and staged identity'
"

echo "Done. Relaunch $PKG — it re-runs the OpenBubbles migration if (and only if)"
echo "OpenBubbles is still installed and signed in; otherwise you get manual sign-in."
echo "Watch it with:  adb logcat -s ObMigrator:V smarttxt_ffi:V"
