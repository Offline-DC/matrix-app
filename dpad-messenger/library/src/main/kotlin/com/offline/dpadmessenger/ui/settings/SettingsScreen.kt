package com.offline.dpadmessenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            CompactTopBar(
                title = "Settings",
                navigationIcon = {
                    CompactBarButton(onClick = onBack) {
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
            modifier = Modifier.padding(inner).padding(8.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingHeader("Display")
            ToggleRow(
                title = "Dark theme",
                subtitle = "Use the system theme on by default",
                checked = darkTheme,
                onCheckedChange = onDarkThemeChange,
            )
            SettingHeader("Account")
            ActionRow(
                title = "Log out",
                subtitle = "Sign out of this device",
                destructive = true,
                onClick = onLogout,
            )
            SettingHeader("About")
            StaticRow(title = "Version", subtitle = "0.2.0 — Phase 2 demo")
            StaticRow(title = "Repository", subtitle = "dpad-messenger")
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
