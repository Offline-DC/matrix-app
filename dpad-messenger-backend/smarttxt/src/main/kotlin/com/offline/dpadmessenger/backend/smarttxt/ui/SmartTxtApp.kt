package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.backend.smarttxt.OpenBubblesMigrator
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtAccountStore
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtLogExporter
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtLogRing
import com.offline.dpadmessenger.backend.core.store.MigrationStatus
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtRepository
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtStatus
import com.offline.dpadmessenger.data.MessageRepository
import com.offline.dpadmessenger.data.ReadReceiptSettings
import com.offline.dpadmessenger.data.RetentionSettings
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.util.TimeFormatPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level entry for the in-app SmartTxt experience. Mirror of
 * `GoogleMessagesApp`:
 *  - REGISTERED → the reused chat UI ([DpadMessengerApp]) backed by the live
 *    SmartTxt repository, with settings (auto-delete, 12/24h) and logout wired.
 *  - anything else → [MigrationScreen] (transfer from OpenBubbles) or, failing the
 *    login reuse, [SmartTxtSetupScreen] (manual sign-in, validated via the NAC server).
 *
 * The chat UI and the setup UI share the same Activity/host — just a state swap
 * on [SmartTxtRepository.status].
 */
@Composable
fun SmartTxtApp(
    modifier: Modifier = Modifier,
    initialRoomId: String? = null,
    initialRoomKey: Any? = null,
    /**
     * Set by a host that owns what happens after first-run setup — today the
     * launcher's onboarding, which shows its own "ur all set" screen.
     *
     * When non-null, pressing Continue on [HandlePickerScreen] calls this
     * INSTEAD of opening the chat list. The handle choice is still persisted by
     * the picker itself, so `handlesConfigured()` is true either way; only the
     * in-memory gate is left closed, which keeps the picker on screen until the
     * host tears the Activity down. Without that, the chat list composes and
     * draws for a frame or two before the host can react — the user sees an
     * empty inbox flash on their way to the done screen.
     */
    onSetupComplete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // Restore a previous sign-in on a fresh process BEFORE reading status, so a
    // returning user lands on their chats instead of the setup screen.
    remember { SmartTxtRepository.restoreStatus(context) }
    // Warm the rolling log ring on app entry — BEFORE the sign-in gate below — so
    // it's already capturing on the login/setup screen. That's when "Report error"
    // is most likely tapped (a failed sign-in); a cold ring would otherwise hand
    // back a stale window. Idempotent, so safe on every entry/recomposition.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SmartTxtLogRing.ensureStarted(context.applicationContext) }
    }
    // Retire any OpenBubbles still on the device, on EVERY launch — not just the one launch
    // that runs the transfer. The transfer's own uninstall only fires when
    // OpenBubblesMigrator.available() is true, so a phone that already migrated (or that was
    // rolled back from beta to main, which reinstalls OpenBubbles, and then came back to
    // Smart Txt) kept a second rustpush app live on the same push identity. Kept as its own
    // LaunchedEffect so the sweep's root probe can never delay the log ring above; it hops to
    // IO itself and stands down whenever a transfer still wants OpenBubbles' files.
    LaunchedEffect(Unit) {
        OpenBubblesMigrator.sweepOnLaunch(context)
    }
    val status by SmartTxtRepository.status.collectAsState()
    // Post-login handle picker: shown once after registration, before the chats.
    var handlesConfigured by remember { mutableStateOf(SmartTxtAccountStore(context).handlesConfigured()) }
    // ...and re-read whenever the status changes, because the value above is captured
    // at FIRST composition — which, on an OpenBubbles migration, is while the transfer
    // is still running (this composable is what renders MigrationScreen). It is false
    // at that instant. The migration then answers the picker on the user's behalf, by
    // carrying OpenBubbles' "Start Chats Using" address into the account store; without
    // this re-read the stale `false` survives and the gate shows them the picker anyway
    // — the "u r signed in!!!" screen a migrated user should never reach.
    LaunchedEffect(status) {
        if (status == SmartTxtStatus.REGISTERED && !handlesConfigured) {
            handlesConfigured = withContext(Dispatchers.IO) {
                SmartTxtAccountStore(context).handlesConfigured()
            }
        }
    }

    when {
        status == SmartTxtStatus.REGISTERED && !handlesConfigured ->
            HandlePickerScreen(
                modifier = modifier,
                onContinue = {
                    // Hand off to the host when it asked for it; otherwise fall
                    // through to the chats as normal. See [onSetupComplete].
                    if (onSetupComplete != null) onSetupComplete() else handlesConfigured = true
                },
            )

        status == SmartTxtStatus.REGISTERED -> {
            // Build the repository OFF the main thread: create() triggers the heavy
            // one-time nativeInit (file I/O + keystore + tokio runtime), which would
            // block the UI thread and ANR if run during composition. On a native
            // failure create() returns null and sets nativeError — we show that
            // error, never the mock demo transport.
            val nativeError by SmartTxtRepository.nativeError.collectAsState()
            var repository by remember { mutableStateOf<MessageRepository?>(null) }
            var buildAttempt by remember { mutableStateOf(0) }
            LaunchedEffect(buildAttempt) {
                repository = withContext(Dispatchers.IO) { SmartTxtRepository.create(context) }
            }
            val repo = repository
            val err = nativeError
            when {
                err != null -> SmartTxtErrorScreen(
                    message = err,
                    onRetry = {
                        SmartTxtRepository.clearNativeError()
                        repository = null
                        buildAttempt++
                    },
                    modifier = modifier,
                )
                // The one-time move off the encrypted-JSON blob happens inside
                // the repository's cache load, which is exactly what this branch is
                // waiting on. Say so rather than showing a duck that looks stuck.
                repo == null -> DuckLoadingIndicator(modifier = modifier, label = migrationLabel())
                else -> SmartTxtChat(
                    repository = repo,
                    modifier = modifier,
                    initialRoomId = initialRoomId,
                    initialRoomKey = initialRoomKey,
                )
            }
        }

        else -> {
            // Not signed in yet. Before the normal setup screen, check for a
            // logged-in OpenBubbles to transfer from (rooted device). If present,
            // run the one-time "Transferring to new Smart Txt" migration — on
            // success it flips status to REGISTERED (→ chats above); on failure or
            // when there's nothing to migrate, fall through to setup.
            // If Apple TERMINALLY invalidated the registration (IDS 6005), say so on
            // the sign-in screen and skip the OpenBubbles transfer entirely: importing
            // the same identity for the same Apple ID just earns another 6005, so the
            // migration path would loop and silently mask the real problem.
            val terminalFailure = remember { SmartTxtAccountStore(context).terminalFailure() }

            var migrate by remember { mutableStateOf<Boolean?>(null) } // null=checking
            LaunchedEffect(Unit) {
                migrate =
                    if (terminalFailure != null) false
                    else withContext(Dispatchers.IO) { OpenBubblesMigrator.available(context) }
            }
            when (migrate) {
                null -> DuckLoadingIndicator(modifier = modifier, label = migrationLabel())
                true -> MigrationScreen(modifier = modifier, onFallbackToSetup = { migrate = false })
                else -> SmartTxtSetupScreen(modifier = modifier, notice = terminalFailure)
            }
        }
    }
}

