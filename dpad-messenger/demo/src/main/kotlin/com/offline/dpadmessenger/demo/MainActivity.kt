package com.offline.dpadmessenger.demo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.offline.dpadmessenger.ui.DpadMessengerApp
import com.offline.dpadmessenger.ui.theme.DpadMessengerTheme

/**
 * Demo entry point. Loads the mock repository from assets/mock_data.json,
 * spins up a coroutine that occasionally simulates an incoming message, and
 * hands control to [DpadMessengerApp].
 */
class MainActivity : AppCompatActivity() {

    private val demoVm: DemoViewModel by viewModels {
        DemoViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
            DpadMessengerTheme(darkTheme = darkOverride) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val repo by demoVm.repository.collectAsState()
                    val current = repo
                    if (current != null) {
                        DpadMessengerApp(
                            repository = current,
                            darkTheme = darkOverride == true,
                            onToggleDarkTheme = { darkOverride = it },
                        )
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        demoVm.startSimulatedTraffic()
    }
}

class DemoViewModelFactory(
    private val appContext: android.content.Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DemoViewModel(appContext) as T
    }
}
