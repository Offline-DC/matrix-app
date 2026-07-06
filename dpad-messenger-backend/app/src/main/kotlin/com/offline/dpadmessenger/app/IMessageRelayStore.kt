package com.offline.dpadmessenger.app

import android.content.Context

/**
 * Persists the iMessage relay connection (the daemon's URL + optional bearer
 * token) so the app reconnects on boot — the analogue of [SignalAccountStore].
 *
 * The daemon (`imessage-relay`) performs the actual Apple registration: it reads
 * the dumb file, logs into iCloud, and completes 2FA server-side, exactly like an
 * OpenBubbles server. The phone is a thin client that just needs to know where
 * the daemon is. So there is no Apple password / 2FA stored here — only the relay
 * endpoint.
 */
class IMessageRelayStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("imessage_relay", Context.MODE_PRIVATE)

    fun save(url: String, token: String?) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token?.trim())
            .apply()
    }

    fun url(): String? = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
    }
}
