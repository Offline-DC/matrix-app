package com.offline.dpadspotify.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offline.dpadspotify.spotify.SpotifyManager

/**
 * No-typing login. The screen just narrates where the zeroconf handoff is:
 * advertise → wait for the user's phone → authenticate. There's nothing to
 * focus here, which is fine — the DPAD has nothing to do until Spotify hands
 * us credentials.
 */
@Composable
fun LoginScreen(state: SpotifyManager.State) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Music",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        when (state) {
            is SpotifyManager.State.Starting -> {
                Spinner()
                Caption("Starting up…")
            }

            is SpotifyManager.State.AwaitingHandoff -> {
                Spinner()
                Column(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Step("1.", "Open Spotify on your phone")
                    Step("2.", "Play any song, then tap the devices icon")
                    Step("3.", "Pick “${state.deviceName}”")
                }
                Caption("Both devices must be on the same Wi-Fi")
            }

            is SpotifyManager.State.Authenticating -> {
                Spinner()
                Caption("Logging in…")
            }

            is SpotifyManager.State.Ready -> {
                // Navigation swaps to Search; nothing to render.
            }

            is SpotifyManager.State.Error -> {
                Caption(state.message, error = true)
                Caption("Check Wi-Fi and restart the app")
            }
        }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.padding(top = 20.dp),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Caption(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Step(number: String, text: String) {
    androidx.compose.foundation.layout.Row {
        Text(
            text = number,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
