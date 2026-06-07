package com.offline.dpadmessenger.backend.signal

/**
 * Host-supplied configuration for the Signal backend, mirroring
 * `gmessages/GoogleMessagesConfig`.
 *
 * The host app sets [messengerActivityClassName] (its messenger Activity's
 * fully-qualified name) so a future Signal notifier can open it on tap without
 * this module needing a compile dependency on the host. Left null in the demo
 * app (no notifications there).
 */
object SignalConfig {
    @Volatile
    var messengerActivityClassName: String? = null
}
