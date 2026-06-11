package com.offline.dpadspotify.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadspotify.focus.dpadFocusHighlight
import com.offline.dpadspotify.focus.dpadRow
import com.offline.dpadspotify.spotify.SpotifyManager
import com.offline.dpadspotify.spotify.TrackResult
import kotlinx.coroutines.launch

/**
 * Home screen: search field on top, result rows below. The whole screen is a
 * single DPAD column — Down from the field lands on the first result, OK on a
 * row starts playback and opens Now Playing.
 *
 * Search fires on IME action (the keypad's OK while typing) rather than
 * per-keystroke: T9 entry is slow, and burning a Web API call per character
 * would be both chatty and visually noisy.
 */
@Composable
fun SearchScreen(
    spotify: SpotifyManager,
    onOpenNowPlaying: () -> Unit,
    onLogout: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TrackResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val nowPlaying by spotify.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        error = null
        scope.launch {
            try {
                results = spotify.search(q)
                if (results.isEmpty()) error = "No results"
            } catch (e: Exception) {
                error = "Search failed — ${e.message}"
            } finally {
                searching = false
            }
        }
    }

    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: search field + (conditionally) now-playing shortcut + logout.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            SearchInput(
                query = query,
                onQueryChange = { query = it },
                onSearch = ::runSearch,
                focusRequester = fieldFocus,
                modifier = Modifier.weight(1f),
            )
            if (nowPlaying != null) {
                RoundIconButton(
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = "Now playing", tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenNowPlaying,
                )
            }
            RoundIconButton(
                icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Log out", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onLogout,
            )
        }

        when {
            searching -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            error != null -> Text(
                text = error ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            results.isEmpty() -> Text(
                text = "Type a song or artist, then press OK",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.uri }) { track ->
                    TrackRow(
                        track = track,
                        onClick = {
                            spotify.playUri(track.uri)
                            onOpenNowPlaying()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .focusRequester(focusRequester)
            // bg/clip before the highlight so the halo border + tint paint on top.
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .dpadFocusHighlight(shape = RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        onSearch()
                    }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Search songs",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
private fun RoundIconButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .dpadRow(onClick = onClick, shape = CircleShape),
    ) { icon() }
}

@Composable
private fun TrackRow(track: TrackResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .dpadRow(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
