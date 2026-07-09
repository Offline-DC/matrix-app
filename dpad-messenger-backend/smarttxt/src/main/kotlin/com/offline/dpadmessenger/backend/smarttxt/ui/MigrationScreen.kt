package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.util.Log
import com.offline.dpadmessenger.backend.smarttxt.OpenBubblesMigrator
import com.offline.dpadmessenger.backend.smarttxt.R

/**
 * "Transferring to new Smart Txt" screen — shown on first launch when the user
 * isn't signed in yet but a logged-in OpenBubbles is present to migrate from
 * (see [OpenBubblesMigrator.available]).
 *
 * The transfer runs in two phases and has three outcomes:
 *  - **Device identity fails** (couldn't copy/stage the `dumb` + os_config, which
 *    NAC validation needs and there's no relay fallback) → shows a failure with a
 *    "Try again" button. There's no usable path, so we do NOT drop to sign-in.
 *  - **Identity OK, but the OpenBubbles login can't be reused** → calls
 *    [onFallbackToSetup] so the user signs in manually; that sign-in still validates
 *    through the NAC server using the staged dumb.
 *  - **Full success** → the migrator flips
 *    [com.offline.dpadmessenger.backend.smarttxt.SmartTxtRepository.status] to
 *    REGISTERED, so the host recomposes straight into the chats (nothing to do here).
 */
@Composable
fun MigrationScreen(
    modifier: Modifier = Modifier,
    onFallbackToSetup: () -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf("Setting things up…") }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        failure = null
        step = "Setting things up…"
        Log.i("ObMigrator", "MigrationScreen shown → starting transfer (attempt ${attempt + 1})")
        val result = OpenBubblesMigrator.migrate(context) { step = it }
        when {
            // Full success: migrate() already stamped REGISTERED + flipped status via
            // markRegisteredExternally, so the host swaps to the chats — nothing to do.
            result.ok && result.registered ->
                Log.i("ObMigrator", "transfer OK (${result.handles.size} handles) → chats")

            // Device identity is staged, but the OpenBubbles login couldn't be reused.
            // Send the user to manual sign-in — it validates via the NAC server + dumb.
            result.ok -> {
                Log.w("ObMigrator", "identity staged; OB login not reused → manual sign-in")
                onFallbackToSetup()
            }

            // Hard failure: the device identity (dumb/os_config) couldn't be staged, so
            // there's no way to validate. Show a failure the user can retry.
            else -> {
                Log.w("ObMigrator", "transfer FAILED (device identity) → failure screen: ${result.error}")
                failure = result.error ?: "The transfer didn't complete."
            }
        }
    }

    MaterialTheme {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.duck_logo),
                    contentDescription = null,
                    modifier = Modifier.size(140.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(28.dp))

                val err = failure
                if (err == null) {
                    Text(
                        text = "Transferring to new Smart Txt",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = "Couldn't transfer from OpenBubbles",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
            }
        }
    }
}
