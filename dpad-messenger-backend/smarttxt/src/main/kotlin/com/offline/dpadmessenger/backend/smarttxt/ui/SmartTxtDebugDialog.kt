package com.offline.dpadmessenger.backend.smarttxt.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtLogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SmartTxtDebug"

/**
 * Hidden diagnostics, revealed by toggling 24-hour time ten times in a row.
 *
 * WHY THIS EXISTS. A terminal IDS 6005 cannot be provoked on demand - Apple decides
 * when to invalidate a registration. Shipping to more users does not exercise it
 * either, because a user who signs in successfully never produces one. So the routing
 * that follows a 6005 (transport -> RegistrationFailed -> onTerminalRegistrationFailure
 * -> signOutKeepingHistory -> sign-in screen) can only be tested by injecting the state
 * rustpush would have published.
 *
 * WHY IT IS SAFE TO SHIP. The injection call takes a fixed VARIANT NAME, not arbitrary
 * JSON - see RustPushNative.nativeDebugInjectRegState. And there is no broadcast
 * receiver, so the only way here is physically navigating Settings; adb alone cannot
 * reach it.
 *
 * Everything writes a DEBUG_ACTION line, and injected states are logged as SYNTHETIC,
 * so nobody reading an exported bundle later mistakes one for something Apple sent.
 */
@Composable
internal fun SmartTxtDebugDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var output by remember {
        mutableStateOf("Pick an action. Results are logged, so Export logs picks them up.")
    }
    var armedTerminal by remember { mutableStateOf(false) }

    fun show(text: String) {
        Log.w(TAG, "DEBUG_ACTION result: $text")
        output = text
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("Smart Txt diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Hidden build tools. Everything here is written to the log ring - " +
                        "run what you need, then Export logs.",
                    style = MaterialTheme.typography.bodySmall,
                )

                DebugRow("Dump registration state") {
                    scope.launch {
                        val s = withContext(Dispatchers.IO) {
                            RustPushNative.runCatchingNativeRegisterState()
                        }
                        show("regstate: $s")
                    }
                }

                DebugRow("Dump registered handles") {
                    scope.launch {
                        val s = withContext(Dispatchers.IO) {
                            RustPushNative.runCatchingNativeHandles()
                        }
                        show("handles: $s")
                    }
                }

                // ---- the negative test. Run this one FIRST. ----------------------
                DebugRow("Inject TRANSIENT failure (must NOT sign you out)") {
                    Log.w(TAG, "DEBUG_ACTION: injecting synthetic TRANSIENT registration failure")
                    runCatching { RustPushNative.nativeDebugInjectRegState("transient") }
                        .onSuccess {
                            show(
                                "Injected. Expected: REGSTATE(push) failed needsRelogin=false, a " +
                                    "warning line, and you stay signed in. If this signs you out, " +
                                    "every transient network fault is dumping users to the login " +
                                    "screen.",
                            )
                        }
                        .onFailure { show("inject failed: $it") }
                }

                // ---- the positive test. Two-step, because it signs you out. ------
                if (!armedTerminal) {
                    DebugRow("Inject TERMINAL 6005 - signs you out") {
                        armedTerminal = true
                        show(
                            "Armed. Select again to confirm. This really does sign you out; " +
                                "history is kept and you will need one real sign-in to get back.",
                        )
                    }
                } else {
                    DebugRow("CONFIRM: inject terminal 6005 and sign out") {
                        armedTerminal = false
                        Log.w(TAG, "DEBUG_ACTION: injecting synthetic TERMINAL 6005")
                        runCatching { RustPushNative.nativeDebugInjectRegState("terminal") }
                            .onSuccess {
                                show("Injected. Expected: REGSTATE FAILED TERMINAL, then sign-in.")
                            }
                            .onFailure { show("inject failed: $it") }
                    }
                    DebugRow("Cancel") {
                        armedTerminal = false
                        show("Cancelled.")
                    }
                }

                DebugRow("Export logs now") {
                    Toast.makeText(context, "Exporting...", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            SmartTxtLogExporter.export(context.applicationContext)
                        }
                        show(r.fold({ "exported, reference: $it" }, { "export failed: ${it.message}" }))
                    }
                }

                DebugRow("Close") { onDismiss() }

                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun DebugRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    )
}
