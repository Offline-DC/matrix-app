package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.offline.dpadmessenger.media.VoiceRecorder
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusRing
import com.offline.dpadmessenger.focus.onDpadAction
import com.offline.dpadmessenger.ui.theme.ComposerAttachBg
import com.offline.dpadmessenger.ui.theme.ComposerAttachGlyph
import com.offline.dpadmessenger.ui.theme.ComposerButtonHighlight
import com.offline.dpadmessenger.ui.theme.ComposerButtonNeutral
import com.offline.dpadmessenger.ui.theme.ComposerButtonResting
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
    /** When non-null, shows a "+" attach button left of the field and invokes
     *  this on activate. DPAD-Left from the field focuses it; Left again exits
     *  via [onLeftFromField]. */
    onAttach: (() -> Unit)? = null,
    /** When non-null, an empty text field shows a record (mic) button instead of
     *  Send; recording → stop → a preview modal → this fires with the recorded
     *  .m4a file path to send as a voice memo. */
    onSendVoiceMemo: ((filePath: String) -> Unit)? = null,
    /** True on a green SMS thread — the Send button uses SMS green, not iMessage blue. */
    isSms: Boolean = false,
    header: @Composable (() -> Unit)? = null,
) {
    val colors = LocalDpadMessengerColors.current
    val context = LocalContext.current
    var fieldValue by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(""))
    }
    // Latest text layout, so DPAD Up/Down can tell which line the cursor is on
    // (escape out of the field only at the first/last line).
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    val fieldFr = textFieldFocusRequester ?: remember { FocusRequester() }
    val attachFr = remember { FocusRequester() }
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

    // ---- voice memo recording -------------------------------------------------
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordStartMs by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    // Bumped after a voice memo is sent so we can move focus back to the text
    // field ("Type a message"), the same as after sending text.
    var focusFieldSignal by remember { mutableStateOf(0) }
    LaunchedEffect(focusFieldSignal) {
        if (focusFieldSignal == 0) return@LaunchedEffect
        repeat(12) {
            if (runCatching { fieldFr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }

    // Abandon any in-progress recording if the composer leaves composition.
    DisposableEffect(Unit) { onDispose { runCatching { recorder.cancel() } } }

    fun beginRecording() {
        if (recorder.start()) {
            recording = true
            recordStartMs = System.currentTimeMillis()
            elapsedSec = 0
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) beginRecording() }
    fun onRecordPressed() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) beginRecording()
        else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    fun onStopPressed() {
        val file = recorder.stop()
        recording = false
        previewPath = file?.absolutePath
        // Move the composer's focus to the text input. Without this, removing the
        // Stop button drops focus to the back button (which flashes behind the
        // modal and looks wrong). The preview modal manages its own focus (Play)
        // in its separate layer, so this doesn't fight it.
        focusFieldSignal++
    }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        // The trailing button just swapped mic→stop, which is a new focus node,
        // so DPAD focus was dropped. Land it on the Stop button so the user can
        // actually stop. Retry a few frames since the new node may not be
        // attached the instant we ask (requestFocus throws until it is).
        for (i in 0 until 12) {
            if (runCatching { sendFr.requestFocus() }.isSuccess) break
            delay(16)
        }
        // Tick the elapsed timer while recording.
        while (recording) {
            elapsedSec = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
            delay(250)
        }
    }
    val voiceEnabled = onSendVoiceMemo != null

    // The Scaffold's imePadding shifts content up by the keyboard height, but
    // on TCL devices the predictive/candidate strip sits ABOVE the keyboard and
    // isn't part of the IME inset — so the composer's last line gets hidden
    // behind it. While the keyboard is up, add extra bottom space to lift the
    // field clear of that strip. Zero when the keyboard is closed.
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val extraImeBottom = if (keyboardUp) 48.dp else 0.dp

    // The rounded field. OpenBubbles puts the voice/send affordance INSIDE this
    // pill at its trailing edge (only the "+" lives outside it), so the pill is a
    // Row — text on the left, action button on the right — rather than the plain
    // Box it used to be.
    val fieldShape = RoundedCornerShape(19.dp)
    val accent = MaterialTheme.colorScheme.primary
    // Focus halo is driven by the TEXT FIELD's own focus, not hasFocus: now that
    // the send/mic button is a descendant of the pill, hasFocus would light the
    // whole field up whenever the user DPADs onto that button.
    var fieldFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = extraImeBottom),
    ) {
        if (header != null) header()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                // Treat the field + Send button as one focus group, and make
                // the TEXT FIELD the entry target. So DPAD-Down from the
                // message list lands on "Type a message", not the Send button.
                // (Right-from-field still reaches Send via its own requester.)
                .focusProperties { @Suppress("DEPRECATION") enter = { fieldFr } }
                .focusGroup(),
        ) {
            if (onAttach != null) {
                AttachButton(
                    onClick = onAttach,
                    focusRequester = attachFr,
                    onLeft = onLeftFromField, // Left from "+" exits to back button
                    onRight = { runCatching { fieldFr.requestFocus() } },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(fieldShape)
                    .background(
                        if (fieldFocused) accent.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // Hairline outline at rest (the iOS field is an outlined pill,
                    // not a filled one); it thickens to the accent on DPAD focus,
                    // which is what the old dpadFocusHighlight did here.
                    .border(
                        width = if (fieldFocused) 2.dp else 1.dp,
                        color = if (fieldFocused) accent else colors.divider,
                        shape = fieldShape,
                    )
                    // Tight end padding — the inner action button supplies the
                    // rest of the optical inset.
                    .padding(start = 12.dp, end = 4.dp),
            ) {
            if (recording) {
                RecordingIndicator(elapsedSec = elapsedSec, modifier = Modifier.weight(1f))
            } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
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
                    onTextLayout = { textLayout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fieldFocused = it.isFocused }
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
                                    // Only leave the field for the messages above
                                    // when the cursor is on the FIRST line —
                                    // otherwise move the cursor up within the
                                    // input so the user can edit multi-line text.
                                    val onFirstLine = textLayout
                                        ?.let { it.getLineForOffset(fieldValue.selection.start) == 0 }
                                        ?: true
                                    if (onFirstLine) { onUpFromField(); true } else false
                                }
                                Key.DirectionLeft -> {
                                    // Only leave the field (→ "+" attach, else the
                                    // back button) when the cursor is at the very
                                    // start; otherwise move the cursor left so the
                                    // user can edit.
                                    val sel = fieldValue.selection
                                    val atStart = sel.collapsed && sel.start == 0
                                    if (atStart) {
                                        if (onAttach != null) runCatching { attachFr.requestFocus() }
                                        else onLeftFromField()
                                        true
                                    } else false
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
            }

            // The trailing action lives INSIDE the pill (OpenBubbles style):
            // waveform when there's nothing to send, up-arrow once there is.
            when {
                // While recording the trailing button becomes Stop.
                recording -> RecordStopButton(
                    recording = true,
                    onClick = { onStopPressed() },
                    focusRequester = sendFr,
                    onLeftToField = {},
                )
                // Empty field + voice enabled → a record (waveform) button.
                voiceEnabled && fieldValue.text.isBlank() -> RecordStopButton(
                    recording = false,
                    onClick = { onRecordPressed() },
                    focusRequester = sendFr,
                    onLeftToField = { runCatching { fieldFr.requestFocus() } },
                )
                else -> SendButton(
                    enabled = fieldValue.text.isNotBlank(),
                    onClick = { submit() },
                    focusRequester = sendFr,
                    onLeftToField = { runCatching { fieldFr.requestFocus() } },
                    accent = if (isSms) Color(0xFF34C759) else ComposerButtonHighlight,
                )
            }
            }
        }

        // Voice memo preview modal (play / discard / send).
        previewPath?.let { p ->
            VoiceMemoPreviewSheet(
                path = p,
                // Every exit from the preview returns focus to "Type a message",
                // never the back button.
                onSend = {
                    onSendVoiceMemo?.invoke(p)
                    previewPath = null
                    focusFieldSignal++
                },
                onDiscard = {
                    runCatching { File(p).delete() }
                    previewPath = null
                    focusFieldSignal++
                },
                onDismiss = {
                    runCatching { File(p).delete() }
                    previewPath = null
                    focusFieldSignal++
                },
            )
        }
    }
}

