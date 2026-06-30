package com.offline.dpadmessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Play/pause control for a voice memo: a round play button, a progress bar, and
 * an elapsed/total time label. Used both in the record preview modal (local
 * file) and on a received message bubble (downloads on first play).
 *
 * @param localPath the audio file path, or null if not downloaded yet.
 * @param onNeedDownload called when play is pressed and [localPath] is null;
 *        should fetch the file and return its local path (or null on failure).
 */
@Composable
fun VoiceMemoPlayer(
    localPath: String?,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    onColor: Color = MaterialTheme.colorScheme.onPrimary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    focusRequester: FocusRequester? = null,
    onNeedDownload: (suspend () -> String?)? = null,
) {
    val scope = rememberCoroutineScope()
    var path by remember(localPath) { mutableStateOf(localPath) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { runCatching { player?.release() } }
    }

    fun startPlaying(p: String) {
        runCatching {
            player?.release()
            val mp = android.media.MediaPlayer()
            mp.setDataSource(p)
            mp.prepare()
            durationMs = mp.duration.coerceAtLeast(0)
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
            }
            mp.start()
            player = mp
            isPlaying = true
        }.onFailure {
            isPlaying = false
        }
    }

    fun toggle() {
        val mp = player
        when {
            isPlaying && mp != null -> { runCatching { mp.pause() }; isPlaying = false }
            mp != null && !isPlaying && path != null -> { runCatching { mp.start() }; isPlaying = true }
            path != null -> startPlaying(path!!)
            onNeedDownload != null && !loading -> {
                loading = true
                scope.launch {
                    val fetched = runCatching { onNeedDownload() }.getOrNull()
                    loading = false
                    if (fetched != null) { path = fetched; startPlaying(fetched) }
                }
            }
        }
    }

    // Tick the position while playing.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(200)
        }
    }

    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (isPlaying || positionMs > 0) positionMs else durationMs

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clip(CircleShape)
                .background(accent)
                .dpadFocusHighlight(shape = CircleShape, borderColor = onColor)
                .focusable()
                .onDpadAction { toggle(); true },
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = onColor, strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = onColor,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        LinearProgressIndicator(
            progress = { fraction },
            color = accent,
            trackColor = trackColor,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(formatClock(shownMs), style = MaterialTheme.typography.labelMedium)
    }
}

/** mm:ss for a millisecond duration. */
internal fun formatClock(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
