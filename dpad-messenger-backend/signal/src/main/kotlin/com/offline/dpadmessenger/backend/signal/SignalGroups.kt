package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import android.util.Log
import com.offline.dpadmessenger.backend.signal.groupsproto.Group
import com.offline.dpadmessenger.backend.signal.groupsproto.GroupAttributeBlob
import com.offline.dpadmessenger.backend.signal.groupsproto.GroupResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.groups.UuidCiphertext
import org.signal.libsignal.zkgroup.groupsend.GroupSendEndorsement
import org.signal.libsignal.zkgroup.groupsend.GroupSendEndorsementsResponse
import java.time.Instant
import java.util.UUID

/**
 * GroupsV2 subsystem: turns a group's 32-byte `masterKey` (carried in every
 * group message's `DataMessage.groupV2`) into a stable group id, and fetches +
 * decrypts the group's title and member ACIs from Signal's group-state server.
 *
 * All zkgroup usage is isolated here so the rest of the backend never touches
 * it. Flow (mirrors Signal-Android `GroupsV2Api` / mautrix-signal
 * `pkg/signalmeow/groupsv2.go`):
 *
 *  1. `GroupSecretParams.deriveFromMasterKey(masterKey)` → secret params; its
 *     public params' `GroupIdentifier` is the stable, server-agnostic group id.
 *  2. `GET /v2/auth` → weekly `AuthCredentialWithPni` responses; pick today's,
 *     `receiveAuthCredentialWithPniAsServiceId(aci, pni, redemptionTime, …)`.
 *  3. `createAuthCredentialPresentation(secretParams, cred)` → presentation.
 *  4. `GET https://storage.signal.org/v1/groups/` with Basic
 *     `hex(publicParams):hex(presentation)` → encrypted `Group` protobuf.
 *  5. `ClientZkGroupCipher(secretParams)` decrypts the title blob and each
 *     member's `userId` ciphertext into a real ACI.
 *
 * ⚠️ ON-DEVICE VERIFICATION REQUIRED. None of this can run in CI (no CDN /
 * group server, no libsignal native lib). Two things especially to verify on
 * a device against the current libsignal-android:
 *   - [SERVER_PUBLIC_PARAMS_B64] MUST be filled with Signal's real production
 *     zkgroup server public params (a public, non-secret constant). It's left
 *     blank here on purpose — a wrong value is worse than an obvious failure.
 *     Source: Signal-Android `BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS`
 *     (prod) — the same base64 string mautrix-signal embeds as
 *     `prodServerPublicParams`.
 *   - The exact zkgroup method names (`createAuthCredentialPresentation`,
 *     `decryptServiceId`, `decryptBlob`) can drift between libsignal releases;
 *     verify against the 0.86.5 javadocs if compilation complains.
 */
