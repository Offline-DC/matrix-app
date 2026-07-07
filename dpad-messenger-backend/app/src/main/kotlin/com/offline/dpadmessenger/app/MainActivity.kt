package com.offline.dpadmessenger.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme

/**
 * Single Activity. Branches on [RepoState]:
 *   - Loading         → spinner
 *   - Ready           → DpadMessengerApp bound to the repo
 *   - LinkingSignal   → QR provisioning screen
 *   - Error           → error text + "Use mock data" button so the user
 *                       can always recover into a working UI
 */
class MainActivity : AppCompatActivity() {

    private val vm: AppViewModel by viewModels { AppViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val s = vm.state.collectAsState().value
            // Use the SmartTxt/BlueBubbles skin whenever the SmartTxt backend is
            // the active conversation source; Signal/Mock keep the default look.
            val smarttxt = (s as? RepoState.Ready)?.mode == BackendMode.SmartTxt
            DpadMessengerTheme(smarttxt = smarttxt) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (s) {
                        RepoState.Loading -> LoadingScreen()
                        is RepoState.Ready -> ReadyScreen(s, vm)
                        is RepoState.LinkingSignal -> SignalLinkScreen(s.provisioning)
                        is RepoState.Error -> ErrorScreen(s.message, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReadyScreen(state: RepoState.Ready, vm: AppViewModel) {
    // In Mock mode a small header lets the user connect a real account (Signal
    // or the SmartTxt relay). Once a real backend is live the user lives entirely
    // inside the shared DpadMessengerApp UI (same UI for Signal and SmartTxt).
    if (state.mode == BackendMode.Mock) {
        var showSmarttxtConnect by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxSize()) {
            if (showSmarttxtConnect) {
                SmartTxtConnectScreen(
                    onConnect = { url, token -> vm.useSmartTxtBackend(url, token) },
                    onCancel = { showSmarttxtConnect = false },
                )
            } else {
                ModeBanner(
                    onLinkSignal = vm::linkSignalDevice,
                    onConnectSmartTxt = { showSmarttxtConnect = true },
                )
            }
            DpadMessengerApp(repository = state.repository, modifier = Modifier.weight(1f))
        }
    } else {
        // Signal AND SmartTxt both render through the shared Signal-like UI.
        DpadMessengerApp(repository = state.repository)
    }
}

@Composable
private fun ModeBanner(onLinkSignal: () -> Unit, onConnectSmartTxt: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Mock data — connect a real account:",
            style = MaterialTheme.typography.labelSmall,
        )
        Button(onClick = onLinkSignal, modifier = Modifier.fillMaxWidth()) {
            Text("Link Signal device")
        }
        Button(onClick = onConnectSmartTxt, modifier = Modifier.fillMaxWidth()) {
            Text("Connect smart txt relay")
        }
    }
}

@Composable
private fun SmartTxtConnectScreen(
    onConnect: (String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Connect to your smart txt relay daemon. It handles the dumb file, " +
                "iCloud login and 2FA (OpenBubbles-style); this phone is a thin client.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Relay URL — e.g. http://192.168.1.10:8765") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bearer token (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onConnect(url, token.ifBlank { null }) },
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ErrorScreen(message: String, vm: AppViewModel) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = vm::useMockBackend, modifier = Modifier.padding(top = 12.dp)) {
                Text("Use mock data")
            }
        }
    }
}
