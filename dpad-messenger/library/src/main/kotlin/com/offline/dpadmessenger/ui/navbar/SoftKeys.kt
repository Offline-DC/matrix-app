package com.offline.dpadmessenger.ui.navbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The messenger's soft-key bar — the two hardware buttons under a dumb phone's
 * screen, and the labels that say what they do.
 *
 * ## Why this isn't just the launcher's NavBar
 *
 * The launcher drives the TCL handsets' real system bar (`android.widget.MenuBar`,
 * see `ui/navbar/NavBar.kt` over there) and every launcher screen declares its
 * keys against it. This library can't call any of that: the dependency runs
 * launcher → dpad-messenger-backend → here, so the code that owns the bar sits
 * *downstream* of the screens that need it.
 *
 * So the library declares the shape it needs — [SoftKeys] for screens, and
 * [SoftKeyBarHost] for whoever is hosting them — and the host plugs its real bar
 * in through [LocalSoftKeyBar]. The launcher's adapter is a dozen lines. A host
 * that provides nothing (the demo app, previews) gets the in-app row below, so
 * screens look and behave the same either way and nothing has to branch.
 *
 * ## Using it
 *
 * ```kotlin
 * SoftKeys(
 *     left = SoftKey("settings") { onSettingsClick() },
 *     right = SoftKey("new") { onNewMessage() },
 * )
 * ```
 *
 * Label and behaviour in one place. The keys are published while the screen is
 * composed and taken down when it leaves.
 *
 * ## The centre key
 *
 * Give `center` a label and no action. The hardware centre is DPAD_CENTER, which
 * every list uses to activate its selection — a bar that intercepted it would
 * break all of them. The label is a hint for the key the screen already handles.
 */

/** One soft key: the label the bar shows, and what pressing it does. */
data class SoftKey(
    val label: String,
    val onPress: (() -> Unit)? = null,
)

/**
 * A screen's claim on the bar, held for as long as that screen is composed.
 * Created by [SoftKeys]; screens never build one themselves.
 *
 * @param labels left / centre / right, any of them null for an empty slot.
 * @param action the lambda for position 0/1/2, or null when that key does nothing
 *        — a key with no action must fall through to the screen untouched.
 */
class SoftKeyBinding(
    val labels: List<String?>,
    val action: (Int) -> (() -> Unit)?,
)

/**
 * Implemented by whatever is hosting these screens, to put [SoftKeyBinding]s on a
 * real bar.
 *
 * Hosts are expected to handle one subtlety: when one screen replaces another,
 * Compose disposes the outgoing binding BEFORE the incoming one binds, so a host
 * that blanks the bar the moment it's released will blank it on every navigation.
 * Defer the clear by a frame and skip it if something else has claimed the bar
 * (the launcher's `NavBarHost` already does exactly this).
 */
interface SoftKeyBarHost {
    /** True when a real system bar is showing the labels, so don't draw one. */
    val nativeActive: Boolean

    fun bind(binding: SoftKeyBinding)
    fun unbind(binding: SoftKeyBinding)
}

/** Set by the host. Null means no real bar — [SoftKeys] draws the in-app row. */
val LocalSoftKeyBar = staticCompositionLocalOf<SoftKeyBarHost?> { null }

/**
 * Declare this screen's soft keys.
 *
 * On a host with a real bar this publishes the labels there and lays out nothing,
 * because the OS draws that bar below our content. Everywhere else it draws
 * [SoftKeyBarContent] in place — so put it where the bar belongs, last in the
 * screen's `Column`.
 */
@Composable
fun SoftKeys(
    left: SoftKey? = null,
    center: SoftKey? = null,
    right: SoftKey? = null,
    modifier: Modifier = Modifier,
) {
    val host = LocalSoftKeyBar.current
    if (host != null) {
        BindSoftKeys(host, left, center, right)
        if (host.nativeActive) return
    }
    SoftKeyBarContent(left, center, right, modifier)
}

/**
 * Hold the bar's labels + actions for as long as the caller is composed.
 *
 * Keyed on the LABELS, not the [SoftKey]s: the lambdas are rebuilt on nearly every
 * recomposition and would otherwise re-bind constantly, and on the launcher's bar a
 * re-bind costs a firmware redraw. The actions are read through
 * [rememberUpdatedState] so a press always runs the current one.
 */
@Composable
private fun BindSoftKeys(
    host: SoftKeyBarHost,
    left: SoftKey?,
    center: SoftKey?,
    right: SoftKey?,
) {
    val latest = rememberUpdatedState(listOf(left, center, right))
    DisposableEffect(host, left?.label, center?.label, right?.label) {
        val binding = SoftKeyBinding(
            labels = listOf(left?.label, center?.label, right?.label),
            action = { position -> latest.value.getOrNull(position)?.onPress },
        )
        host.bind(binding)
        onDispose { host.unbind(binding) }
    }
}

/**
 * The in-app bar itself, for hosts with no system bar to publish to — the demo
 * app, previews, any non-TCL build.
 *
 * Deliberately the launcher's soft-key row with one value changed: same 12sp, same
 * 10dp/4dp padding, same black strip, same 65% alpha — white instead of the
 * launcher's brand yellow, because the messengers use white chrome throughout.
 * (On a TCL handset this never draws; the launcher's host sets the real bar's
 * colour and typeface to match.)
 */
@Composable
internal fun SoftKeyBarContent(
    left: SoftKey?,
    center: SoftKey?,
    right: SoftKey?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftKeyLabel(left, TextAlign.Start)
        SoftKeyLabel(center, TextAlign.Center)
        SoftKeyLabel(right, TextAlign.End)
    }
}

/**
 * One slot. Blank slots still take their third of the row, so the other two labels
 * don't slide around as a screen's keys change.
 *
 * Tappable. On a handset that never matters — this row isn't drawn at all, the OS
 * bar is — but off the handsets these labels are the only route to their actions.
 * The room list makes that concrete: "new" replaced a floating compose button, so
 * without a tap target here the demo app and the emulator would have no way to
 * start a conversation.
 */
@Composable
private fun RowScope.SoftKeyLabel(key: SoftKey?, align: TextAlign) {
    val press = key?.onPress
    BasicText(
        text = key?.label.orEmpty(),
        modifier = Modifier
            .weight(1f, fill = true)
            .then(if (press != null) Modifier.clickable { press() } else Modifier),
        style = TextStyle(
            color = SOFT_KEY_WHITE,
            fontSize = 12.sp,
            textAlign = align,
        ),
        maxLines = 1,
    )
}

/** White at 65% — the messengers' answer to the launcher's dimmed brand yellow. */
private val SOFT_KEY_WHITE = Color.White.copy(alpha = 0.65f)
