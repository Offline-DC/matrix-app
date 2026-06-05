package com.offline.dpadmessenger.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

/**
 * Stub login screen.
 *
 * The whole screen is shaped to match the Matrix login flow (homeserver +
 * user + password) so Phase 3 can swap in real auth without touching the
 * surface. Today it just accepts anything and calls [onLoginSuccess].
 *
 * DPAD layout: top-to-bottom Homeserver → Username → Password → Continue.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var homeserver by rememberSaveable { mutableStateOf("https://matrix.org") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Sign in",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "Connect to your Matrix homeserver",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalDpadMessengerColors.current.mutedText,
            )
            LabeledField(
                label = "Homeserver",
                value = homeserver,
                onValueChange = { homeserver = it },
                imeAction = ImeAction.Next,
                capitalization = KeyboardCapitalization.None,
            )
            LabeledField(
                label = "Username",
                value = username,
                onValueChange = { username = it },
                imeAction = ImeAction.Next,
                capitalization = KeyboardCapitalization.None,
            )
            LabeledField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.None,
                obscure = true,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                ContinueButton(
                    enabled = username.isNotBlank() && password.isNotBlank(),
                    onClick = onLoginSuccess,
                )
            }
            Text(
                text = "Stub: any non-empty credentials work. Real auth lands in Phase 3.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalDpadMessengerColors.current.mutedText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    capitalization: KeyboardCapitalization,
    obscure: Boolean = false,
) {
    val fr = remember { FocusRequester() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalDpadMessengerColors.current.mutedText,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .focusRequester(fr)
                // bg/clip before the highlight so the halo paints on top.
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .dpadFocusHighlight(shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (obscure) PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = imeAction,
                    capitalization = capitalization,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ContinueButton(enabled: Boolean, onClick: () -> Unit) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (enabled) MaterialTheme.colorScheme.onPrimary
        else LocalDpadMessengerColors.current.mutedText
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(accent)
            .dpadRow(onClick = { if (enabled) onClick() }, shape = CircleShape),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Continue",
            tint = iconTint,
        )
    }
}
