package com.offline.dpadmessenger.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.core.content.ContextCompat
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.focus.onDpadAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fullscreen, DPAD-navigable photo / video picker. Replaces the system
 * "Photos" picker (which isn't keyboard/DPAD friendly on flip phones like the
 * TCL Flip 2) with an in-app grid of the device's recent images and videos.
 *
 * Flow:
 *  - On open, requests the media-read permission if not already held.
 *  - Shows a grid of thumbnails (newest first). DPAD moves between cells; OK
 *    sends the highlighted item via [onPick] (a content:// uri string that the
 *    repository's AttachmentSender reads + uploads).
 *  - Back (or the top-left back button) closes via [onClose].
 *
 * No external image library — thumbnails come from MediaStore directly, so this
 * works on the locked-down AOSP builds these phones ship.
 */
@Composable
fun MediaPickerScreen(
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val permissions = remember { requiredMediaPermissions() }
    var granted by remember {
        mutableStateOf(permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var asked by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-check from the system rather than trusting the result map alone
        // (covers the "Allow limited access" / partial-grant cases on 14+).
        granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(permissions)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PickerHeader(onClose = onClose)
            if (granted) {
                MediaGrid(context = context, onPick = onPick)
            } else {
                PermissionPrompt(
                    asked = asked,
                    onGrant = { permLauncher.launch(permissions) },
                )
            }

            // The picker is an overlay in the SAME window as the thread beneath
            // it, so without a claim of its own the thread's "back" stayed live
            // and the right soft key would have left the conversation entirely
            // instead of closing the picker.
            SoftKeys(right = SoftKey("back") { onClose() })
        }
    }
}

@Composable
private fun PickerHeader(onClose: () -> Unit) {
    val backFocus = remember { FocusRequester() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .focusRequester(backFocus)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .dpadFocusHighlight(shape = CircleShape)
                .focusable()
                .onDpadAction { onClose(); true },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "Send photo or video",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun PermissionPrompt(asked: Boolean, onGrant: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (!asked) "Requesting access to your photos…"
                else "Allow access to photos and videos to send them.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (asked) {
                val grantFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { grantFocus.requestFocus() } }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .focusRequester(grantFocus)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .dpadRow(onClick = onGrant, shape = RoundedCornerShape(22.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "Allow access",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(
    context: Context,
    onPick: (String) -> Unit,
) {
    val items by produceState<List<PickerItem>?>(initialValue = null) {
        value = queryRecentMedia(context)
    }
    val current = items
    when {
        current == null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        current.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No photos or videos found.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        else -> {
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val columns = 3
                itemsIndexed(items = current, key = { _, it -> it.uri }) { index, item ->
                    MediaCell(
                        context = context,
                        item = item,
                        onClick = { onPick(item.uri) },
                        focusRequester = if (index == 0) firstFocus else null,
                        // Consume Left on the first column and Right on the last
                        // column / item so DPAD focus can't escape the grid into
                        // nothing (which dropped the highlight).
                        isLeftEdge = index % columns == 0,
                        isRightEdge = index % columns == columns - 1 || index == current.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCell(
    context: Context,
    item: PickerItem,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    isLeftEdge: Boolean = false,
    isRightEdge: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    val thumb by produceState<ImageBitmap?>(
        initialValue = com.offline.dpadmessenger.ui.util.cachedImageByKey(item.uri),
        item.uri,
    ) {
        value = loadThumbnail(context, item.uri)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // Trap Left/Right at the grid's horizontal edges so focus stays put
            // instead of being lost off the side of the grid.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> isLeftEdge
                    Key.DirectionRight -> isRightEdge
                    else -> false
                }
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .dpadRow(onClick = onClick, shape = shape),
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = if (item.isVideo) "Video" else "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        if (item.isVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** One MediaStore entry: its content:// uri and whether it's a video. */
private data class PickerItem(val uri: String, val isVideo: Boolean)

/** Media-read permissions appropriate to the running OS version. */
private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** Query recent images + videos (newest first), capped at [limit] entries. */
private suspend fun queryRecentMedia(context: Context, limit: Int = 300): List<PickerItem> =
    withContext(Dispatchers.IO) {
        val items = mutableListOf<PickerItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext() && items.size < limit) {
                    val id = c.getLong(idCol)
                    val isVideo =
                        c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val uri = ContentUris.withAppendedId(collection, id)
                    items.add(PickerItem(uri.toString(), isVideo))
                }
            }
        }
        items
    }

/** Decode a small thumbnail for [uriStr]. Uses MediaStore's fast thumbnailer
 *  on API 29+, falls back to a downsampled decode on older builds. */
private suspend fun loadThumbnail(context: Context, uriStr: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        com.offline.dpadmessenger.ui.util.cachedImageByKey(uriStr)?.let { return@withContext it }
        val uri = Uri.parse(uriStr)
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
                }
            }
        }.getOrNull()
        if (bmp != null) com.offline.dpadmessenger.ui.util.putCachedImage(uriStr, bmp)
        bmp
    }
