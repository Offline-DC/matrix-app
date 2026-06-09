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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    /** Re-link the phone (re-pair / reauth, keeping messages). Shown above
     *  Log out when set; null hides it. */
    onRelink: (() -> Unit)? = null,
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
) {
    // Land focus on the back button on entry, so the screen has a visible
    // highlight and DPAD navigation works immediately (every other screen sets
    // initial focus; this one used to start with nothing focused).
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
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
            if (onAutoDeleteChange != null) {
                SettingHeader("Messages")
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
            SettingHeader("About")
            StaticRow(title = "Version", subtitle = "0.2.0 — Phase 2 demo")
            StaticRow(title = "Repository", subtitle = "dpad-messenger")
            // Log out kept last so it's the bottom-most action.
            SettingHeader("Account")
            if (onRelink != null) {
                ActionRow(
                    title = "Re-link phone",
                    subtitle = "Reconnect to your phone — keeps your messages",
                    onClick = onRelink,
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
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .dpadRow(onClick = onClick, shape = RoundedCornerShape(10.dp))
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
