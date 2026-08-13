package com.offline.dpadmessenger.focus

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * @param shape the clip/border shape. Defaults to 12dp rounded. Any [Shape] —
 *              not just a [RoundedCornerShape] — so a message bubble can pass
 *              its tailed outline and have the focus border trace the tail too.
 * @param borderWidth focused-state border width.
 * @param focusedTint background tint applied when focused. Pass
 *                    [Color.Unspecified] to skip the tint.
 * @param borderColor color of the focus border. Pass [Color.Unspecified] to
 *                    default to [MaterialTheme.colorScheme.primary]. Override
 *                    on elements that already use the primary color (e.g. the
 *                    send button) so the halo doesn't disappear into the bg.
 */
fun Modifier.dpadFocusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
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
 * A d-pad focus ring: a stroked circle sitting just OUTSIDE this element's
 * bounds — a small [gap], then the ring — drawn only when [focused]. Unlike
 * [dpadFocusHighlight], which borders the shape's own edge, this renders a
 * distinct halo outside a filled button so focus reads clearly on top of a
 * solid fill. Chain it BEFORE any `.clip()` so the outer ring isn't clipped.
 *
 * @param color the ring color (typically the highlight/accent).
 * @param ringWidth stroke width of the ring.
 * @param gap space between the element's edge and the ring.
 */
fun Modifier.dpadFocusRing(
    focused: Boolean,
    color: Color,
    ringWidth: Dp = 3.dp,
    gap: Dp = 2.dp,
): Modifier = drawBehind {
    if (!focused) return@drawBehind
    val stroke = ringWidth.toPx()
    val radius = size.minDimension / 2f + gap.toPx() + stroke / 2f
    drawCircle(color = color, radius = radius, style = Stroke(width = stroke))
}

/**
 * The rounded-rectangle sibling of [dpadFocusRing]: a stroked rounded rect
 * sitting just OUTSIDE this element's bounds, drawn only when [focused]. Use it
 * on wide/pill-shaped focusable elements (buttons, bubbles) where a circular
 * ring would be wrong. Chain it BEFORE any `.clip()`/`.background()` so the
 * outset ring isn't clipped away. Same "halo on top of a solid fill" role the
 * circular ring plays for round buttons.
 *
 * @param color the ring color (typically the accent/highlight).
 * @param cornerRadius corner radius of the element being ringed (the ring's own
 *                     radius is expanded to stay concentric).
 * @param ringWidth stroke width of the ring.
 * @param gap space between the element's edge and the ring.
 */
fun Modifier.dpadFocusRingRect(
    focused: Boolean,
    color: Color,
    cornerRadius: Dp,
    ringWidth: Dp = 3.dp,
    gap: Dp = 2.dp,
): Modifier = drawBehind {
    if (!focused) return@drawBehind
    val stroke = ringWidth.toPx()
    val outset = gap.toPx() + stroke / 2f
    val r = cornerRadius.toPx() + outset
    drawRoundRect(
        color = color,
        topLeft = Offset(-outset, -outset),
        size = Size(size.width + outset * 2f, size.height + outset * 2f),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = stroke),
    )
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.dpadRow(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Press-and-hold action. When null, dpadRow behaves exactly as before
     *  (short press only). When set, touch uses combinedClickable's long-press
     *  and DPAD uses an OK-hold timer: a quick press fires [onClick], holding
     *  past [LONG_PRESS_MS] fires [onLongClick]. */
    onLongClick: (() -> Unit)? = null,
    /** Override the focus-border color. Defaults to the theme primary; pass a
     *  contrasting color (e.g. onPrimary) on a primary-colored element so the
     *  border doesn't vanish into the background. */
    focusBorderColor: Color = Color.Unspecified,
    /** Focus-border width. */
    focusBorderWidth: Dp = 3.dp,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val base = this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .dpadFocusHighlight(shape = shape, borderWidth = focusBorderWidth, borderColor = focusBorderColor)

    if (onLongClick == null) {
        // clickable adds the focus target; onDpadAction lives on top of it so
        // the key handler is attached to the same focused node.
        return base
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .onDpadAction { onClick(); true }
            .padding(2.dp)
    }

    // Long-press variant. Touch: combinedClickable. DPAD has no native
    // long-press, so we time the OK key (mirrors MessageBubble): KeyDown starts
    // a timer that fires onLongClick mid-hold; a KeyUp before it fires onClick.
    val scope = rememberCoroutineScope()
    var pressStarted by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var pressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    return base
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        // Preview-consume the OK key so combinedClickable's own KeyUp handler
        // doesn't ALSO fire onClick for the same press.
        .onPreviewKeyEvent { event ->
            if (event.key !in OkKeys) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        pressStarted = true
                        longFired = false
                        pressJob?.cancel()
                        pressJob = scope.launch {
                            kotlinx.coroutines.delay(LONG_PRESS_MS)
                            longFired = true
                            // Claim the OK key so held-key auto-repeats don't
                            // fire an action on the just-opened menu.
                            DpadFireGate.tryAcquire(event.key)
                            onLongClick()
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    pressJob?.cancel(); pressJob = null
                    DpadFireGate.release(event.key)
                    val startedHere = pressStarted
                    pressStarted = false
                    if (startedHere && !longFired) onClick()
                    true
                }
                else -> false
            }
        }
        .padding(2.dp)
}

