package com.offline.dpadmessenger.ui.util

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Process-wide LRU cache of decoded thumbnails, keyed by path+target size.
 * Without this, every media bubble / picker cell re-reads and re-decodes its
 * file from disk each time it scrolls back into view (LazyColumn disposes
 * off-screen items) — the dominant source of scroll jank and GC on the flip
 * phone. Sized by approximate bitmap bytes; ~8 MB is a few screens' worth.
 */
private val bitmapCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width.toLong() * value.height.toLong() * 4L)
            .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

private fun cacheKey(path: String, maxEdge: Int) = "$path@$maxEdge"

/** Cached decoded bitmap if present, else null. Safe on the main thread (a
 *  cheap map lookup) — use it as a synchronous fast path so a cache hit shows
 *  instantly instead of flashing a spinner. */
fun cachedBitmap(path: String, maxEdge: Int): ImageBitmap? = bitmapCache.get(cacheKey(path, maxEdge))

/**
 * [decodeDownscaled] with the LRU cache in front. Call from a background
 * thread. Returns null if the file can't be decoded (cached failures are not
 * stored, so a later retry can still succeed).
 */
fun decodeDownscaledCached(path: String, maxEdge: Int): ImageBitmap? {
    val key = cacheKey(path, maxEdge)
    bitmapCache.get(key)?.let { return it }
    val bmp = decodeDownscaled(path, maxEdge) ?: return null
    bitmapCache.put(key, bmp)
    return bmp
}

/** Generic cache access for callers that decode by their own means (e.g. the
 *  MediaStore content-uri thumbnailer in the picker). Keep keys unique. */
fun cachedImageByKey(key: String): ImageBitmap? = bitmapCache.get(key)
fun putCachedImage(key: String, bitmap: ImageBitmap) { bitmapCache.put(key, bitmap) }

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
