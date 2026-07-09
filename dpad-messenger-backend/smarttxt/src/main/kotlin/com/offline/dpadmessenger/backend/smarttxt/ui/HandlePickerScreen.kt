package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.smarttxt.R
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtAccountStore
import com.offline.dpadmessenger.ui.components.DpadButton
import kotlinx.coroutines.delay

/** "tel:+1…" → "+1…", "mailto:x@y" → "x@y". */
private fun prettyHandle(h: String): String = h.removePrefix("tel:").removePrefix("mailto:")

/**
 * Post-login success + "start new messages from" chooser (the SmartTxt twin of
 * OpenBubbles' post-activation handle screen).
 *
 * Shows a single dropdown of the registered handles — the phone number is the
 * initial selection; DPAD-Up from Continue lands on it and OK opens the list to
 * switch to an iCloud email. [DpadButton] "Continue" is focused by default,
 * persists the choice, and flips the gate to the (blank) chat list.
 */
@Composable
fun HandlePickerScreen(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SmartTxtAccountStore(context) }
    val handles = remember { store.loadAccount()?.handles.orEmpty() }

    // Default the sending handle to the phone number, else the first handle.
    var selected by remember {
        mutableStateOf(handles.firstOrNull { it.startsWith("tel:") } ?: handles.firstOrNull() ?: "")
    }
    var expanded by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val dropdownFr = remember { FocusRequester() }
    val continueFr = remember { FocusRequester() }
    // Auto-focus Continue (retry a few frames until it's attached).
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { continueFr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }

    val helvetica = remember { FontFamily(Font(R.font.helvetica_now_text_black)) }
    val typo = MaterialTheme.typography
    MaterialTheme(
        typography = typo.copy(
            headlineSmall = typo.headlineSmall.copy(fontFamily = helvetica),
            titleSmall = typo.titleSmall.copy(fontFamily = helvetica),
            bodyLarge = typo.bodyLarge.copy(fontFamily = helvetica),
            bodyMedium = typo.bodyMedium.copy(fontFamily = helvetica),
            labelLarge = typo.labelLarge.copy(fontFamily = helvetica),
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("u r signed in!!!", style = MaterialTheme.typography.headlineSmall)
            Text(
                "you will start messages from:",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            if (handles.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(dropdownFr)
                            .onFocusChanged { fieldFocused = it.isFocused }
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (fieldFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { expanded = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            prettyHandle(selected),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change number")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        handles.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(prettyHandle(h)) },
                                onClick = { selected = h; expanded = false },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            DpadButton(
                text = "Continue",
                onClick = {
                    store.saveHandleSelection(enabled = handles, default = selected)
                    // Apply immediately so the very first outgoing text sends from
                    // the chosen number/email (not just handles.first()).
                    RustPushNative.runCatchingNativeSetSendHandle(selected)
                    onContinue()
                },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = continueFr,
            )
        }
    }
}