class SignalGroups(
    private val account: SignalAccount,
    private val api: SignalApi,
) {
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

    private val serverPublicParams: ServerPublicParams? by lazy {
        if (SERVER_PUBLIC_PARAMS_B64.isBlank()) {
            Log.e(TAG, "SERVER_PUBLIC_PARAMS_B64 is unset — group state fetch disabled")
            null
        } else {
            runCatching { ServerPublicParams(Base64.decode(SERVER_PUBLIC_PARAMS_B64, Base64.NO_WRAP)) }
                .onFailure { Log.e(TAG, "bad SERVER_PUBLIC_PARAMS_B64", it) }
                .getOrNull()
        }
    }

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CachedGroup>()
    private data class CachedGroup(val info: GroupInfo, val fetchedAt: Long)

    /** Decrypted, app-facing group state. */
    data class GroupInfo(
        val masterKey: ByteArray,
        /** Stable group id (zkgroup GroupIdentifier bytes), base64'd. */
        val groupId: String,
        val title: String,
        /** Member ACIs (service-id strings), in group order, including us. */
        val memberAcis: List<String>,
        val revision: Int,
        /** Serialized GroupSendEndorsementsResponse for multi-recipient send
         *  authorization, or null if the server didn't supply it. */
        val endorsementsResponse: ByteArray? = null,
    )

    /** Raw zkgroup GroupIdentifier bytes (used as the sealed-sender groupId). */
    fun groupIdBytesForMasterKey(masterKey: ByteArray): ByteArray? = try {
        GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            .publicParams.groupIdentifier.serialize()
    } catch (t: Throwable) {
        Log.w(TAG, "groupId bytes derivation failed", t)
        null
    }

    /** "sig:group:<base64 groupId>" room id for a group's master key. */
    fun roomIdForMasterKey(masterKey: ByteArray): String? {
        val id = groupIdForMasterKey(masterKey) ?: return null
        return ROOM_PREFIX + id
    }

    /** Stable base64 group id derived purely from the master key (no network). */
    fun groupIdForMasterKey(masterKey: ByteArray): String? = try {
        val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
        val idBytes = secret.publicParams.groupIdentifier.serialize()
        Base64.encodeToString(idBytes, Base64.NO_WRAP or Base64.URL_SAFE)
    } catch (t: Throwable) {
        Log.w(TAG, "groupId derivation failed", t)
        null
    }

    /**
     * Fetch + decrypt group state, served from a short-lived cache. Returns
     * null if the server params constant is unset or the fetch/decrypt fails
     * (callers fall back to a placeholder name + the senders they've seen).
     */
    suspend fun getOrFetch(masterKey: ByteArray): GroupInfo? {
        val gid = groupIdForMasterKey(masterKey) ?: return null
        cacheMutex.withLock {
            cache[gid]?.let {
                if (System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MS) return it.info
            }
        }
        val fresh = fetchGroupState(masterKey, gid) ?: return null
        cacheMutex.withLock { cache[gid] = CachedGroup(fresh, System.currentTimeMillis()) }
        return fresh
    }

    /**
     * Build a serialized `GroupSendFullToken` authorizing a multi-recipient
     * send to [recipientAcis] (a subset of the group). Returns null if we have
     * no endorsements / server params, or if the zkgroup calls fail — callers
     * then fall back to per-member authenticated fan-out.
     *
     * ⚠️ The zkgroup group-send-endorsement API
     * (`GroupSendEndorsementsResponse.receive`, `GroupSendEndorsement.combine`,
     * `toFullToken`) is version-sensitive; verify against libsignal-android
     * 0.86.5 on-device. Everything is wrapped so a mismatch degrades to
     * fan-out rather than crashing.
     */
    suspend fun buildGroupSendToken(masterKey: ByteArray, recipientAcis: List<String>): ByteArray? {
        val serverParams = serverPublicParams ?: return null
        val info = getOrFetch(masterKey) ?: return null
        val endorsementsBytes = info.endorsementsResponse ?: return null
        return runCatching {
            val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            val localAci = ServiceId.Aci(UUID.fromString(account.aci))
            val memberServiceIds = info.memberAcis.map { ServiceId.parseFromString(it) }
            val response = GroupSendEndorsementsResponse(endorsementsBytes)
            val received = response.receive(memberServiceIds, localAci, Instant.now(), secret, serverParams)
            val endorsementList = received.endorsements
            val recipientSet = recipientAcis.toHashSet()
            val selected = info.memberAcis.indices
                .filter { info.memberAcis[it] in recipientSet }
                .mapNotNull { endorsementList.getOrNull(it) }
            if (selected.isEmpty()) return@runCatching null
            val combined = GroupSendEndorsement.combine(selected)
            combined.toFullToken(secret, response.expiration).serialize()
        }.onFailure { Log.w(TAG, "group send token build failed", it) }.getOrNull()
    }

    private suspend fun fetchGroupState(masterKey: ByteArray, gid: String): GroupInfo? =
        withContext(Dispatchers.IO) {
            val serverParams = serverPublicParams ?: return@withContext null
            val pniStr = account.pni ?: run {
                Log.w(TAG, "no PNI on account — cannot build group auth credential")
                return@withContext null
            }
            try {
                Log.d(TAG, "fetchGroupState $gid: deriving params + fetching auth creds…")
                val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
                val publicParams = secret.publicParams

                // 1) Day-aligned auth credential for today (UTC).
                val todayStart = (System.currentTimeMillis() / 1000L / DAY_SECONDS) * DAY_SECONDS
                val creds = api.getGroupAuthCredentials(
                    login, password, todayStart, todayStart + 7 * DAY_SECONDS,
                )
                val todays = creds.credentials.firstOrNull { it.redemptionTime == todayStart }
                    ?: creds.credentials.firstOrNull()
                    ?: run { Log.w(TAG, "no group auth credential returned"); return@withContext null }
                Log.d(TAG, "fetchGroupState $gid: ${creds.credentials.size} creds; building presentation…")

                val authOps = ClientZkAuthOperations(serverParams)
                val aci = ServiceId.Aci(UUID.fromString(account.aci))
                val pni = ServiceId.Pni(UUID.fromString(pniStr))
                val authCred = authOps.receiveAuthCredentialWithPniAsServiceId(
                    aci,
                    pni,
                    todays.redemptionTime,
                    AuthCredentialWithPniResponse(Base64.decode(todays.credential, Base64.NO_WRAP)),
                )
                val presentation = authOps.createAuthCredentialPresentation(secret, authCred)
                Log.d(TAG, "fetchGroupState $gid: presentation built; fetching group state…")

                // 2) Fetch encrypted group state.
                val stateBytes = api.getGroupState(
                    groupPublicParamsHex = hex(publicParams.serialize()),
                    authPresentationHex = hex(presentation.serialize()),
                )
                // Current servers return a GroupResponse{group, endorsements};
                // older ones a bare Group. Try the wrapper, fall back.
                val response = runCatching { GroupResponse.parseFrom(stateBytes) }.getOrNull()
                val group = if (response != null && response.hasGroup()) response.group
                            else Group.parseFrom(stateBytes)
                Log.d(TAG, "fetchGroupState $gid: state ${stateBytes.size}b, ${group.membersList.size} member(s), titlePresent=${!group.title.isEmpty}")
                val endorsements = response?.groupSendEndorsementsResponse
                    ?.takeIf { !it.isEmpty }
                    ?.toByteArray()

                // 3) Decrypt title + members.
                val cipher = ClientZkGroupCipher(secret)
                val title = runCatching {
                    if (group.title.isEmpty) "" else {
                        val blobBytes = cipher.decryptBlob(group.title.toByteArray())
                        GroupAttributeBlob.parseFrom(blobBytes).title
                    }
                }.getOrDefault("")

                val members = group.membersList.mapNotNull { m ->
                    runCatching {
                        // ClientZkGroupCipher.decrypt(UuidCiphertext) → ServiceId.
                        // Use toServiceIdString() (bare UUID for an ACI), NOT
                        // toString() — the latter returns the debug form
                        // "<ACI:uuid>", which then gets sent verbatim in the
                        // /v2/keys/<id>/* prekey URL and 404s every group send,
                        // and also breaks the self-filter (it != account.aci).
                        cipher.decrypt(UuidCiphertext(m.userId.toByteArray())).toServiceIdString()
                    }.getOrNull()
                }

                Log.d(TAG, "group state: '${title.take(40)}' ${members.size} members rev=${group.revision}")
                GroupInfo(
                    masterKey = masterKey,
                    groupId = gid,
                    title = title.ifBlank { "Signal group" },
                    memberAcis = members,
                    revision = group.revision,
                    endorsementsResponse = endorsements,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "group state fetch/decrypt failed for $gid", t)
                null
            }
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "SignalGroups"
        const val ROOM_PREFIX = "sig:group:"
        private const val DAY_SECONDS = 86_400L
        private const val CACHE_TTL_MS = 10L * 60 * 1000  // 10 minutes

        /**
         * Signal's PRODUCTION zkgroup server public params for chat.signal.org —
         * a public (non-secret) cryptographic verification constant, identical
         * in every Signal client. Sourced from Signal-Desktop's
         * `config/production.json` `serverPublicParams`. If Signal ever rotates
         * it, update from the same place.
         */
        const val SERVER_PUBLIC_PARAMS_B64 =
            "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
    }
}
