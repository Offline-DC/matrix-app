package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context

/**
 * Which network an outgoing call should go out on when the person can be reached
 * both ways.
 *
 * The dialer asks "call from?" whenever an IDS lookup says the number is on
 * FaceTime — correct the first time and tedious by the fiftieth, because for most
 * people the answer never changes. This is the standing answer.
 */
enum class DefaultCalling(val storedValue: String, val label: String) {
    /** Ask every time. The default, and what the product did before this existed. */
    ASK("ask", "ask every time"),

    /** Call over FaceTime when the peer is reachable there; dial when they aren't. */
    SMART("smart", "smart number"),

    /** Always dial the cellular number, even when FaceTime is available. */
    DUMB("dumb", "dumb number"),
    ;

    companion object {
        fun fromStored(value: String?): DefaultCalling =
            entries.firstOrNull { it.storedValue == value } ?: ASK
    }
}

/**
 * Where [DefaultCalling] is kept.
 *
 * ## Why not SmartTxtAccountStore
 * That is the obvious neighbour — it already holds `defaultHandle`, the other
 * "who do I go out as" setting — but it is backed by EncryptedSharedPreferences,
 * and reading it means a keystore round trip that `DialerController` already
 * documents as too slow for the main thread. The dialer consults this on the
 * path between pressing call and the call going out, so it lives in the plain
 * `smarttxt_settings` file instead, next to the 24-hour clock preference.
 *
 * Nothing here is a secret: it is a two-bit choice about which radio to use.
 *
 * ## Who reads it
 * The Smart Txt settings screen writes it, and the launcher's dialer reads it.
 * Those are different Gradle modules but the same process — the launcher builds
 * the backend in — so one SharedPreferences file is genuinely shared state and
 * not an IPC boundary in disguise.
 */
object SmartTxtCallPreference {

    /** Shared with the rest of Smart Txt's display preferences. */
    private const val PREFS_NAME = "smarttxt_settings"
    private const val KEY_DEFAULT_CALLING = "defaultCalling"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The standing answer, or [DefaultCalling.ASK] when the user hasn't set one. */
    fun defaultCalling(context: Context): DefaultCalling =
        DefaultCalling.fromStored(prefs(context).getString(KEY_DEFAULT_CALLING, null))

    fun setDefaultCalling(context: Context, value: DefaultCalling) {
        prefs(context).edit().putString(KEY_DEFAULT_CALLING, value.storedValue).apply()
    }
}