/** Diameter of the action button that sits inside the composer pill. Sized to
 *  leave a hairline of pill visible above and below it (pill min height 38dp),
 *  which is what makes it read as "in the field" rather than "next to it". */
private val InnerActionSize = 30.dp
private val InnerActionIcon = 17.dp

/** Trailing record/stop button, rendered INSIDE the text field's pill (waveform
 *  when idle, stop while recording).
 *
 *  The idle waveform follows the composer resting/highlighted scheme (soft blue
 *  at rest, signal blue when focused); the recording Stop state stays error-red
 *  so "recording now" reads unmistakably regardless of focus. */
@Composable
private fun RecordStopButton(
    recording: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onLeftToField: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = LocalDpadMessengerColors.current
    val smarttxt = colors.smarttxt
    val background = when {
        recording -> MaterialTheme.colorScheme.error
        focused -> ComposerButtonHighlight
        // SmartTxt: at rest the waveform is a bare glyph sitting in the field —
        // OpenBubbles draws no disc behind it. The circle only appears once DPAD
        // focus lands here, which is what marks the selection. Other skins keep
        // the always-filled resting button.
        smarttxt -> Color.Transparent
        else -> ComposerButtonResting
    }
    // ...so on a transparent rest state the white glyph would vanish into the
    // field: tint it grey, the same muted tone the placeholder text uses, so the
    // idle waveform reads as a secondary affordance rather than competing with
    // the blue Send button. It only goes white once it's sitting on a filled disc.
    val iconTint = if (smarttxt && !recording && !focused) colors.mutedText
        else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(InnerActionSize)
            .focusRequester(focusRequester)
            // Show the blue halo while recording too (this is the stop button).
            // Tighter gap than the default: the ring now has to fit within the
            // pill instead of floating in open composer space.
            .dpadFocusRing(focused, ComposerButtonHighlight, ringWidth = 2.dp, gap = 1.dp)
            .clip(CircleShape)
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onDpadAction { onClick(); true }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // While recording, trap focus on Stop — swallow every
                    // direction so the user can't DPAD away mid-recording and
                    // must press OK (stop) to leave.
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight ->
                        if (recording) true
                        else if (event.key == Key.DirectionLeft) { onLeftToField(); true }
                        else false
                    else -> false
                }
            },
    ) {
        Icon(
            // OpenBubbles/iMessage marks "record an audio message" with a
            // waveform, not a mic — GraphicEq is the equalizer-bar glyph that
            // reads the same at 17dp.
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.GraphicEq,
            contentDescription = if (recording) "Stop recording" else "Record voice message",
            tint = iconTint,
            modifier = Modifier.size(InnerActionIcon),
        )
    }
}

