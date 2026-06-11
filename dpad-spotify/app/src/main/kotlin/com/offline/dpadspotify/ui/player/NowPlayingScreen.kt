package com.offline.dpadspotify.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import com.offline.dpadspotify.focus.dpadFocusHighlight
import com.offline.dpadspotify.focus.dpadRow
import com.offline.dpadspotify.focus.onDpadAction
import com.offline.dpadspotify.spotify.SpotifyManager
import kotlinx.coroutines.delay

/**
 * Now Playing. Three focusable controls — Prev / Play-Pause / Next — with the
 * play button focused on entry, so a bare OK press toggles pause. Position is
 * polled once a second (librespot has no position event stream).
 */
@Composable
fun NowPlayingScreen(
    spotify: SpotifyManager,
    onBack: () -> Unit,
) {
    val nowPlaying by spotify.nowPlaying.collectAsState()
    val playFocus = remember { FocusRequester() }
    var positionMs by remember { mutableIntStateOf(0) }

    // Keyed on presence: the requester is only attached when there IS a track
    // (the null branch below returns before the controls row), and calling
    // requestFocus() on an unattached FocusRequester throws.
    LaunchedEffect(nowPlaying != null) {
        if (nowPlaying != null) playFocus.requestFocus()
    }

    LaunchedEffect(nowPlaying?.paused, nowPlaying?.title) {
        while (true) {
            spotify.positionMs()?.let { positionMs = it }
            delay(1000)
        }
    }

    val np = nowPlaying
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (np == null) {
            Text(
                text = "Nothing playing",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            text = if (np.loading) "Loading…" else np.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = np.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Progress + timestamps.
        if (np.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Timestamp(positionMs)
                Timestamp(np.durationMs)
            }
        }

        // Controls. Left/Right on the DPAD walks between them naturally.
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            ControlButton(
                icon = { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = MaterialTheme.colorScheme.onSurface) },
                onClick = { spotify.previous() },
            )
            ControlButton(
                icon = {
                    Icon(
                        imageVector = if (np.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (np.paused) "Play" else "Pause",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                },
                onClick = { spotify.playPause() },
                primary = true,
                focusRequester = playFocus,
            )
            ControlButton(
                icon = { Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = MaterialTheme.colorScheme.onSurface) },
                onClick = { spotify.next() },
            )
        }

        Text(
            text = "Back returns to search",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

@Composable
private fun Timestamp(ms: Int) {
    val totalSec = ms / 1000
    Text(
        text = "%d:%02d".format(totalSec / 60, totalSec % 60),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ControlButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    // The primary (play) button is itself colorScheme.primary, so the default
    // primary-colored focus halo would vanish into it (same trap dpad-messenger
    // documents on its send button). Give it an onSurface halo instead.
    val base = Modifier
        .size(if (primary) 56.dp else 46.dp)
        .clip(CircleShape)
        .background(
            if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        )
    val modifier = if (primary) {
        base
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .dpadFocusHighlight(
                shape = CircleShape,
                borderColor = MaterialTheme.colorScheme.onSurface,
                focusedTint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onDpadAction { onClick(); true }
            .padding(2.dp)
    } else {
        base.dpadRow(onClick = onClick, focusRequester = focusRequester, shape = CircleShape)
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier) { icon() }
}
