package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * Media transport for the Signal backend: turns a picked `content://` URI
 * into an uploaded, encrypted CDN attachment (send side), and turns an
 * inbound [AttachmentPointer] download token back into a decrypted local
 * file (receive side).
 *
 * Receive (download + decrypt) reuses [SignalApi.downloadAttachment] +
 * [SignalAttachmentCrypto.decrypt] — the same path contact sync already
 * exercises in production. Send (encrypt + CDN upload) is new and uses the
 * v4 upload form + TUS resumable upload; that half needs on-device
 * verification since the sandbox can't reach the CDN.
 *
 * Lives behind the optional [com.offline.dpadmessenger.data.MediaDownloader]
 * / [com.offline.dpadmessenger.data.AttachmentSender] capabilities so the
 * mock/unit-test repository can omit it entirely.
 */
class SignalAttachments(
    context: Context,
    private val account: SignalAccount,
    private val api: SignalApi,
) {
    private val appContext = context.applicationContext
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

    /** Everything [SignalSender.sendAttachment] needs to build an
     *  AttachmentPointer, plus [localPath] so the local bubble renders the
     *  picked media immediately (before the recipient ever downloads it). */
    class Uploaded(
        val cdnNumber: Int,
        val cdnKey: String,
        val key: ByteArray,        // 64-byte AES+HMAC key (travels in the DataMessage)
        val digest: ByteArray,     // SHA-256 over iv||ciphertext||mac
        val size: Int,             // plaintext byte length
        val contentType: String,
        val fileName: String?,
        val localPath: String,
    )

    /**
     * Read [contentUri], encrypt it under a fresh 64-byte key, upload the
     * blob to the CDN, and return the pointer fields. Also drops a plaintext
     * copy in the cache so the sender's own bubble can show it right away.
     * Returns null on any failure (caller marks the send failed).
     */
    suspend fun upload(contentUri: String): Uploaded? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(contentUri)
            val resolver = appContext.contentResolver
            val plaintext = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run {
                    Log.w(TAG, "upload: could not open $contentUri")
                    return@withContext null
                }
            // contentResolver.getType() returns null for file:// URIs (e.g. a
            // recorded voice memo from cacheDir), so fall back to the file
            // extension — otherwise an .m4a would upload as octet-stream and
            // render as a generic file instead of a voice message.
            val contentType = resolver.getType(uri)
                ?: mimeFromExtension(contentUri)
                ?: "application/octet-stream"
            val fileName = queryDisplayName(uri)

            // Cache a plaintext copy for the local bubble.
            val localFile = File(mediaDir(), "out-${System.currentTimeMillis()}-${fileName ?: "media"}")
            localFile.writeBytes(plaintext)

            val key = ByteArray(64).also { SecureRandom().nextBytes(it) }
            val encrypted = SignalAttachmentCrypto.encrypt(plaintext, key)

            val form = api.getAttachmentUploadForm(login, password)
            api.uploadAttachment(form, encrypted.data)

            Log.d(TAG, "uploaded ${plaintext.size}b as cdn${form.cdn} key=${form.key.take(12)}…")
            Uploaded(
                cdnNumber = form.cdn,
                cdnKey = form.key,
                key = key,
                digest = encrypted.digest,
                size = plaintext.size,
                contentType = contentType,
                fileName = fileName,
                localPath = localFile.absolutePath,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "attachment upload failed", t)
            null
        }
    }

    /**
     * Download + decrypt the attachment described by [token] and write it to
     * the media cache. Returns the local file path, or null on failure.
     */
    suspend fun download(token: String): String? = withContext(Dispatchers.IO) {
        val parsed = AttachmentToken.decode(token) ?: run {
            Log.w(TAG, "download: un-parseable token")
            return@withContext null
        }
        try {
            val encrypted = api.downloadAttachment(login, password, parsed.cdnNumber, parsed.cdnKey)
            val plaintext = SignalAttachmentCrypto.decrypt(encrypted, parsed.key, parsed.digest)
            // Signal pads attachments to a bucket size; the real length lives
            // in the pointer's `size`. Trim if we have it.
            val trimmed = if (parsed.size in 0 until plaintext.size) {
                plaintext.copyOfRange(0, parsed.size)
            } else {
                plaintext
            }
            val ext = extensionFor(parsed.contentType)
            val out = File(mediaDir(), "in-${parsed.cdnKey.takeLast(16).replace('/', '_')}$ext")
            out.writeBytes(trimmed)
            Log.d(TAG, "downloaded ${trimmed.size}b → ${out.name}")
            out.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "attachment download failed", t)
            null
        }
    }

    private fun mediaDir(): File =
        File(appContext.cacheDir, "signal-media").apply { mkdirs() }

    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun extensionFor(contentType: String): String = when {
        contentType.startsWith("image/jpeg") -> ".jpg"
        contentType.startsWith("image/png") -> ".png"
        contentType.startsWith("image/gif") -> ".gif"
        contentType.startsWith("image/webp") -> ".webp"
        contentType.startsWith("video/mp4") -> ".mp4"
        contentType.startsWith("video/") -> ".mov"
        contentType.startsWith("audio/mpeg") -> ".mp3"
        contentType.startsWith("audio/ogg") -> ".ogg"
        contentType.startsWith("audio/") -> ".m4a"
        else -> ".bin"
    }

    /** Best-effort MIME from a uri/path file extension, for file:// sources
     *  where the content resolver can't supply a type. */
    private fun mimeFromExtension(uriOrPath: String): String? =
        when (uriOrPath.substringAfterLast('.', "").lowercase()) {
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            else -> null
        }

    companion object {
        private const val TAG = "SignalAttachments"

        /** Derive the UI [com.offline.dpadmessenger.data.AttachmentKind]
         *  string-friendly kind from a MIME type. */
        fun kindFor(contentType: String): com.offline.dpadmessenger.data.AttachmentKind = when {
            contentType.startsWith("image/") -> com.offline.dpadmessenger.data.AttachmentKind.IMAGE
            contentType.startsWith("video/") -> com.offline.dpadmessenger.data.AttachmentKind.VIDEO
            contentType.startsWith("audio/") -> com.offline.dpadmessenger.data.AttachmentKind.AUDIO
            else -> com.offline.dpadmessenger.data.AttachmentKind.OTHER
        }
    }

    /**
     * Compact, self-contained encoding of everything [download] needs, stashed
     * in [com.offline.dpadmessenger.data.Attachment.downloadToken] so the UI's
     * tap-to-load flow can fetch the bytes later without re-parsing protos.
     *
     * Format (pipe-delimited, v1):
     *   `v1|cdnNumber|cdnKey|keyB64|digestB64|size|contentTypeB64`
     * Base64 (URL-safe, no-wrap) is used for the binary + free-text fields so
     * pipes/slashes in the values can't corrupt the framing.
     */
    data class AttachmentToken(
        val cdnNumber: Int,
        val cdnKey: String,
        val key: ByteArray,
        val digest: ByteArray?,
        val size: Int,
        val contentType: String,
    ) {
        fun encode(): String = listOf(
            "v1",
            cdnNumber.toString(),
            b64(cdnKey.toByteArray()),
            b64(key),
            digest?.let { b64(it) } ?: "",
            size.toString(),
            b64(contentType.toByteArray()),
        ).joinToString("|")

        companion object {
            private fun b64(b: ByteArray) =
                Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE)

            private fun unb64(s: String) =
                Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)

            fun decode(token: String): AttachmentToken? = try {
                val p = token.split("|")
                if (p.size < 7 || p[0] != "v1") null
                else AttachmentToken(
                    cdnNumber = p[1].toInt(),
                    cdnKey = String(unb64(p[2])),
                    key = unb64(p[3]),
                    digest = if (p[4].isEmpty()) null else unb64(p[4]),
                    size = p[5].toInt(),
                    contentType = String(unb64(p[6])),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
