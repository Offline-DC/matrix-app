package com.offline.dpadmessenger.ui.chat

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import com.offline.dpadmessenger.data.AttachmentKind
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction
import androidx.compose.foundation.focusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fullscreen media viewer overlay. Black background, the image/video centered,
 * and a single back button (DPAD-focusable; Back key also closes). Mounted on
 * top of the chat by [com.offline.dpadmessenger.ui.chat.ChatScreen].
 */
@Composable
fun FullscreenMediaViewer(
    path: String,
    kind: AttachmentKind,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            AttachmentKind.VIDEO -> VideoPlayer(path)
            else -> ImageView(path)
        }

        // Back button (top-left), DPAD-focusable + OK to close.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .focusRequester(backFocus)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .dpadFocusHighlight(shape = CircleShape, borderColor = Color.White)
                .focusable()
                .onDpadAction { onClose(); true },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Close",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ImageView(path: String) {
    // null = decoding; Decoded(null) = decode failed (e.g. HEIC with no codec).
    val result by produceState<ImageResult?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            // Downscale large images so we don't OOM on a flip phone; the
            // shared decoder also handles HEIF/HEIC via ImageDecoder.
            ImageResult(com.offline.dpadmessenger.ui.util.decodeDownscaled(path, maxEdge = 1280))
        }
    }
    when (val r = result) {
        null -> CircularProgressIndicator(color = Color.White)
        else -> {
            val bmp = r.bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "Can't preview this photo on this device.",
                    color = Color.White,
                )
            }
        }
    }
}

/** Distinguishes a finished-but-failed decode from "still decoding". */
private data class ImageResult(val bitmap: androidx.compose.ui.graphics.ImageBitmap?)

@Composable
private fun VideoPlayer(path: String) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            VideoView(context).apply {
                setVideoPath(path)
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { it.isLooping = false; start() }
            }
        },
    )
}
