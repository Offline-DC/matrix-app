package com.offline.dpadmessenger.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.offline.dpadmessenger.data.AttachmentKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies a downloaded attachment out of the messenger's private media cache and
 * into the device gallery, so it survives the cache being pruned and shows up in
 * whatever the handset uses to browse photos.
 *
 * Called from the media viewer's "save" option. Blocking — copies bytes — so run
 * it off the main thread; the caller reports the boolean with a toast.
 *
 * ## Why MediaStore and not a file write
 *
 * From Android 10 the gallery directories aren't writable directly, but inserting
 * into [MediaStore] is, with no permission at all: the row we create is ours. That
 * is the path the target handsets take (the TCL Flip 2 is Android 11).
 *
 * Below 10 the same insert needs `WRITE_EXTERNAL_STORAGE`, which this library
 * doesn't declare or request — a pre-10 save therefore throws `SecurityException`,
 * lands in the catch, and the viewer says it couldn't save rather than pretending.
 * That's a deliberate trade: no runtime-permission flow for an OS version none of
 * the shipping hardware runs. Add the declaration plus a request here if that
 * changes.
 */
internal fun saveMediaToGallery(
    context: Context,
    path: String,
    kind: AttachmentKind,
): Boolean {
    val source = File(path)
    // A zero-length file means the download never finished. Copying it would put
    // a broken entry in the gallery and still report success.
    if (!source.exists() || source.length() == 0L) return false

    val isVideo = kind == AttachmentKind.VIDEO
    val mime = guessMimeType(source, isVideo)
    val collection =
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, saveFileName(source, mime, isVideo))
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$SAVE_SUBDIR")
            // Keep the row hidden from the gallery until the bytes are actually
            // there, so an interrupted copy never surfaces as a broken thumbnail.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri: Uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false

    val copied = runCatching {
        val stream = resolver.openOutputStream(uri) ?: return@runCatching false
        stream.use { out -> source.inputStream().use { it.copyTo(out) } }
        true
    }.getOrElse { false }

    if (!copied) {
        // Drop the placeholder row. On Q+ it's still IS_PENDING, which the
        // gallery hides — leaving it would litter the media database with rows
        // the user can neither see nor delete.
        runCatching { resolver.delete(uri, null, null) }
        return false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }
    return true
}

/** Gallery sub-folder saved media lands in, under Pictures/ or Movies/. */
private const val SAVE_SUBDIR = "Messages"

/**
 * Mime type for the declared row. The cache names files by their mime type's
 * extension, so the extension is the best source; the [isVideo] fallbacks only
 * matter for a file that somehow arrived without one.
 */
private fun guessMimeType(file: File, isVideo: Boolean): String {
    val ext = file.extension.lowercase(Locale.US)
    val fromExt = if (ext.isNotEmpty()) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    } else null
    return fromExt ?: if (isVideo) "video/mp4" else "image/jpeg"
}

/**
 * A name for the gallery. The cache names files after their content, which is not
 * something to put in front of someone browsing their photos — so use a readable,
 * sortable timestamp instead, with an extension that matches the mime we declared.
 *
 * Two saves inside the same second collide; MediaStore resolves that itself by
 * appending "(1)", which is the behaviour any gallery app already shows.
 */
private fun saveFileName(file: File, mime: String, isVideo: Boolean): String {
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        ?: file.extension.ifEmpty { if (isVideo) "mp4" else "jpg" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "message-$stamp.$ext"
}
