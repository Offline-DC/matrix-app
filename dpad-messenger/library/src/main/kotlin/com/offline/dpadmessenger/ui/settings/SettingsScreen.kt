package com.offline.dpadmessenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.components.CompactBarButton
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
    /** Re-link the phone — a fresh sign-in for a new ~2-week session, keeping
     *  messages. Shown above Log out when set; null hides it. */
    onRelink: (() -> Unit)? = null,
    /** Whole days since the last fresh sign-in. When set, shows a "last linked"
     *  row under Re-link (and warns when the session is near its ~2-week end). */
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
    /** Auto-delete-old-messages toggle. Hidden when [onAutoDeleteChange] is
     *  null (repositories without local retention). */
    autoDeleteEnabled: Boolean = true,
    onAutoDeleteChange: ((Boolean) -> Unit)? = null,
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
    /** Force an IDS re-registration now (the periodic renewal, on demand). Shows a
     *  "Re-register now" row when set; null hides it. Host handles the result feedback. */
    onReregister: (() -> Unit)? = null,
) {
    // Settings has no soft-key actions of its own. Publish BLANK labels so the
    // native soft-key bar stays VISIBLE (matching every other screen) instead of
    // hiding it — setVisible(false) left an empty black strip at the bottom.
    com.offline.dpadmessenger.ui.components.MessengerSoftKeys()

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
            if (onAutoDeleteChange != null || showHandlePicker) {
                SettingHeader("Messages")
                if (showHandlePicker) {
                    HandlePickerRow(
                        handles = sendHandles,
                        selected = defaultSendHandle.ifBlank { sendHandles.first() },
                        onSelect = onDefaultSendHandleChange!!,
                    )
                }
                if (onAutoDeleteChange != null) {
                    ToggleRow(
                        title = "Auto-delete old messages",
                        subtitle = "Remove texts older than 3 days from this device. " +
                            "This will not delete messages on other devices.",
                        checked = autoDeleteEnabled,
                        onCheckedChange = onAutoDeleteChange,
                    )
                }
            }
            // Log out kept last so it's the bottom-most action.
            SettingHeader("Account")
            if (onRelink != null) {
                ActionRow(
                    title = "Re-link phone",
                    subtitle = "Scan the code again for a fresh 2-week session — keeps your messages",
                    // Tint the row when the session is near its end so it stands out.
                    destructive = linkAgeDays != null && linkAgeDays >= RELINK_WARN_DAYS,
                    focusRequester = relinkFocus,
                    onClick = onRelink,
                )
            }
            if (linkAgeDays != null) {
                StaticRow(title = "Days since last link", subtitle = linkAgeDays.toString())
            }
            if (onReregister != null) {
                ActionRow(
                    title = "Re-register now",
                    subtitle = "Refresh this device's iMessage registration (no sign-in needed)",
                    onClick = onReregister,
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

/** Day threshold at/after which we warn the user their ~2-week Google session is
 *  about to expire and they should re-link. Shared with the room-list banner. */
internal const val RELINK_WARN_DAYS = 13

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
        Column {
            Text(title, color = color, style = MaterialTheme.typography.bodyLarge)
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
