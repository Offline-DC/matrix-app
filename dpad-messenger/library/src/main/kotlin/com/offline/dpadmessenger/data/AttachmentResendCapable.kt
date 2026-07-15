package com.offline.dpadmessenger.data

/**
 * Marker for repositories whose [MessageRepository.resendMessage] can re-upload a
 * failed ATTACHMENT (photo / video / voice memo), not just re-send text.
 *
 * The chat context sheet only offers "Retry send" on a failed message that HAS an
 * attachment when the backing repository implements this, mirroring how
 * [RemoteDeleteCapable] gates Delete. Backends that can only resend text (Signal,
 * Google Messages — both bail out on `attachment != null`) must NOT implement it:
 * the Retry row would either do nothing or, worse, re-send the message with an
 * empty body and silently drop the media.
 *
 * Implementing this is a promise that resendMessage keeps the attachment's bytes
 * (e.g. a local copy written at send time) and re-uploads them.
 */
interface AttachmentResendCapable
