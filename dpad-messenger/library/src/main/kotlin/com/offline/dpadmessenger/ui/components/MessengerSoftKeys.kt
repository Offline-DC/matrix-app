package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bridge that lets messenger screens publish soft-key labels (left / center / right) to
 * the host's real system nav bar. The launcher host provides a controller backed by the
 * TCL android.widget.MenuBar (white text); when it's active, [MessengerSoftKeys] draws
 * nothing and just publishes. On any other host (the standalone demo, emulator) the
 * controller is null and a plain fallback bar is drawn instead.
 */
interface MessengerSoftKeyController {
    /** True when a native system nav bar is driving the soft keys. */
    val active: Boolean

    /** Publish the current screen's three labels to the native bar. */
    fun set(left: String?, center: String?, right: String?)

    /** Show/hide the whole bar (used to hide it on screens with no soft-key actions). */
    fun setVisible(visible: Boolean)
}

val LocalMessengerSoftKeys = staticCompositionLocalOf<MessengerSoftKeyController?> { null }

@Composable
fun MessengerSoftKeys(
    leftLabel: String? = null,
    centerLabel: String? = null,
    rightLabel: String? = null,
) {
    val controller = LocalMessengerSoftKeys.current
    if (controller != null && controller.active) {
        LaunchedEffect(leftLabel, centerLabel, rightLabel) {
            controller.set(leftLabel, centerLabel, rightLabel)
        }
        return
    }

    // Fallback (non-native host): a simple white-on-black soft-key row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val base = TextStyle(color = Color.White, fontSize = 12.sp)
        BasicText(leftLabel.orEmpty(), Modifier.weight(1f), style = base.copy(textAlign = TextAlign.Start))
        BasicText(centerLabel.orEmpty(), Modifier.weight(1f), style = base.copy(textAlign = TextAlign.Center))
        BasicText(rightLabel.orEmpty(), Modifier.weight(1f), style = base.copy(textAlign = TextAlign.End))
    }
}

/**
 * Hides the messenger soft-key bar while composed (restores it on dispose). Use on screens
 * with no soft-key actions — e.g. the Settings page — so a stale "new / settings" bar
 * (whose keys do nothing there) isn't shown.
 */
@Composable
fun HideMessengerSoftKeys() {
    val controller = LocalMessengerSoftKeys.current ?: return
    DisposableEffect(Unit) {
        controller.setVisible(false)
        onDispose { controller.setVisible(true) }
    }
}
