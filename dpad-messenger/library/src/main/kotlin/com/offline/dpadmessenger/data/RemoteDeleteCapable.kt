package com.offline.dpadmessenger.data

/**
 * Capability marker for a [MessageRepository] whose [MessageRepository.deleteMessage]
 * is a real "delete for everyone" — it redacts the message on the recipient's
 * devices and syncs the deletion to the user's own linked devices (Signal
 * semantics: `DataMessage.Delete` keyed by author + sent timestamp, mirrored
 * via a `SyncMessage.Sent` transcript).
 *
 * The chat context sheet only offers "Delete" when the backing repository
 * implements this, mirroring how [AttachmentSender] gates the composer's "+"
 * button. Backends without a remote-delete wire protocol (e.g. SMS/RCS) must
 * NOT implement it — the option would silently do nothing.
 */
interface RemoteDeleteCapable
