package com.offline.dpadmessenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

/**
 * Stub settings screen.
 *
 * Right now this is structural — it exists so the navigation graph can route
 * here and Phase 3 can drop real preferences in (homeserver URL, push backend,
 * verified-device list, recovery key, etc.) without touching the entry-point
 * code. The current widgets are illustrative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    /** Re-link the phone — a fresh sign-in that keeps
     *  messages. Shown above Log out when set; null hides it. */
    onRelink: (() -> Unit)? = null,
    /** Whole days since the last fresh sign-in. When set, shows a "last linked"
     *  row under Re-link. Informational only — no expiry is tied to it. */
    linkAgeDays: Int? = null,
    /** When true, land initial focus on the "Re-link phone" row instead of the
     *  back button — used when the user arrives here from the day-13 banner. */
    focusRelinkOnEntry: Boolean = false,
    modifier: Modifier = Modifier,
    /** When true, show the dark-theme toggle (demo harness). The launcher
     *  forces light, so it passes false and the row is hidden. */
    showDarkThemeToggle: Boolean = false,
    darkTheme: Boolean = false,
    onDarkThemeChange: (Boolean) -> Unit = {},
    /** How many days of messages this device keeps. The row is hidden when
     *  [onAutoDeleteDaysChange] is null (repositories without local retention). */
    autoDeleteDays: Int = com.offline.dpadmessenger.data.RetentionSettings.DEFAULT_RETENTION_DAYS,
    onAutoDeleteDaysChange: ((Int) -> Unit)? = null,
    /** Send-read-receipts toggle. Hidden when [onSendReadReceiptsChange] is null
     *  (repositories that can't send receipts, e.g. the mock). Off by default:
     *  reading still clears the notification on the user's own devices, but the
     *  sender isn't told "Read" unless this is on.
     *
     *  THIS DEVICE ONLY. Read receipts are per-device on Apple and do not sync, so
     *  this cannot turn them off on the user's iPhone or Mac — the copy says so. */
    sendReadReceipts: Boolean = false,
    onSendReadReceiptsChange: ((Boolean) -> Unit)? = null,
    /** 24-hour clock toggle. Hidden when [onUse24HourTimeChange] is null. */
    use24HourTime: Boolean = false,
    onUse24HourTimeChange: ((Boolean) -> Unit)? = null,
    /** The handles the user can send FROM (raw form, e.g. "tel:+1…"/"mailto:…").
     *  When non-empty and [onDefaultSendHandleChange] is set, Settings shows a
     *  "Start new messages from" picker — the default handle for NEW conversations
     *  (existing threads keep their own handle, decided natively per-thread). */
    sendHandles: List<String> = emptyList(),
    defaultSendHandle: String = "",
    onDefaultSendHandleChange: ((String) -> Unit)? = null,
    /** Force an IDS re-registration now. Shows a "Re-register now" row when set;
     *  null hides it. Host handles the result feedback.
     *
     *  This is one of only TWO paths in the app that register with Apple (the other
     *  is interactive sign-in), and it is unthrottled by design — it calls
     *  rustpush's `refresh_now()`, which cancels the ResourceManager's backoff
     *  sleep. On an account Apple has already flagged (IDS 6009) repeated taps
     *  extend the block, which is exactly how one customer lost sending entirely in
     *  Aug 2026. Hence the subtitle steering users to support first. */
    onReregister: (() -> Unit)? = null,
    /** DEBUG: arm a one-shot override so the PROACTIVE token refresh fires on the next
     *  maintenance tick instead of an hour before a 24-hour token expires.
     *
     *  Distinct from [onReregister], which is the thing people reach for and the wrong
     *  tool here: that calls `reauth()`, skipping the expiry arithmetic and the threshold
     *  and restarting the long-poll afterwards. This row exercises the branch nobody can
     *  otherwise reach without waiting ~23h — a refresh that fires from the timer,
     *  mid-session, and must leave the stream and the registration untouched.
     *
     *  Google-Messages only; null hides the row. */
    onForceTokenRefresh: (() -> Unit)? = null,
    /** DEBUG: run the pairing check on demand — ask Google whether this device is still
     *  in the account's registration list, AND fire a live re-assert to see whether the
     *  paired phone still answers.
     *
     *  Exists because the shipping detector is deliberately slow: it must outlast a
     *  late `BROWSER_ACTIVE` echo (MEASURED at +768 s and +884 s on a healthy device)
     *  before it can accuse a link of being dead, so reproducing an unpair takes 11-30
     *  minutes of waiting. This row answers the same question in about 30 seconds.
     *
     *  Google-Messages only; null hides the row. */
    onCheckPairing: (() -> Unit)? = null,
    /** Export this device's Smart Txt logs to support (zips the rolling
     *  Smart-Txt-only log ring and uploads it). Shown just above Log out when
     *  set; null hides the row. Host handles the result feedback. */
    onExportLogs: (() -> Unit)? = null,
) {
    // Land focus on the back button on entry, so the screen has a visible
    // highlight and DPAD navigation works immediately (every other screen sets
    // initial focus; this one used to start with nothing focused).
    val backFocus = remember { FocusRequester() }
    val relinkFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Arriving from the re-link banner lands on the Re-link row; otherwise
        // the back button (so the screen always has a visible highlight).
        val target = if (focusRelinkOnEntry && onRelink != null) relinkFocus else backFocus
        // Retry across a few frames — the target row may not be attached on the
        // first frame (esp. the re-link row below the scroll fold).
        repeat(10) {
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos {}
        }
    }
    Scaffold(
        // Right soft key only. Left stays blank: nothing on this page needs a
        // second action, and an invented one would just be another way to leave.
        bottomBar = {
            SoftKeys(right = SoftKey("back") { onBack() })
        },
        topBar = {
            CompactTopBar(
                title = "Settings",
                navigationIcon = {
                    CompactBarButton(onClick = onBack, focusRequester = backFocus) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(8.dp)
                .fillMaxSize()
                // Scrollable so content taller than the screen (esp. on a
                // 240x320 flip) can be reached. The focusable rows drive the
                // scroll via DPAD; the About section sits ABOVE the bottom so
                // it's visible on the way down to Log out.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showDarkThemeToggle || onUse24HourTimeChange != null) {
                SettingHeader("Display")
                if (showDarkThemeToggle) {
                    ToggleRow(
                        title = "Dark theme",
                        subtitle = "Use the system theme on by default",
                        checked = darkTheme,
                        onCheckedChange = onDarkThemeChange,
                    )
                }
                if (onUse24HourTimeChange != null) {
                    ToggleRow(
                        title = "24-hour time",
                        subtitle = "Show times like 14:30 instead of 2:30 PM",
                        checked = use24HourTime,
                        onCheckedChange = onUse24HourTimeChange,
                    )
                }
            }
            val showHandlePicker = onDefaultSendHandleChange != null && sendHandles.isNotEmpty()
            if (onAutoDeleteDaysChange != null || onSendReadReceiptsChange != null || showHandlePicker) {
                SettingHeader("Messages")
                if (showHandlePicker) {
                    HandlePickerRow(
                        handles = sendHandles,
                        selected = defaultSendHandle.ifBlank { sendHandles.first() },
                        onSelect = onDefaultSendHandleChange!!,
                    )
                }
                if (onSendReadReceiptsChange != null) {
                    ToggleRow(
                        title = "Send read receipts",
                        // Say "this phone" explicitly. Read receipts are a PER-DEVICE
                        // setting on Apple — turning them off here does not turn them
                        // off on the user's iPhone or Mac, and if they read the message
                        // there the sender still sees "Read". The old copy didn't say
                        // that, so people turned it off here, saw "Read" anyway, and
                        // reported it as broken.
                        subtitle = "Let people see when you've read their message on this phone. " +
                            "Your iPhone and Mac each have their own setting — turn it off " +
                            "there too. When off, reading still clears the notification on " +
                            "your other devices.",
                        checked = sendReadReceipts,
                        onCheckedChange = onSendReadReceiptsChange,
                    )
                }
                if (onAutoDeleteDaysChange != null) {
                    // Press-to-cycle rather than a dropdown: there is no pointer here,
                    // and ActionRow already gets d-pad focus right. Four options is few
                    // enough that cycling reaches any of them in at most three presses.
                    val opts = com.offline.dpadmessenger.data.RetentionSettings.RETENTION_DAY_OPTIONS
                    // "Ever" is not in opts — it is a migration leftover, not a choice,
                    // so indexOf returns -1 for it and the first press leaves it behind
                    // for good. That press lands on the LAST option (28 days), the
                    // closest real window: jumping straight to 1 day would prune nearly
                    // everything on the phone in one accidental press, with no way back.
                    val next = opts.indexOf(autoDeleteDays).let { i ->
                        if (i < 0) opts.last() else opts[(i + 1) % opts.size]
                    }
                    ActionRow(
                        title = "Keep messages for",
                        value = retentionLabel(autoDeleteDays),
                        subtitle = "Press OK to change. Older texts are removed from this " +
                            "phone only; your other devices keep theirs.",
                        onClick = { onAutoDeleteDaysChange(next) },
                    )
                }
            }
            // Log out kept last so it's the bottom-most action.
            SettingHeader("Account")
            if (onRelink != null) {
                ActionRow(
                    title = "Re-link phone",
                    subtitle = "Scan the code again if messages stop arriving — keeps your messages",
                    focusRequester = relinkFocus,
                    onClick = onRelink,
                )
            }
            if (linkAgeDays != null) {
                StaticRow(title = "Days since last link", subtitle = linkAgeDays.toString())
                // NB: informational only. There is no known expiry tied to this number —
                // see GMESSAGES_SEAMLESS_LINK_DESIGN_20260817.md §1(b)/§3. Do not
                // reintroduce a day-threshold warning without measured evidence.
            }
            if (onReregister != null) {
                ActionRow(
                    title = "Re-register now",
                    subtitle = "For debugging only \u2013 reach out to support@dumb.co before doing this!",
                    onClick = onReregister,
                )
            }
            if (onForceTokenRefresh != null) {
                ActionRow(
                    title = "Force token refresh (debug)",
                    subtitle = "Fires the proactive refresh on the next tick. One-shot \u2013 " +
                        "messages should keep arriving with no reconnect screen",
                    onClick = onForceTokenRefresh,
                )
            }
            if (onCheckPairing != null) {
                ActionRow(
                    title = "Check pairing now (debug)",
                    subtitle = "Asks Google and the phone whether this device is still " +
                        "linked. Takes ~30s \u2013 result in a toast, detail in the logs",
                    onClick = onCheckPairing,
                )
            }
            if (onExportLogs != null) {
                ActionRow(
                    title = "Export logs",
                    subtitle = "Send Smart Txt logs to support to help diagnose an issue",
                    onClick = onExportLogs,
                )
            }
            ActionRow(
                title = "Log out",
                subtitle = "Sign out of this device",
                destructive = true,
                onClick = onLogout,
            )
        }
    }
}