/** DPAD OK-hold threshold for [dpadRow]'s long-press; matches MessageBubble. */
const val LONG_PRESS_MS: Long = 400L

/** Standard DPAD key set we treat as "OK / select". */
val OkKeys: Set<Key> = setOf(Key.Enter, Key.DirectionCenter, Key.NumPadEnter)

/** Returns true if [Key] is a DPAD directional key. */
val Key.isDpadDirection: Boolean
    get() = this == Key.DirectionUp || this == Key.DirectionDown ||
            this == Key.DirectionLeft || this == Key.DirectionRight


/**
 * Hand focus to [target] in response to a d-pad press, and report whether the key
 * should be CONSUMED.
 *
 * ## Why this exists
 *
 * The obvious way to write a "Down from the toolbar drops into the list" handler is to
 * request focus and return true. On this hardware that is a trap. A [FocusRequester]
 * throws when no node is currently attached to it, and the targets these handlers aim
 * at are rows inside a `LazyColumn` — which disposes anything scrolled out of view, and
 * re-binds row 0's requester every time an arriving message re-sorts the list. So the
 * request fails, `runCatching` swallows the exception, the key has ALREADY been
 * consumed, and focus stays exactly where it was. Press again: identical. The user is
 * stranded on a toolbar button with no way into the list, which on a phone with no
 * touchscreen is unrecoverable — the only exit is backing out of the screen.
 *
 * The rule that prevents it: **never consume a key you did not act on.**
 *
 *  1. Try synchronously. If focus moved, consume it — the common case, unchanged.
 *  2. If it did not, run [prepare] (e.g. scroll the target back into composition so its
 *     requester can bind), retry for [retryFrames] frames, and finally fall back to
 *     [FocusManager.moveFocus] in [fallback].
 *  3. Return false either way, so the platform's own focus search ALSO sees the event.
 *     That is the belt-and-braces part: even if our requester never binds, default
 *     traversal still moves the user somewhere, and a dead end becomes impossible as
 *     long as anything below is focusable.
 *
 * The cost of (3) is that focus can occasionally move twice — the platform's search
 * lands somewhere, then our retry pulls it to [target]. A visible hop in a rare case,
 * traded against a dead end in a rare case. Not a close call.
 */
fun CoroutineScope.handOffFocus(
    target: FocusRequester,
    focusManager: FocusManager,
    fallback: FocusDirection,
    retryFrames: Int = 12,
    /** DIAGNOSTIC (temporary): when set, narrate the hand-off under [DPAD_FOCUS_TAG]. */
    label: String? = null,
    prepare: (suspend () -> Unit)? = null,
): Boolean {
    if (runCatching { target.requestFocus() }.isSuccess) {
        if (label != null) Log.d(DPAD_FOCUS_TAG, "$label: moved immediately")
        return true
    }
    if (label != null) Log.d(DPAD_FOCUS_TAG, "$label: requester UNATTACHED, retrying")
    launch {
        runCatching { prepare?.invoke() }
        repeat(retryFrames) { i ->
            withFrameNanos {}
            if (runCatching { target.requestFocus() }.isSuccess) {
                if (label != null) Log.d(DPAD_FOCUS_TAG, "$label: bound after ${i + 1} frame(s)")
                return@launch
            }
        }
        val moved = runCatching { focusManager.moveFocus(fallback) }.getOrDefault(false)
        if (label != null) {
            Log.d(DPAD_FOCUS_TAG, "$label: never bound after $retryFrames frames; " +
                "moveFocus($fallback)=$moved  <-- if false, the user is stranded")
        }
    }
    return false
}

/** DIAGNOSTIC (temporary): logcat tag for the d-pad focus probe. */
const val DPAD_FOCUS_TAG: String = "DpadFocus"
