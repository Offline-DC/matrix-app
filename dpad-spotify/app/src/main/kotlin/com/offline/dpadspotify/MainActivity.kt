package com.offline.dpadspotify

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.offline.dpadspotify.ui.DpadSpotifyApp
import com.offline.dpadspotify.ui.theme.DpadSpotifyTheme

/**
 * Entry point. AppCompatActivity (not ComponentActivity) on purpose — see
 * dpad-messenger: newer androidx.activity auto-enables edge-to-edge which
 * draws a black nav-bar band on the TCL Flip 2.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spotify = (application as SpotifyApp).spotify
        setContent {
            DpadSpotifyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DpadSpotifyApp(spotify = spotify)
                }
            }
        }
    }
}
