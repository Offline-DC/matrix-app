package com.offline.dpadmessenger.backend.smarttxt.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtLogExporter
import com.offline.dpadmessenger.ui.components.DpadButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A dpad-navigable "Report error" button. Ships this device's Smart Txt logs to
 * support via the exact same capture + upload as Settings -> "Export logs"
 * ([SmartTxtLogExporter]) — so when a user is stuck on a sign-in / connection
 * error, support can pull their Smart Txt logs on the spot, without walking them
 * through turning on the launcher diagnostics "rolling adb logs".
 *
 * Self-contained: grabs its own context + coroutine scope, shows in-button
 * loading while uploading (DpadButton swallows re-taps during [loading]), and
 * Toasts the result (server reference on success). Drop it directly under an
 * error message; it's a neutral/secondary button so the primary action
 * (Retry / Sign in) stays visually dominant.
 */
@Composable
internal fun ReportErrorButton(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf(false) }
    DpadButton(
        text = "Report error",
        onClick = {
            if (!reporting) {
                reporting = true
                Toast.makeText(context, "Reporting error to support...", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        SmartTxtLogExporter.export(context.applicationContext)
                    }
                    reporting = false
                    val msg = result.fold(
                        onSuccess = { ref -> "Report sent to support. Reference: $ref" },
                        onFailure = { e -> "Couldn't send report: ${e.message ?: "unknown error"}" },
                    )
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        },
        loading = reporting,
        primary = false,
        modifier = modifier,
        focusRequester = focusRequester,
    )
}
