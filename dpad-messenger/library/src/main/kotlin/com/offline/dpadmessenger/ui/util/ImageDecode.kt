package com.offline.dpadmessenger.ui.util

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Decode an image file to a downscaled [ImageBitmap], or null if it can't be
 * decoded.
 *
 * Tries the platform [ImageDecoder] first on API 28+ — it covers more formats
 * than the legacy [BitmapFactory] path, notably **HEIF/HEIC** (the format
 * iPhones send), plus WebP and animated stills. Falls back to BitmapFactory on
 * older builds or if ImageDecoder throws. Returns null when the device simply
 * can't decode the bytes (e.g. an HEIC on hardware with no HEVC/HEIF decoder),
 * so callers can show a clear "can't preview" state instead of spinning forever.
 *
 * @param maxEdge target size for the longer edge in px; <=0 disables downscale.
 */
fun decodeDownscaled(path: String, maxEdge: Int): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.length() == 0L) return null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val viaDecoder = runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software allocation → universally drawable (incl. software
                // canvases / screenshots) and safe to wrap as an ImageBitmap.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val longer = maxOf(info.size.width, info.size.height)
                if (maxEdge > 0 && longer > maxEdge) {
                    val scale = maxEdge.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }.asImageBitmap()
        }.getOrNull()
        if (viaDecoder != null) return viaDecoder
        // else fall through to BitmapFactory
    }

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        var longer = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxEdge > 0 && longer / 2 >= maxEdge) { sample *= 2; longer /= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }.getOrNull()
}