/** Replaces the text within the pill while a voice memo is being recorded: a
 *  red dot + the elapsed time. The pill's own shape/fill/border come from the
 *  field container it now sits inside, so this is just the contents. */
@Composable
private fun RecordingIndicator(elapsedSec: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        val m = elapsedSec / 60
        val s = elapsedSec % 60
        Text(
            text = "Recording…  %d:%02d".format(m, s),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** "+" attach button left of the text field — the only control that stays
 *  OUTSIDE the pill, as in OpenBubbles. DPAD: Left exits (back button), Right
 *  returns to the field, OK opens the picker.
 *
 *  It's a permanent light-grey disc with a dark "+", not the old bare glyph that
 *  only grew a circle on focus: OpenBubbles keeps the disc visible at all times,
 *  and on a touch screen an invisible-until-focused button is a poor target.
 *  DPAD focus is marked by the ring instead. */
@Composable
private fun AttachButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .focusRequester(focusRequester)
            .dpadFocusRing(focused, ComposerButtonHighlight, ringWidth = 2.dp, gap = 1.dp)
            .clip(CircleShape)
            .background(if (focused) ComposerButtonNeutral else ComposerAttachBg)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onDpadAction { onClick(); true }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onLeft(); true }
                    Key.DirectionRight -> { onRight(); true }
                    else -> false
                }
            },
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Attach photo or video",
            tint = ComposerAttachGlyph,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Send button, rendered INSIDE the text field's pill at its trailing edge. */
@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onLeftToField: () -> Unit,
    accent: Color = ComposerButtonHighlight,
) {
    var focused by remember { mutableStateOf(false) }
    // Full signal blue as soon as there's text to send; the soft resting blue
    // only shows on an empty field (repos without voice/attach — in Signal the
    // empty field shows the mic instead). DPAD focus is marked by the ring, not
    // a colour change.
    val background = if (enabled) accent else ComposerButtonResting
    val iconTint = if (enabled) Color.White
        else LocalDpadMessengerColors.current.mutedText
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(InnerActionSize)
            .focusRequester(focusRequester)
            .dpadFocusRing(focused, accent, ringWidth = 2.dp, gap = 1.dp)
            .clip(CircleShape)
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onDpadAction { if (enabled) { onClick(); true } else false }
            // DPAD-Left jumps back to the text field so the user doesn't have
            // to round-trip through a Down/Up sequence.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onLeftToField()
                    true
                } else false
            },
    ) {
        Icon(
            // An up-arrow in a filled disc, like iMessage — not a paper plane.
            imageVector = Icons.Filled.ArrowUpward,
            contentDescription = "Send",
            tint = iconTint,
            modifier = Modifier.size(InnerActionIcon),
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
