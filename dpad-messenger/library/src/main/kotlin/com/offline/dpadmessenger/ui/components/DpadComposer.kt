package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors

/**
 * Bottom message composer. Designed for DPAD users:
 *  - The text field is the default focus target when the chat screen opens.
 *  - DPAD-Up from the field calls [onUpFromField] (the chat screen wires this
 *    to "focus the last message"), unconditionally — we don't try to share
 *    Up between cursor movement and focus traversal because flip-phone chat
 *    messages are almost always single-line in practice.
 *  - DPAD-Right within the text moves the cursor right; **at end of string**,
 *    it jumps to the Send button.
 *  - DPAD-Left from the Send button returns to the field.
 *  - OK on the Send button submits (does not require an IME tap).
 *
 * @param onSend invoked with the trimmed message body when the user submits.
 * @param onUpFromField fired on DPAD-Up while the field has focus. Wire this
 *        to focus the previous focusable (last bubble) yourself — we don't
 *        guess at the chat layout from here.
 * @param onLeftFromField fired on DPAD-Left while the field has focus. The
 *        chat screen wires this to focus the back button in the top bar.
 * @param header optional content rendered above the input row (e.g. the
 *               reply / edit banner).
 * @param prefill optional starting value (e.g. when starting an edit).
 * @param prefillKey identity for [prefill]. When this changes, the field
 *                   resets to [prefill]. Pass the edit-target message id so
 *                   cancelling then re-editing the same message reloads.
 */
@Composable
fun DpadComposer(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Type a message",
    textFieldFocusRequester: FocusRequester? = null,
    sendButtonFocusRequester: FocusRequester? = null,
    prefill: String? = null,
    prefillKey: Any? = null,
    onUpFromField: () -> Unit = {},
    onLeftFromField: () -> Unit = {},
    autoFocusOnAttach: Boolean = false,
    header: @Composable (() -> Unit)? = null,
) {
    val colors = LocalDpadMessengerColors.current
    var fieldValue by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(""))
    }
    val keyboard = LocalSoftwareKeyboardController.current

    val fieldFr = textFieldFocusRequester ?: remember { FocusRequester() }
    val sendFr = sendButtonFocusRequester ?: remember { FocusRequester() }

    // Tracks whether the inner field has been placed in the layout (and
    // therefore is in the focus tree). The parent-side LaunchedEffect that
    // we used to use ran too early — requestFocus() silently no-op'd because
    // the focus node wasn't attached. onGloballyPositioned guarantees the
    // node is ready.
    var fieldAttached by remember { mutableStateOf(false) }
    LaunchedEffect(fieldAttached) {
        if (fieldAttached && autoFocusOnAttach) {
            runCatching { fieldFr.requestFocus() }
            // Defensively hide the IME in case Compose auto-opened it.
            delay(100)
            keyboard?.hide()
        }
    }
    // Focus goes on the inner BasicTextField directly so the user sees the
    // actual input as the entry target. The wrapper still draws the halo via
    // hasFocus (true when any descendant is focused). The chat screen is in
    // charge of suppressing the auto-IME after focus so Back still leaves in
    // one press — see ChatScreen's LaunchedEffect that calls hide() after.

    // When the parent hands us a fresh prefill (e.g. "edit this message"),
    // overwrite whatever the user had been typing. Keyed on [prefillKey] so
    // cancel+re-edit of the same message refires.
    LaunchedEffect(prefillKey) {
        if (prefill != null) {
            fieldValue = TextFieldValue(prefill, selection = TextRange(prefill.length))
        }
    }

    fun submit() {
        val payload = fieldValue.text.trim()
        if (payload.isEmpty()) return
        onSend(payload)
        fieldValue = TextFieldValue("")
        keyboard?.hide()
        // Keep focus on the inner field after send. The IME stays hidden
        // because we just called hide(); next Back goes straight to nav.
        runCatching { fieldFr.requestFocus() }
    }

    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (header != null) header()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // Halo via hasFocus — true when the inner BasicTextField
                    // (descendant) has focus. Wrapper itself isn't focusable.
                    .dpadFocusHighlight(shape = RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    singleLine = false,
                    maxLines = 4,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { submit() },
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        // The inner field is the DPAD entry target — caller's
                        // FocusRequester is wired here so the chat screen can
                        // focus the actual input on entry.
                        .focusRequester(fieldFr)
                        // Flip the attached flag the first time layout has
                        // measured + placed this field. That's the only safe
                        // moment for the auto-focus LaunchedEffect above to
                        // call requestFocus() — before placement, the focus
                        // node isn't in the tree and the call silently
                        // does nothing.
                        .onGloballyPositioned {
                            if (!fieldAttached) fieldAttached = true
                        }
                        // Pre-IME key handler: act on DPAD before the text
                        // field consumes events for cursor movement.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    onUpFromField()
                                    true
                                }
                                Key.DirectionLeft -> {
                                    onLeftFromField()
                                    true
                                }
                                Key.DirectionRight -> {
                                    val sel = fieldValue.selection
                                    val atEnd = sel.collapsed && sel.start >= fieldValue.text.length
                                    if (atEnd) {
                                        runCatching { sendFr.requestFocus() }
                                        true
                                    } else false
                                }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (fieldValue.text.isEmpty()) {
                            Text(
                                text = hint,
                                color = colors.mutedText,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    },
                )
            }

            SendButton(
                enabled = fieldValue.text.isNotBlank(),
                onClick = { submit() },
                focusRequester = sendFr,
                onLeftToField = { runCatching { fieldFr.requestFocus() } },
            )
        }
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onLeftToField: () -> Unit,
) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (enabled) MaterialTheme.colorScheme.onPrimary
        else LocalDpadMessengerColors.current.mutedText
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .focusRequester(focusRequester)
            .clip(CircleShape)
            .background(accent)
            // Halo color is onPrimary (white-ish), not primary — otherwise it
            // disappears into the blue button background.
            .dpadFocusHighlight(
                shape = CircleShape,
                borderColor = MaterialTheme.colorScheme.onPrimary,
                focusedTint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
            )
            .focusable()
            .onDpadAction { if (enabled) { onClick(); true } else false }
            // DPAD-Left jumps back to the text field so the user doesn't have
            // to round-trip through a Down/Up sequence.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onLeftToField()
                    true
                } else false
            }
            .padding(PaddingValues(8.dp)),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = iconTint,
        )
    }
}

/** Saver so `rememberSaveable` can round-trip a [TextFieldValue]. */
private val TextFieldValueSaver = androidx.compose.runtime.saveable.Saver<TextFieldValue, Any>(
    save = { value -> listOf(value.text, value.selection.start, value.selection.end) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val list = saved as List<Any>
        TextFieldValue(
            text = list[0] as String,
            selection = TextRange(list[1] as Int, list[2] as Int),
        )
    },
)