@Composable
private fun SettingHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalDpadMessengerColors.current.mutedText,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadRow(
                onClick = { onCheckedChange(!checked) },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalDpadMessengerColors.current.mutedText,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Optional current value, shown bold immediately after [title] on the same
     *  line, so a row reads "Keep messages for **3 days**" and the subtitle is
     *  free to be the instruction rather than the value. Null keeps the plain
     *  title-and-subtitle layout every other row uses. */
    value: String? = null,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                    // fill = false so the value hugs the title instead of being
                    // flung to the right edge. On a 240px screen a hard-right
                    // value forces the title to wrap and the pair stops reading
                    // as one phrase.
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (value != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        value,
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalDpadMessengerColors.current.mutedText,
            )
        }
    }
}

/** "Start new messages from" default-handle chooser: a focusable row showing the
 *  current number/email, opening a dropdown of the other registered handles on OK.
 *  This default only applies to NEW conversations — an existing thread continues
 *  from whatever handle it was started with (handled natively per-thread). */
@Composable
private fun HandlePickerRow(
    handles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .dpadRow(onClick = { expanded = true }, shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start new messages from", style = MaterialTheme.typography.bodyLarge)
                Text(
                    prettyHandle(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDpadMessengerColors.current.mutedText,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change the handle new conversations start from")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            handles.forEach { h ->
                DropdownMenuItem(
                    text = { Text(prettyHandle(h)) },
                    onClick = {
                        onSelect(h)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** "tel:+1…" → "+1…", "mailto:x@y" → "x@y". */
/**
 * "Ever" / "1 day" / "3 days" — the settings row's current value.
 *
 * "Ever", not "Never": the value sits on the title's line, so the row reads as one
 * sentence — "Keep messages for **Ever**". "Keep messages for Never" says the exact
 * opposite of what that setting does.
 */
private fun retentionLabel(days: Int): String = when {
    days <= com.offline.dpadmessenger.data.RetentionSettings.RETENTION_NEVER_DAYS -> "Ever"
    days == 1 -> "1 day"
    else -> "$days days"
}

private fun prettyHandle(h: String): String = h.removePrefix("tel:").removePrefix("mailto:")

@Composable
private fun StaticRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalDpadMessengerColors.current.mutedText,
            )
        }
    }
}
