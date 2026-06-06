package com.offline.dpadmessenger.backend.gmessages

/**
 * Host-app configuration for the Google Messages backend.
 *
 * This module is host-agnostic: it doesn't know the name of the Activity that
 * displays the messenger. The host app sets that here once at startup so the
 * incoming-message notification (see [GoogleMessagesNotifier]) knows what to
 * open when tapped.
 *
 * dumb-down-launcher sets this in `DumbDownApp.onCreate`:
 * ```
 * GoogleMessagesConfig.messengerActivityClassName =
 *     "com.offlineinc.dumbdownlauncher.messenger.MessengerActivity"
 * ```
 *
 * The named Activity is targeted by fully-qualified class name (via
 * `Intent.setClassName(packageName, …)`) so this module never needs a compile
 * dependency on the host's `:app` module.
 */
object GoogleMessagesConfig {

    /**
     * Fully-qualified class name of the Activity that hosts the messenger UI,
     * in the host app's own package. When null, incoming-message
     * notifications are still posted but have no tap target (they won't open
     * anything). Set it before the first message can arrive.
     */
    @Volatile
    var messengerActivityClassName: String? = null
}