/** The signed-in chat experience. Split out from [SmartTxtApp] so the repository
 *  (and its heavy native init) can be built off the main thread first. */
@Composable
private fun SmartTxtChat(
    repository: MessageRepository,
    modifier: Modifier,
    initialRoomId: String?,
    initialRoomKey: Any?,
) {
    val context = LocalContext.current
    val retention = repository as? RetentionSettings
    val readReceipts = repository as? ReadReceiptSettings
    val scope = rememberCoroutineScope()

    // If the relay link died and couldn't refresh, show a reconnect prompt instead
    // of a chat that silently can't send (like gmessages).
    val authExpired = SmartTxtRepository.authExpiredFlow()?.collectAsState()?.value ?: false
    if (authExpired) {
        SmartTxtReconnectScreen(
            onReconnect = { scope.launch { SmartTxtRepository.session().reauth() } },
            modifier = modifier,
        )
        return
    }

    val autoDeleteDays = retention?.autoDeleteDays?.collectAsState()?.value
        ?: com.offline.dpadmessenger.data.RetentionSettings.DEFAULT_RETENTION_DAYS
    // Read receipts: off by default. When off, reading still clears the notification
    // on the user's own Apple devices; only the sender-facing "Read" is suppressed.
    val sendReadReceipts = readReceipts?.sendReadReceipts?.collectAsState()?.value ?: false

    // "Send from" default handle: the registered numbers/emails, the saved default,
    // and a setter that persists + pushes it to the native send path.
    val accountStore = remember { SmartTxtAccountStore(context) }
    val sendHandles = remember { accountStore.loadAccount()?.handles.orEmpty() }
    var defaultHandle by remember {
        mutableStateOf(
            accountStore.defaultHandle()
                ?: sendHandles.firstOrNull { it.startsWith("tel:") }
                ?: sendHandles.firstOrNull()
                ?: ""
        )
    }
    // Re-apply the saved default to the native runtime on (re)entry (off-main; the
    // native call can block on a lock). AppState.send_handle resets on process death.
    LaunchedEffect(defaultHandle) {
        if (defaultHandle.isNotBlank()) {
            withContext(Dispatchers.IO) { RustPushNative.runCatchingNativeSetSendHandle(defaultHandle) }
        }
    }

    val settingsPrefs = remember {
        context.applicationContext.getSharedPreferences("smarttxt_settings", android.content.Context.MODE_PRIVATE)
    }
    var use24Hour by remember {
        val saved = settingsPrefs.getBoolean("use24HourTime", false)
        TimeFormatPreference.use24Hour = saved
        mutableStateOf(saved)
    }

    // Hidden diagnostics unlock: toggle 24-hour time DEBUG_TOGGLE_COUNT times in a
    // row. The host is deliberately an inert display preference rather than
    // "Re-register now" - a missed gesture there falls through to the normal click,
    // which contacts Apple and mints a real re-registration, the one thing this whole
    // area exists to avoid doing casually. The count is EVEN, so the preference lands
    // back exactly where the user found it and the gesture leaves no trace.
    var debugToggleCount by remember { mutableStateOf(0) }
    var debugLastToggleMs by remember { mutableStateOf(0L) }
    var showDebug by remember { mutableStateOf(false) }

    DpadMessengerApp(
        repository = repository,
        modifier = modifier,
        onLogout = { SmartTxtRepository.shutdown(context, wipe = true) },
        // "Re-link phone" and "Days since last link" are intentionally left off for
        // the demo: omitting onRelink/onFreshRelink/linkAgeDays (all default to
        // null) hides both Settings rows. There's no real ~2-week IDS session here.
        autoDeleteDays = autoDeleteDays,
        onAutoDeleteDaysChange = retention?.let { r -> { days: Int -> r.setAutoDeleteDays(days) } },
        sendReadReceipts = sendReadReceipts,
        onSendReadReceiptsChange = readReceipts?.let { r -> { enabled: Boolean -> r.setSendReadReceipts(enabled) } },
        use24HourTime = use24Hour,
        onUse24HourTimeChange = { enabled ->
            use24Hour = enabled
            TimeFormatPreference.use24Hour = enabled
            settingsPrefs.edit().putBoolean("use24HourTime", enabled).apply()

            // A gap longer than the window resets the run, so ordinary use of the
            // toggle can never trip this.
            val now = android.os.SystemClock.elapsedRealtime()
            debugToggleCount =
                if (now - debugLastToggleMs <= DEBUG_TOGGLE_WINDOW_MS) debugToggleCount + 1 else 1
            debugLastToggleMs = now
            if (debugToggleCount >= DEBUG_TOGGLE_COUNT) {
                debugToggleCount = 0
                showDebug = true
                android.util.Log.w(
                    "SmartTxtDebug",
                    "DEBUG_MENU unlocked (24-hour toggle x" + DEBUG_TOGGLE_COUNT + ")",
                )
            }
        },
        sendHandles = sendHandles,
        defaultSendHandle = defaultHandle,
        onDefaultSendHandleChange = if (sendHandles.size > 1) { h: String ->
            defaultHandle = h
            scope.launch(Dispatchers.IO) {
                accountStore.saveHandleSelection(enabled = sendHandles, default = h)
                RustPushNative.runCatchingNativeSetSendHandle(h)
            }
            Unit
        } else null,
        // Settings → "Re-register now". This is the USER-FACING recovery for the
        // silent-de-registration failure, and it is one of only TWO places in the app
        // that register with Apple (the other is interactive sign-in). It runs the
        // full ladder, [SmartTxtRepository.fixConnection], which rebuilds the native
        // client before re-registering — going straight at the client fails with "the
        // iMessage engine isn't running yet" whenever that client is dead, which is
        // precisely the state a stuck device is in.
        //
        // The narrower variants (reregisterNow / fixConnectionBlocking) were deleted
        // in Aug 2026: they had no callers, no guards, and reached Apple in one hop.
        // If you need a test hook, add one behind @VisibleForTesting — do not
        // reintroduce a second production path to registration.
        onReregister = {
            Toast.makeText(context, "Reconnecting iMessage…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    SmartTxtRepository.fixConnection(context)
                }
                // Never surface a raw error. Transient faults are retried in code and
                // then handed to the background renewal, so the only message that
                // ever asks anything of the user is the genuinely unrecoverable one.
                val msg = when (outcome) {
                    is SmartTxtRepository.FixOutcome.Recovered ->
                        "iMessage reconnected. Try sending again."
                    is SmartTxtRepository.FixOutcome.RetryingInBackground ->
                        "Still reconnecting in the background — you can keep using Smart Txt."
                    is SmartTxtRepository.FixOutcome.NeedsUser -> outcome.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            Unit
        },
        onExportLogs = {
            Toast.makeText(context, "Exporting Smart Txt logs...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    SmartTxtLogExporter.export(context.applicationContext)
                }
                val msg = result.fold(
                    onSuccess = { ref -> "Logs sent to support. Reference: $ref" },
                    onFailure = { e -> "Couldn't send logs: ${e.message ?: "unknown error"}" },
                )
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            Unit
        },
        initialRoomId = initialRoomId,
        initialRoomKey = initialRoomKey,
    )

    if (showDebug) {
        SmartTxtDebugDialog(context = context, onDismiss = { showDebug = false })
    }
}

/** Consecutive 24-hour-time toggles needed to reveal the hidden diagnostics. Even,
 *  so the preference ends where it started. */
private const val DEBUG_TOGGLE_COUNT = 10

/** Max gap between toggles before the run resets. */
private const val DEBUG_TOGGLE_WINDOW_MS = 3_000L

/**
 * "Moving your messages…" while a legacy-blob migration is in flight, null
 * otherwise (which renders the original bare duck).
 *
 * Reads a process-global flow rather than being threaded down from the
 * repository: the migration runs inside [MessageStore.migrateIfNeeded], several
 * layers below any composable, and plumbing a callback up through three
 * repositories to reach three gate screens would be far more invasive.
 *
 * Expect this to flash rather than linger — 441 messages import in one
 * transaction. It is reassurance for the rare large account, not a progress bar.
 */
@Composable
private fun migrationLabel(): String? {
    val migrating by MigrationStatus.backendId.collectAsState()
    return if (migrating != null) "Moving your messages\u2026" else null
}
