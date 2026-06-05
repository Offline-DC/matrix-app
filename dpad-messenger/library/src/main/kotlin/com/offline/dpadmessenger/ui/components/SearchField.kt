package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

/**
 * Compact search input for the room list. DPAD-Down moves to the first row;
 * pressing OK on the trailing close button clears the query.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search chats",
    focusRequester: FocusRequester? = null,
) {
    val colors = LocalDpadMessengerColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fr = focusRequester ?: remember { FocusRequester() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .focusRequester(fr)
                // bg/clip before the highlight so the halo border + tint paint on top.
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .dpadFocusHighlight(shape = RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.mutedText,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) keyboard?.show() },
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = colors.mutedText,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
        }
        // Always mount the clear button so its focus node is stable across
        // query changes. Disable + dim when empty rather than unmounting,
        // otherwise DPAD focus gets orphaned when the user clears.
        ClearButton(
            enabled = query.isNotEmpty(),
            onClick = {
                // Move focus back to the field first so we don't disappear
                // a focused node — actually this button stays mounted, so
                // the only side effect is clearing.
                onQueryChange("")
            },
        )
    }
}

@Composable
private fun ClearButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = MaterialTheme.colorScheme.surface
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
        else LocalDpadMessengerColors.current.mutedText
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .dpadRow(onClick = { if (enabled) onClick() }, shape = CircleShape),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Clear search",
            tint = tint,
        )
    }
}
