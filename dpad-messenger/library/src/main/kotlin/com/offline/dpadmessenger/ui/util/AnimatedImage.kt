package com.offline.dpadmessenger.ui.util

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Animated-image support (GIF / animated WebP).
 *
 * The still-image path ([decodeDownscaled]) goes through
 * [ImageDecoder.decodeBitmap], which ALWAYS returns a single frame — so every
 * GIF froze on frame one. This adds the missing path: decode to an
 * [AnimatedImageDrawable] via [ImageDecoder.decodeDrawable] and host it in a
 * real [ImageView] (Compose's canvas can't draw an animating Drawable itself).
 *
 * Both the inline bubble ([com.offline.dpadmessenger.ui.components.MessageBubble])
 * and the fullscreen viewer ([com.offline.dpadmessenger.ui.chat.FullscreenMediaViewer])
 * use these so a GIF animates in the thread AND when opened.
 */

/**
 * Cheap pre-filter: could this file be an animated image worth attempting an
 * animated decode on? Only GIF / WebP can animate, so this keeps the (heavier)
 * [decodeAnimatedDrawable] attempt off every JPEG/PNG/HEIC. A *static* GIF/WebP
 * passes this filter but [decodeAnimatedDrawable] returns null for it, so the
 * caller still falls back to the still-frame path.
 */
fun isMaybeAnimatedImage(path: String, mimeType: String?): Boolean {
    val mt = mimeType?.lowercase().orEmpty()
    if (mt == "image/gif" || mt == "image/webp") return true
    return when (path.substringAfterLast('.', "").lowercase()) {
        "gif", "webp" -> true
        else -> false
    }
}

/**
 * Decode [path] to a looping [AnimatedImageDrawable], or null when the file is
 * not a genuinely animated image (a static GIF/WebP decodes to a BitmapDrawable,
 * which returns null here), can't be decoded, or the platform predates
 * [ImageDecoder] (API 28). Call from a background thread — decoding touches disk.
 *
 * @param maxEdge downscale target for the longer edge in px; <=0 keeps full size.
 */
fun decodeAnimatedDrawable(path: String, maxEdge: Int = 0): AnimatedImageDrawable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    val file = File(path)
    if (!file.exists() || file.length() == 0L) return null
    return runCatching {
        val source = ImageDecoder.createSource(file)
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            if (maxEdge > 0) {
                val longer = maxOf(info.size.width, info.size.height)
                if (longer > maxEdge) {
                    val scale = maxEdge.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        }
        // decodeDrawable returns an AnimatedImageDrawable ONLY for a multi-frame
        // source; a single-frame GIF/WebP comes back as a BitmapDrawable. Loop
        // forever like every messenger does (the file's own loop count is often 1).
        (drawable as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    }.getOrNull()
}

/**
 * Render an [AnimatedImageDrawable] and keep it animating. Compose can't draw an
 * animating Drawable on its own canvas, so we host it in an [ImageView].
 * [AnimatedImageDrawable.start] kicks the animation; [AnimatedImageDrawable.stop]
 * on dispose releases its frame callback when the bubble scrolls off / the viewer
 * closes so it isn't left ticking in the background.
 */
@Composable
fun AnimatedImage(
    drawable: AnimatedImageDrawable,
    modifier: Modifier = Modifier,
    scaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> ImageView(ctx) },
        update = { view ->
            view.scaleType = scaleType
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            // Guarded to match the codebase's runtime-SDK convention (see
            // ImageDecode.decodeDownscaled); always true here since an
            // AnimatedImageDrawable can only exist on API 28+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drawable.start() // no-op if already running
        },
    )
    DisposableEffect(drawable) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drawable.stop()
        }
    }
}
