package com.offline.dpadmessenger.data

/**
 * Capability marker for a [MessageRepository] that supports per-conversation
 * thread actions — delete and mute (see [MessageRepository.deleteRoom],
 * [MessageRepository.setMuted], [MessageRepository.observeMutedRooms]).
 *
 * The room list only surfaces the press-and-hold conversation menu when the
 * backing repository implements this, mirroring how [ConversationStarter] gates
 * the "new message" button. The in-memory mock and both real backends (Signal,
 * Google Messages) implement it; the bare mock used in previews does not.
 */
interface ThreadActions
