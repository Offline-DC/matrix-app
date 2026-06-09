package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import android.util.Log
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves contact display names via Signal's profile service, using the
 * 32-byte `profileKey` a sender includes on their messages. This is how we get
 * real names without contact sync (which modern primaries often don't answer):
 * fetch the encrypted profile name and decrypt it with the sender's profile key.
 *
 * Per-process cache: each serviceId is resolved at most once (re-resolving on
 * every inbound message would hammer the profile endpoint).
 *
 * ⚠️ Uses the unversioned `/v1/profile/<aci>` endpoint — verify on-device that
 * it still returns `name`; if not, the versioned endpoint is the fallback.
 */
class SignalProfiles(
    private val account: SignalAccount,
    private val api: SignalApi,
) {
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

    /** serviceIds we've SUCCESSFULLY named — never re-fetched. */
    private val succeeded = ConcurrentHashMap.newKeySet<String>()
    /** Last attempt time per serviceId, to throttle retries after a miss. */
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()

    /**
     * Fetch + decrypt [serviceId]'s profile name using [profileKey]. Returns the
     * decrypted name, or null if already resolved, throttled, or the fetch had
     * no name. Unlike a success, a MISS is not cached — we retry (after a short
     * throttle) on the next message, so a transient failure self-heals.
     * Best-effort — never throws.
     */
    suspend fun resolveName(serviceId: String, profileKey: ByteArray): String? {
        if (profileKey.size != 32) {
            Log.d(TAG, "resolveName $serviceId: bad profileKey size ${profileKey.size}")
            return null
        }
        if (serviceId in succeeded) {
            Log.d(TAG, "resolveName $serviceId: already resolved — skipping")
            return null
        }
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[serviceId]
        if (last != null && now - last < ATTEMPT_THROTTLE_MS) {
            Log.d(TAG, "resolveName $serviceId: throttled (${(now - last) / 1000}s since last try)")
            return null
        }
        lastAttemptAt[serviceId] = now
        // Derive the zkgroup profile-key version; the VERSIONED endpoint is the
        // one that returns the encrypted name (the unversioned one omits it).
        val version = runCatching {
            ProfileKey(profileKey).getProfileKeyVersion(ServiceId.Aci(UUID.fromString(serviceId))).serialize()
        }.getOrNull()
        Log.d(TAG, "resolveName $serviceId: fetching profile (version=${if (version != null) "yes" else "n/a"})…")
        return try {
            val encryptedB64 = api.fetchProfileEncryptedName(login, password, serviceId, version)
            if (encryptedB64 == null) {
                Log.d(TAG, "resolveName $serviceId: no encrypted name returned (will retry later)")
                return null
            }
            val encrypted = Base64.decode(encryptedB64, Base64.NO_WRAP)
            val name = SignalProfileCipher.decryptName(encrypted, profileKey)
            Log.d(
                TAG,
                "resolveName $serviceId: encrypted=${encrypted.size}b decrypted=${
                    if (name == null) "FAILED/blank" else "\"$name\""
                }",
            )
            if (name != null) succeeded.add(serviceId)  // cache only on success
            name
        } catch (t: Throwable) {
            Log.w(TAG, "resolveName $serviceId: failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "SignalProfiles"
        /** Minimum gap between profile-fetch attempts for the same contact
         *  after a miss (avoids hammering the endpoint on every message). */
        private const val ATTEMPT_THROTTLE_MS = 30_000L
    }
}
