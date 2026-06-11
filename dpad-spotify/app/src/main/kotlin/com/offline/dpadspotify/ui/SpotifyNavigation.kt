package com.offline.dpadspotify.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.offline.dpadspotify.spotify.SpotifyManager
import com.offline.dpadspotify.ui.login.LoginScreen
import com.offline.dpadspotify.ui.player.NowPlayingScreen
import com.offline.dpadspotify.ui.search.SearchScreen

/**
 * Login → Search ⇄ Now Playing. Mirrors dpad-messenger's navigation style:
 * snap transitions (no fades — DPAD users on tiny screens shouldn't wait
 * through cross-fades) and a flat route table.
 */
@Composable
fun DpadSpotifyApp(
    spotify: SpotifyManager,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    val state by spotify.state.collectAsState()

    // Session state drives navigation: when librespot reports Ready we leave
    // the login screen; if the session dies/logs out we fall back to it.
    LaunchedEffect(state is SpotifyManager.State.Ready) {
        if (state is SpotifyManager.State.Ready) {
            nav.navigate(Routes.SEARCH) { popUpTo(0) { inclusive = true } }
        } else {
            nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.LOGIN,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(state = state)
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                spotify = spotify,
                onOpenNowPlaying = { nav.navigate(Routes.NOW_PLAYING) },
                onLogout = { spotify.logout() },
            )
        }
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(
                spotify = spotify,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

internal object Routes {
    const val LOGIN = "login"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
}
