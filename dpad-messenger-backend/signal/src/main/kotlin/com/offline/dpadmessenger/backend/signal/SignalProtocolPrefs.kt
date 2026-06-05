package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SharedPreferences-backed storage for the protocol-store categories that
 * [AndroidSignalProtocolStore] exposes:
 *
 *   identities  — per-peer trusted IdentityKey (key = "aci.deviceId")
 *   prekeys     — one-time PreKeyRecord blobs (key = "preKeyId" as int-string)
 *   signed      — SignedPreKeyRecord blobs (key = "signedPreKeyId" as string)
 *   kyber       — KyberPreKeyRecord blobs (key = "kyberPreKeyId" as string)
 *   sessions    — SessionRecord blobs (key = "aci.deviceId")
 *
 * All values are base64-encoded serialized records. Each category lives in
 * its own [EncryptedSharedPreferences] file — keeps the namespaces separate
 * and limits the blast radius if any one prefs file gets corrupted.
 *
 * Why SharedPreferences and not Room/SQLite: a Signal linked device that
 * only talks to a small number of contacts accrues ~tens to low-hundreds of
 * session records — well within SharedPreferences' practical limit. If we
 * outgrow it we can swap in Room later without API changes.
 */
class SignalProtocolPrefs(context: Context) {

    // Hold the application Context so the lazy `prefs(...)` factory and the
    // MasterKey builder can reach it from member scope (the constructor's
    // `context` param goes out of scope after `init`).
    private val ctx: Context = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun prefs(name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            ctx,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val identities by lazy { prefs("dpad_sig_identities") }
    private val prekeys    by lazy { prefs("dpad_sig_prekeys") }
    private val signed     by lazy { prefs("dpad_sig_signed") }
    private val kyber      by lazy { prefs("dpad_sig_kyber") }
    private val sessions   by lazy { prefs("dpad_sig_sessions") }
    private val senderKeys by lazy { prefs("dpad_sig_senderkeys") }

    fun putIdentity(addressKey: String, bytes: ByteArray) =
        identities.edit().putString(addressKey, encode(bytes)).apply()
    fun getIdentity(addressKey: String): ByteArray? = identities.getString(addressKey, null)?.let(::decode)
    fun hasIdentity(addressKey: String): Boolean = identities.contains(addressKey)

    fun putPreKey(id: Int, bytes: ByteArray) =
        prekeys.edit().putString(id.toString(), encode(bytes)).apply()
    fun getPreKey(id: Int): ByteArray? = prekeys.getString(id.toString(), null)?.let(::decode)
    fun hasPreKey(id: Int): Boolean = prekeys.contains(id.toString())
    fun removePreKey(id: Int) = prekeys.edit().remove(id.toString()).apply()
    fun allPreKeyIds(): List<Int> = prekeys.all.keys.mapNotNull { it.toIntOrNull() }

    fun putSignedPreKey(id: Int, bytes: ByteArray) =
        signed.edit().putString(id.toString(), encode(bytes)).apply()
    fun getSignedPreKey(id: Int): ByteArray? = signed.getString(id.toString(), null)?.let(::decode)
    fun hasSignedPreKey(id: Int): Boolean = signed.contains(id.toString())
    fun removeSignedPreKey(id: Int) = signed.edit().remove(id.toString()).apply()
    fun allSignedPreKeys(): Map<Int, ByteArray> = signed.all
        .mapNotNull { (k, v) -> (k.toIntOrNull() ?: return@mapNotNull null) to decode(v as String) }
        .toMap()

    fun putKyberPreKey(id: Int, bytes: ByteArray) =
        kyber.edit().putString(id.toString(), encode(bytes)).apply()
    fun getKyberPreKey(id: Int): ByteArray? = kyber.getString(id.toString(), null)?.let(::decode)
    fun hasKyberPreKey(id: Int): Boolean = kyber.contains(id.toString())
    fun allKyberPreKeys(): Map<Int, ByteArray> = kyber.all
        .mapNotNull { (k, v) -> (k.toIntOrNull() ?: return@mapNotNull null) to decode(v as String) }
        .toMap()

    fun putSession(addressKey: String, bytes: ByteArray) =
        sessions.edit().putString(addressKey, encode(bytes)).apply()
    fun getSession(addressKey: String): ByteArray? = sessions.getString(addressKey, null)?.let(::decode)
    fun hasSession(addressKey: String): Boolean = sessions.contains(addressKey)
    fun removeSession(addressKey: String) = sessions.edit().remove(addressKey).apply()
    fun removeAllSessionsFor(serviceId: String) {
        val toRemove = sessions.all.keys.filter { it.startsWith("$serviceId.") }
        sessions.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }
    fun deviceIdsFor(serviceId: String): List<Int> = sessions.all.keys
        .filter { it.startsWith("$serviceId.") }
        .mapNotNull { it.substringAfter(".").toIntOrNull() }

    /**
     * SenderKey records, keyed by "<senderServiceId>.<deviceId>.<distributionUuid>".
     * Needed for Signal's SenderKey path — when a peer (or our primary) wants
     * to fan a message out to multiple recipients using one key, they send us
     * a SenderKeyDistributionMessage first; we store the resulting
     * [SenderKeyRecord] here so the follow-up GroupCipher decrypt succeeds.
     * Persisting (rather than in-memory) means SKDMs we've already received
     * survive app restart — without that, every restart loses key state and
     * the primary has to redistribute, which it may not do promptly.
     */
    fun putSenderKey(key: String, bytes: ByteArray) =
        senderKeys.edit().putString(key, encode(bytes)).apply()
    fun getSenderKey(key: String): ByteArray? = senderKeys.getString(key, null)?.let(::decode)

    private fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
