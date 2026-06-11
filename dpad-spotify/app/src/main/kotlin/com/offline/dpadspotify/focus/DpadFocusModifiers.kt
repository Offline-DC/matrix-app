package com.offline.dpadspotify.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Modifiers and helpers for DPAD-driven UIs.
 *
 * Phones don't draw a focus halo by default — when the user is steering with a
 * hardware DPAD they have no idea what is selected unless we render it. These
 * helpers make focus visible and predictable everywhere it can land.
 */

/**
 * Draws a thick rounded border + tint when focused. Use on any element the
 * DPAD can land on: list rows, message bubbles, buttons, the text field.
 *
 * @param shape the clip/border shape. Defaults to 12dp rounded.
 * @param borderWidth focused-state border width.
 * @param focusedTint background tint applied when focused. Pass
 *                    [Color.Unspecified] to skip the tint.
 * @param borderColor color of the focus border. Pass [Color.Unspecified] to
 *                    default to [MaterialTheme.colorScheme.primary]. Override
 *                    on elements that already use the primary color (e.g. the
 *                    send button) so the halo doesn't disappear into the bg.
 */
fun Modifier.dpadFocusHighlight(
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    borderWidth: Dp = 3.dp,
    focusedTint: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val effectiveBorder = if (borderColor.isUnspecified) accent else borderColor
    val tint = if (focusedTint.isUnspecified) accent.copy(alpha = 0.12f) else focusedTint
    this
        .clip(shape)
        .then(if (focused) Modifier.background(tint) else Modifier)
        .border(
            width = if (focused) borderWidth else 0.dp,
            color = if (focused) effectiveBorder else Color.Transparent,
            shape = shape,
        )
        // hasFocus (not isFocused) so wrapping a focusable child — e.g. a
        // BasicTextField inside a styled Box — still draws the highlight on
        // the wrapper.
        .onFocusChanged { focused = it.hasFocus }
}

/**
 * Intercepts a DPAD key press and runs [onPress]. Returns true (consuming
 * the event) when [onPress] returns true.
 *
 * **One-action-per-press semantics.** Fires on [KeyEventType.KeyDown] (so
 * the action feels instant on a hardware DPAD — no waiting for release) but
 * routes through a process-wide [DpadFireGate] that blocks all subsequent
 * KeyDowns for the same key until a KeyUp arrives. This catches two
 * separate cascade sources:
 *   1. Android's normal auto-repeat (filtered by `repeatCount != 0`).
 *   2. Focus-changes mid-press: if [onPress] navigates to a new screen,
 *      the next focused element receives a fresh KeyDown for the still-held
 *      key. Without the gate, that element would fire its own action — and
 *      so on, "deeper into things" per device testing on the TCL Flip 2.
 *
 * @param keys the set of keys this handler reacts to. Defaults to [OkKeys]
 *             (Enter / DPAD-Center / NumPadEnter). [androidx.compose.ui.input.key.Key]
 *             is a value class so we can't accept it as a vararg.
 */
fun Modifier.onDpadAction(
    keys: Set<Key> = OkKeys,
    onPress: () -> Boolean,
): Modifier = onKeyEvent { event ->
    if (event.key !in keys) return@onKeyEvent false
    when (event.type) {
        KeyEventType.KeyDown -> {
            // Filter Android-standard repeats first as a fast path.
            if (event.nativeKeyEvent.repeatCount != 0) return@onKeyEvent true
            if (!DpadFireGate.tryAcquire(event.key)) {
                // Gate held — a previous KeyDown already fired an action for
                // this key. Consume the event but don't fire again.
                return@onKeyEvent true
            }
            onPress()
        }
        KeyEventType.KeyUp -> {
            DpadFireGate.release(event.key)
            // Consume KeyUp too. Compose's Modifier.clickable has its own
            // keyboard handler that fires onClick on KeyUp for Enter / DPAD-
            // Center / Space. Without consuming here, releasing a key after
            // a press-and-hold that opened (say) a context sheet would land
            // an extra click on whatever element is now focused — like the
            // first reaction chip. We've already fired the intended action
            // on KeyDown via the gate; KeyUp has no work left to do.
            true
        }
        else -> false
    }
}

/**
 * Process-wide gate for one-action-per-press DPAD handling.
 *
 * Use case: the user holds the OK key. KeyDown fires, the action navigates
 * to a new screen, and Android dispatches subsequent KeyDown events from
 * the same physical press to whichever element holds focus next. Without
 * coordination, that next element fires its own action — chains of
 * "open chat → open sheet → react" happen from a single press-and-hold.
 *
 * The gate solves it: any [onDpadAction] modifier that fires acquires the
 * key globally. Until [release] is called (on KeyUp) or [maxHoldMs] elapses
 * (safety net for KeyUp events lost to focus changes), no other handler
 * can fire for the same key.
 */
internal object DpadFireGate {
    private val lock = Any()
    private val heldKeyCodes = mutableSetOf<Int>()
    private var expireAtMs: Long = 0L
    /** Auto-release after this long — protects against KeyUp delivered to a
     *  view that doesn't process it (e.g. BasicTextField). */
    private const val maxHoldMs: Long = 2_000L

    /** @return true if the caller should fire the action. */
    fun tryAcquire(key: Key): Boolean {
        val code = key.nativeKeyCode
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now > expireAtMs) heldKeyCodes.clear()
            if (code in heldKeyCodes) return false
            heldKeyCodes.add(code)
            expireAtMs = now + maxHoldMs
            return true
        }
    }

    fun release(key: Key) {
        val code = key.nativeKeyCode
        synchronized(lock) { heldKeyCodes.remove(code) }
    }
}

/**
 * Combines focus request, focus highlight, and a clickable-style press handler.
 * Saves re-wiring the same three modifiers on every list row.
 *
 * @param onClick fired on touch or DPAD-OK.
 * @param focusRequester optional — pass one if you need to programmatically focus
 *                       this element (e.g. when a screen first opens).
 * @param shape the clip/highlight shape.
 */
@Composable
fun Modifier.dpadRow(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .dpadFocusHighlight(shape = shape)
        // clickable adds the focus target; onDpadAction lives on top of it so
        // the key handler is attached to the same focused node.
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
        .onDpadAction { onClick(); true }
        .padding(2.dp)
}

/** Standard DPAD key set we treat as "OK / select". */
val OkKeys: Set<Key> = setOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter)

/** Returns true if [Key] is a DPAD directional key. */
val Key.isDpadDirection: Boolean
    get() = this == Key.DirectionUp || this == Key.DirectionDown ||
            this == Key.DirectionLeft || this == Key.DirectionRight
