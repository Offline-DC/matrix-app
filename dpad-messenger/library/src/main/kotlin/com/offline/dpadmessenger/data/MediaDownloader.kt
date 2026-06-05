package com.offline.dpadmessenger.data

/**
 * Optional capability for repositories whose messages can carry downloadable
 * media. The chat UI uses it for the tap-to-load → tap-to-view flow.
 */
interface MediaDownloader {
    /**
     * Download + cache the attachment on [messageId] in [roomId]. Updates the
     * message's [Attachment.localPath] in the observed flow on success.
     * @return the local file path, or null on failure.
     */
    suspend fun downloadMedia(roomId: String, messageId: String): String?
}
