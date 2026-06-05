package com.offline.dpadmessenger.ui.chat

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
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
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Downscale very large images so we don't OOM on a flip phone.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 1280)
                }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        CircularProgressIndicator(color = Color.White)
    } else {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

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

/** Largest power-of-two sample that keeps the longer edge ≥ [target]. */
private fun sampleSize(w: Int, h: Int, target: Int): Int {
    var sample = 1
    var longer = maxOf(w, h)
    while (longer / 2 >= target) { sample *= 2; longer /= 2 }
    return sample
}
