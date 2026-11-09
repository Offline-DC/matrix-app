package com.offline.dpadmessenger.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
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

/**
 * Decode a small preview of a MediaStore item ([uri]) entirely in memory, or
 * null if it can't be decoded.
 *
 * This exists so the media picker never asks MediaProvider for a thumbnail.
 * `ContentResolver.loadThumbnail` on Android 10+ doesn't just return a bitmap:
 * MediaProvider generates it and **persists** it to shared storage as
 * `<dir>/.thumbnails/<_ID>.jpg` (about 10 KB, number-named, next to a
 * `.nomedia`). The TCL Flip 2 runs API 30, so every photo the picker ever
 * showed left one behind — and a Mac importing photos over USB (Image Capture,
 * Apple Photos) lists those files as photos, so each real photo imports twice
 * (Stefano, Oct 2026; Jack confirmed the number-named ~10 KB files from his
 * screenshot). Decoding the preview ourselves writes nothing. Don't bring
 * `loadThumbnail` back.
 *
 * - **Video:** a frame from [MediaMetadataRetriever]. API 27+ asks for one
 *   already scaled; older builds take the full frame and scale it down. Before
 *   this the pre-29 path tried to `BitmapFactory` a video stream, got null, and
 *   the cell spun forever.
 * - **Image, API 28+:** [ImageDecoder] over the content resolver, downscaled
 *   at decode time as [decodeDownscaled] does — it also applies EXIF
 *   orientation itself, so a portrait photo comes out upright.
 * - **Image, API 24–27** (the HIT phone, Android 8.1): [BitmapFactory] with a
 *   bounds pass first, so `inSampleSize` is chosen from the real dimensions
 *   rather than the fixed `8` the old path used. EXIF orientation is not
 *   applied on this path — BitmapFactory never did, and that is what those
 *   builds showed before too.
 *
 * Every failure is swallowed into null; the cell already shows a spinner for
 * that. Runs a decode — call from a background thread.
 *
 * @param maxEdge target size for the longer edge in px.
 */
fun decodeUriDownscaled(context: Context, uri: Uri, maxEdge: Int, isVideo: Boolean): ImageBitmap? =
    runCatching {
        if (isVideo) decodeVideoFrame(context, uri, maxEdge) else decodeImageUri(context, uri, maxEdge)
    }.getOrNull()

private fun decodeVideoFrame(context: Context, uri: Uri, maxEdge: Int): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                -1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge,
            )
        } else {
            retriever.getFrameAtTime()?.let { full ->
                val longer = maxOf(full.width, full.height)
                if (maxEdge > 0 && longer > maxEdge) {
                    val scale = maxEdge.toFloat() / longer
                    Bitmap.createScaledBitmap(
                        full,
                        (full.width * scale).toInt().coerceAtLeast(1),
                        (full.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    full
                }
            }
        }
        return frame?.asImageBitmap()
    } finally {
        // release() is what frees the native decoder; close() only exists on
        // API 29+, and on those builds it is the same call.
        retriever.release()
    }
}

private fun decodeImageUri(context: Context, uri: Uri, maxEdge: Int): ImageBitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val viaDecoder = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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
        // else fall through to BitmapFactory, as decodeDownscaled does
    }

    val resolver = context.contentResolver
    // Bounds pass. `decodeStream` returns null here BY DESIGN (inJustDecodeBounds
    // decodes nothing), so the result is deliberately not inspected — only the
    // stream's presence and the sizes it filled in are. A first draft treated
    // that null as "couldn't open" and returned early, which would have left
    // every photo on the Android 8.1 handset spinning for ever.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
    if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var longer = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxEdge > 0 && longer / 2 >= maxEdge) { sample *= 2; longer /= 2 }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}
