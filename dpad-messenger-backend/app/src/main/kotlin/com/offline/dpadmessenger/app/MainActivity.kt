package com.offline.dpadmessenger.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            DpadMessengerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val s = vm.state.collectAsState().value) {
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
    // When the user is in Mock mode and wants to link Signal, the demo
    // currently routes through a tiny header bar above the main UI. This
    // is intentionally minimal — once Signal mode is live, the user lives
    // entirely inside DpadMessengerApp and the mode-switch UI moves into
    // Settings (TODO).
    if (state.mode == BackendMode.Mock) {
        Column(modifier = Modifier.fillMaxSize()) {
            MockModeBanner(onLinkSignal = vm::linkSignalDevice)
            DpadMessengerApp(repository = state.repository, modifier = Modifier.weight(1f))
        }
    } else {
        DpadMessengerApp(repository = state.repository)
    }
}

@Composable
private fun MockModeBanner(onLinkSignal: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Mock data — link Signal to use your real account:",
                style = MaterialTheme.typography.labelSmall,
            )
            Button(onClick = onLinkSignal, modifier = Modifier.fillMaxSize().padding(top = 2.dp)) {
                Text("Link Signal device")
            }
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
