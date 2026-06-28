package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thin HTTPS client for the Signal chat server REST surface.
 *
 * For now this exposes one call — `confirmDevice` — which is what
 * finalises a newly-scanned QR link into a real Signal device record
 * on the server. Additional REST endpoints (one-time prekey top-up,
 * account self-fetch, etc.) can be added here as the receive loop
 * grows.
 *
 * Cross-reference: mautrix-signal `pkg/signalmeow/provisioning.go::
 * confirmDevice` and Signal-Android `org.whispersystems.signalservice.
 * internal.push.PushServiceSocket::finishDeviceLink`.
 */
class SignalApi(
    private val okHttp: OkHttpClient,
    private val baseUrl: String = "https://chat.signal.org",
) {

    /**
     * `PUT /v1/devices/link` — registers a new linked device with the
     * user's existing Signal account.
     *
     * The old `PUT /v1/devices/<provisioningCode>` endpoint was removed
     * (returns HTTP 405). The new endpoint takes the verification code
     * inside the JSON body (`verificationCode` field of
     * [ConfirmDeviceRequest]) instead of in the path. See mautrix-signal
     * `pkg/signalmeow/provisioning.go::confirmDevice`.
     *
     * Auth: HTTP Basic with the user's phone-number as username and the
     * password chosen by us (and committed to in this request body).
     *
     * On success the server returns a JSON body with the assigned
     * `deviceId`; we plumb that back so the caller can persist it on
     * the [SignalAccount].
     */
    suspend fun confirmDevice(
        phoneNumber: String,
        password: String,
        @Suppress("UNUSED_PARAMETER") provisioningCode: String,  // now lives in body
        body: ConfirmDeviceRequest,
    ): ConfirmDeviceResponse = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$phoneNumber:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val json = JSON.encodeToString(body)
        Log.d(TAG, "PUT $baseUrl/v1/devices/link body=$json")
        val request = Request.Builder()
            .url("$baseUrl/v1/devices/link")
            .put(json.toRequestBody("application/json".toMediaType()))
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()

        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: $respText")
        if (!response.isSuccessful) {
            throw IOException("confirmDevice failed: HTTP ${response.code} — $respText")
        }
        JSON.decodeFromString(ConfirmDeviceResponse.serializer(), respText)
    }

    /**
     * `PUT /v2/keys?identity={aci|pni}` — upload one-time prekeys + the
     * current signed/last-resort bundle for an identity. Called after
     * device confirmation so peers initiating a session against us can
     * fetch a bundle to seed the X3DH handshake.
     *
     * Auth: HTTP Basic with `<aci>.<deviceId>:<password>`.
     */
    suspend fun uploadPreKeys(
        login: String,           // "<aci>.<deviceId>"
        password: String,
        identity: String,         // "aci" or "pni"
        body: PreKeyUploadRequest,
    ): Boolean = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val json = JSON.encodeToString(body)
        Log.d(TAG, "PUT $baseUrl/v2/keys?identity=$identity body=${json.take(200)}…")
        val request = Request.Builder()
            .url("$baseUrl/v2/keys?identity=$identity")
            .put(json.toRequestBody("application/json".toMediaType()))
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()

        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: $respText")
        response.isSuccessful
    }

    /**
     * `GET /v2/keys/<aci>/[deviceId]` — fetch ALL device prekey bundles
     * for a recipient (pass "&#42;" for deviceId to ask the server for
     * every device at once) so we can bootstrap a Signal session via
     * SessionBuilder.
     * Each entry holds the recipient's signed prekey, optional one-time
     * prekey, and (post-PQXDH) Kyber prekey. Returned in JSON; the wire
     * shape is hand-modeled below in [PreKeyBundleResponse].
     */
    suspend fun fetchPreKeys(
        login: String,            // "<aci>.<deviceId>"
        password: String,
        recipientServiceId: String,
    ): PreKeyBundleResponse = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val url = "$baseUrl/v2/keys/$recipientServiceId/*"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: ${respText.take(400)}…")
        if (!response.isSuccessful) {
            throw IOException("fetchPreKeys failed: HTTP ${response.code} — $respText")
        }
        JSON.decodeFromString(PreKeyBundleResponse.serializer(), respText)
    }

    /**
     * `GET /v1/certificate/delivery` — fetch our SenderCertificate, which
     * we embed inside every sealed-sender envelope we encrypt so the
     * recipient can verify "yes, the Signal server vouched that this
     * sender is who they claim". Cert lives ~24h; cache it client-side.
     */
    suspend fun fetchSenderCertificate(
        login: String,
        password: String,
    ): SenderCertificateResponse = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val url = "$baseUrl/v1/certificate/delivery"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: ${respText.take(200)}…")
        if (!response.isSuccessful) {
            throw IOException("fetchSenderCertificate failed: HTTP ${response.code} — $respText")
        }
        JSON.decodeFromString(SenderCertificateResponse.serializer(), respText)
    }

    /**
     * `GET https://cdnN.signal.org/attachments/<cdnKey>` — download an
     * encrypted attachment blob from Signal's CDN. Used by contact sync
     * (SyncMessage.Contacts) and by inbound media in DataMessage.
     *
     * Signal runs several CDN versions (0 → `cdn.signal.org`, 2 →
     * `cdn2.signal.org`, 3 → `cdn3.signal.org`). All share the same
     * `/attachments/<key>` download path; cdn3's TUS-style path is upload-
     * only. Auth is plain HTTP Basic with the chat-server account creds
     * for every version — verified in mautrix-signal's
     * `pkg/signalmeow/attachments.go::DownloadAttachment` which calls
     * `web.GetAttachment(path, cdnNumber)` with the same auth shape for
     * all CDNs. There is NO separate "fetch read credentials" round-trip.
     *
     * Path-encoding gotcha: cdnKey can contain `/` characters (Signal
     * keys are subdirectory-style like `aabb/ccdd/eeff…`). The slashes
     * are real path separators on the CDN, not characters to url-encode.
     * Build the URL through HttpUrl.Builder and `addPathSegment` each
     * piece individually so OkHttp encodes the segment contents but
     * preserves the slashes between them.
     *
     * Returns the raw encrypted bytes; decryption + MAC verification is
     * the caller's job (see [SignalAttachmentCrypto]).
     */
    suspend fun downloadAttachment(
        login: String,
        password: String,
        cdnNumber: Int,
        cdnKey: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val host = when (cdnNumber) {
            0 -> "cdn.signal.org"
            2 -> "cdn2.signal.org"
            3 -> "cdn3.signal.org"
            else -> "cdn$cdnNumber.signal.org"
        }
        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegment("attachments")
        cdnKey.split("/").forEach { if (it.isNotEmpty()) urlBuilder.addPathSegment(it) }
        val url = urlBuilder.build()

        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val bytes = response.body?.bytes() ?: ByteArray(0)
        Log.d(TAG, "→ HTTP ${response.code} (${bytes.size} bytes)")
        if (!response.isSuccessful) {
            // Include the response body so we see the CDN's actual error JSON.
            val bodyPreview = bytes.decodeToString().take(200)
            throw IOException("downloadAttachment failed: HTTP ${response.code} — $bodyPreview")
        }
        bytes
    }

    /**
     * `GET /v1/profile/<serviceId>` — fetch a user's profile. The `name` field
     * is the AES-GCM-encrypted display name (base64); decrypt it with the
     * contact's profile key via [SignalProfileCipher]. Returns null if there's
     * no name or the request fails.
     *
     * NOTE: this is the unversioned profile endpoint. If modern accounts stop
     * returning `name` here, the versioned `/v1/profile/<aci>/<version>` form
     * (zkgroup ProfileKeyVersion) is the fallback to wire up.
     */
    suspend fun fetchProfileEncryptedName(
        login: String,
        password: String,
        serviceId: String,
        /** zkgroup ProfileKeyVersion (hex). When set, hits the VERSIONED
         *  endpoint which actually returns the encrypted `name`; the
         *  unversioned endpoint omits it on modern accounts. */
        version: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val url = if (version != null) "$baseUrl/v1/profile/$serviceId/$version"
                  else "$baseUrl/v1/profile/$serviceId"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "fetchProfile $serviceId → HTTP ${response.code}; body=${respText.take(400)}")
        if (!response.isSuccessful) return@withContext null
        val name = runCatching {
            JSON.decodeFromString(ProfileResponse.serializer(), respText).name
        }.getOrNull()
        Log.d(TAG, "fetchProfile $serviceId → name field ${if (name.isNullOrBlank()) "ABSENT/blank" else "present (${name.length} b64 chars)"}")
        name?.takeIf { it.isNotBlank() }
    }

    /**
     * `GET /v1/certificate/auth/group?redemptionStartSeconds=&redemptionEndSeconds=`
     * — fetch weekly zkgroup auth credentials. Each entry pairs a day-aligned
     * `redemptionTime` (unix seconds) with a base64 `AuthCredentialWithPni`
     * response that zkgroup turns into a group-server auth presentation.
     * Reference: Signal-Android `PushServiceSocket` GROUPSV2_CREDENTIAL.
     */
    suspend fun getGroupAuthCredentials(
        login: String,
        password: String,
        fromSeconds: Long,
        toSeconds: Long,
    ): GroupCredentialsResponse = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val url = "$baseUrl/v1/certificate/auth/group?redemptionStartSeconds=$fromSeconds&redemptionEndSeconds=$toSeconds"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: ${respText.take(200)}…")
        if (!response.isSuccessful) {
            throw IOException("getGroupAuthCredentials failed: HTTP ${response.code} — $respText")
        }
        JSON.decodeFromString(GroupCredentialsResponse.serializer(), respText)
    }

    /**
     * `GET https://storage.signal.org/v1/groups/` — fetch the encrypted
     * GroupsV2 state. Auth is HTTP Basic where the username is the hex of the
     * group's public params and the password is the hex of the zkgroup auth
     * presentation (NOT the account login). Returns the raw `Group` protobuf
     * bytes; member ids + title are still zkgroup-encrypted (see
     * [SignalGroups]). Reference: Signal-Android `PushServiceSocket
     * .getGroupsV2Group` / mautrix-signal `pkg/signalmeow/groupsv2.go`.
     */
    suspend fun getGroupState(
        groupPublicParamsHex: String,
        authPresentationHex: String,
        groupsBaseUrl: String = "https://storage.signal.org",
    ): ByteArray = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$groupPublicParamsHex:$authPresentationHex".toByteArray(),
            Base64.NO_WRAP,
        )
        val url = "$groupsBaseUrl/v1/groups/"
        Log.d(TAG, "GET $url (groupsV2 state)")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("Content-Type", "application/x-protobuf")
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val bytes = response.body?.bytes() ?: ByteArray(0)
        Log.d(TAG, "→ HTTP ${response.code} (${bytes.size} bytes group state)")
        if (!response.isSuccessful) {
            throw IOException("getGroupState failed: HTTP ${response.code} — ${bytes.decodeToString().take(160)}")
        }
        bytes
    }

    /**
     * `GET /v4/attachments/form/upload` — ask the server for a one-shot
     * CDN upload slot. Returns the cdn number, the opaque `key` that will
     * become the attachment's `cdnKey`, the resumable upload URL, and the
     * headers to replay on the create request. Mirrors mautrix-signal
     * `pkg/signalmeow/web::GetAttachmentUploadForm`.
     */
    suspend fun getAttachmentUploadForm(
        login: String,
        password: String,
    ): AttachmentUploadForm = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val url = "$baseUrl/v4/attachments/form/upload"
        Log.d(TAG, "GET $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: ${respText.take(300)}…")
        if (!response.isSuccessful) {
            throw IOException("getAttachmentUploadForm failed: HTTP ${response.code} — $respText")
        }
        JSON.decodeFromString(AttachmentUploadForm.serializer(), respText)
    }

    /**
     * Upload [encrypted] (the `iv || ciphertext || mac` blob) to the CDN
     * slot described by [form], using the TUS resumable protocol Signal's
     * cdn3 speaks: a creation POST (with the server-supplied headers) yields
     * a `Location`, then a single PATCH streams the bytes at offset 0.
     *
     * Reference: mautrix-signal `web/cdn.go` (`uploadAttachment` →
     * `tus.NewClient`/`CreateUpload`/`uploadChunck`). Signal-Android does the
     * same via `ResumableUploadSpec`. Throws on any non-2xx.
     *
     * NOTE: this path needs on-device verification — the sandbox can't reach
     * the CDN. The download/decrypt side is exercised by contact sync already.
     */
    suspend fun uploadAttachment(
        form: AttachmentUploadForm,
        encrypted: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        // 1) TUS create — POST the resumable location with the form headers.
        val createBuilder = Request.Builder()
            .url(form.signedUploadLocation)
            .post(ByteArray(0).toRequestBody())
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", encrypted.size.toString())
            .header("User-Agent", "DPADMessenger/0.1")
        form.headers.forEach { (k, v) -> createBuilder.header(k, v) }
        val createResp = okHttp.newCall(createBuilder.build()).execute()
        val createBody = createResp.body?.string().orEmpty()
        Log.d(TAG, "TUS create → HTTP ${createResp.code} loc=${createResp.header("Location")}")
        if (!createResp.isSuccessful) {
            throw IOException("attachment TUS create failed: HTTP ${createResp.code} — $createBody")
        }
        // Location may be absolute or relative to the create URL.
        val locationHeader = createResp.header("Location")
            ?: throw IOException("attachment TUS create returned no Location header")
        val base = form.signedUploadLocation.toHttpUrlOrNull()
            ?: throw IOException("bad signedUploadLocation '${form.signedUploadLocation}'")
        val patchUrl = base.resolve(locationHeader)
            ?: throw IOException("could not resolve TUS Location '$locationHeader'")

        // 2) TUS upload — PATCH the bytes at offset 0 in one shot.
        val patchBuilder = Request.Builder()
            .url(patchUrl)
            .patch(encrypted.toRequestBody("application/offset+octet-stream".toMediaType()))
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Offset", "0")
            .header("User-Agent", "DPADMessenger/0.1")
        form.headers.forEach { (k, v) -> patchBuilder.header(k, v) }
        val patchResp = okHttp.newCall(patchBuilder.build()).execute()
        val patchBody = patchResp.body?.string().orEmpty()
        Log.d(TAG, "TUS upload → HTTP ${patchResp.code} (${encrypted.size} bytes)")
        if (!patchResp.isSuccessful) {
            throw IOException("attachment TUS upload failed: HTTP ${patchResp.code} — $patchBody")
        }
    }

    /**
     * `PUT /v1/messages/<aci>` — submit one or more encrypted envelopes
     * for delivery to the recipient's devices. Body wraps an array of
     * device-targeted envelope blobs. We send one per recipient device.
     *
     * Returns the raw HTTP status + body. A 409 means we have stale
     * device IDs and need to call [fetchPreKeys] again; a 410 means a
     * session is bad and needs rebuild. The caller inspects the status.
     */
    suspend fun sendMessage(
        login: String,
        password: String,
        recipientServiceId: String,
        body: SendMessageRequest,
    ): SendMessageResult = withContext(Dispatchers.IO) {
        val authHeader = "Basic " + Base64.encodeToString(
            "$login:$password".toByteArray(),
            Base64.NO_WRAP,
        )
        val json = JSON.encodeToString(body)
        val url = "$baseUrl/v1/messages/$recipientServiceId"
        Log.d(TAG, "PUT $url body=${json.take(400)}…")
        val request = Request.Builder()
            .url(url)
            .put(json.toRequestBody("application/json".toMediaType()))
            .header("Authorization", authHeader)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: $respText")
        SendMessageResult(response.code, respText)
    }

    /**
     * `PUT /v1/messages/multi_recipient` — submit ONE sealed-sender
     * multi-recipient blob (from `SealedSessionCipher.multiRecipientEncrypt`)
     * that the server fans out to every recipient device. This is the
     * large-group optimization: a single request instead of one per member.
     *
     * Authorized by a zkgroup `Group-Send-Token` (no account auth — it's
     * sealed sender). Content-Type is Signal's multi-recipient-message media
     * type. Reference: Signal-Android `PushServiceSocket.sendGroupMessage`.
     */
    suspend fun sendMultiRecipient(
        serializedBlob: ByteArray,
        timestamp: Long,
        groupSendTokenB64: String,
        online: Boolean = false,
        urgent: Boolean = true,
    ): SendMessageResult = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v1/messages/multi_recipient?ts=$timestamp&online=$online&urgent=$urgent"
        Log.d(TAG, "PUT $url (${serializedBlob.size} bytes mrm)")
        val request = Request.Builder()
            .url(url)
            .put(serializedBlob.toRequestBody(MRM_MEDIA_TYPE.toMediaType()))
            .header("Group-Send-Token", groupSendTokenB64)
            .header("X-Signal-Agent", "DPADMSG")
            .header("User-Agent", "DPADMessenger/0.1")
            .build()
        val response = okHttp.newCall(request).execute()
        val respText = response.body?.string().orEmpty()
        Log.d(TAG, "→ HTTP ${response.code}: ${respText.take(200)}")
        SendMessageResult(response.code, respText)
    }

    /**
     * `GET /v2/directory/auth` — fetch short-lived credentials for the Contact
     * Discovery Service (CDSI). The returned username/password authenticate the
     * subsequent `cdsiLookup` against `cdsi.signal.org` (the actual enclave
     * handshake + attestation is handled inside libsignal's `Network`). Returns
     * null on any failure so discovery degrades to "number not resolvable".
     *
     * Reference: Signal-Android `PushServiceSocket.getCdsiAuthorization()`.
     */
    suspend fun getCdsiAuth(login: String, password: String): CdsiAuthResponse? =
        withContext(Dispatchers.IO) {
            val authHeader = "Basic " + Base64.encodeToString(
                "$login:$password".toByteArray(),
                Base64.NO_WRAP,
            )
            val url = "$baseUrl/v2/directory/auth"
            Log.d(TAG, "GET $url")
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", authHeader)
                .header("User-Agent", "DPADMessenger/0.1")
                .build()
            val response = okHttp.newCall(request).execute()
            val respText = response.body?.string().orEmpty()
            Log.d(TAG, "getCdsiAuth → HTTP ${response.code}: ${respText.take(120)}")
            if (!response.isSuccessful) return@withContext null
            runCatching {
                JSON.decodeFromString(CdsiAuthResponse.serializer(), respText)
            }.getOrNull()
        }

    companion object {
        private const val TAG = "SignalApi"
        private const val MRM_MEDIA_TYPE = "application/vnd.signal-messenger.mrm"
        private val JSON = Json {
            ignoreUnknownKeys = true
            // Signal's server checks for explicit presence of fields
            // like `fetchesMessages` and the `capabilities` map even
            // when set to default values — omitting them returns 400.
            encodeDefaults = true
        }
    }
}

/** Result of a PUT /v1/messages call — kept loose because Signal's body
 *  varies between success and various 4xx conditions (mismatched devices,
 *  stale registration ID, etc.). */
data class SendMessageResult(val httpStatus: Int, val rawBody: String) {
    val isSuccess: Boolean get() = httpStatus in 200..299
}

/** GET /v2/directory/auth response — short-lived CDSI credentials. */
@Serializable
data class CdsiAuthResponse(
    val username: String,
    val password: String,
)

/** GET /v1/profile/&lt;serviceId&gt; response (subset). `name` is the
 *  base64 AES-GCM-encrypted display name. */
@Serializable
data class ProfileResponse(
    val name: String? = null,
    val about: String? = null,
)

/** GET /v2/auth response — weekly zkgroup auth credentials. */
@Serializable
data class GroupCredentialsResponse(
    val credentials: List<GroupCredential> = emptyList(),
    val pni: String? = null,
)

@Serializable
data class GroupCredential(
    /** Base64-encoded AuthCredentialWithPniResponse. */
    val credential: String,
    /** Day-aligned unix-seconds this credential is valid for. */
    val redemptionTime: Long,
)

/**
 * GET /v4/attachments/form/upload response. `key` becomes the attachment's
 * `cdnKey`; `cdn` its `cdnNumber`. `headers` must be replayed on the TUS
 * create request; `signedUploadLocation` is the resumable upload endpoint.
 */
@Serializable
data class AttachmentUploadForm(
    val cdn: Int,
    val key: String,
    val headers: Map<String, String> = emptyMap(),
    val signedUploadLocation: String,
)

/** GET /v2/keys/&lt;aci&gt; response (all devices). */
@Serializable
data class PreKeyBundleResponse(
    val identityKey: String,
    val devices: List<DevicePreKeyBundle>,
)

@Serializable
data class DevicePreKeyBundle(
    val deviceId: Int,
    val registrationId: Int,
    val signedPreKey: ServerSignedPreKey,
    val preKey: ServerOneTimePreKey? = null,
    val pqPreKey: ServerSignedPreKey? = null,
)

@Serializable
data class ServerSignedPreKey(
    val keyId: Int,
    val publicKey: String,
    val signature: String,
)

@Serializable
data class ServerOneTimePreKey(
    val keyId: Int,
    val publicKey: String,
)

/** GET /v1/certificate/delivery response. */
@Serializable
data class SenderCertificateResponse(
    /** Base64-encoded serialized SenderCertificate proto. */
    val certificate: String,
)

/** PUT /v1/messages/&lt;aci&gt; request body. */
@Serializable
data class SendMessageRequest(
    /** One entry per target device. */
    val messages: List<OutgoingMessage>,
    /** Sender-chosen timestamp in milliseconds. Used to dedupe + ack. */
    val timestamp: Long,
    /** `true` means recipient should be poked even if not connected. */
    val online: Boolean = false,
    val urgent: Boolean = true,
)

@Serializable
data class OutgoingMessage(
    /** Signal envelope type: 3 = PREKEY_MESSAGE, 1 = DOUBLE_RATCHET,
     *  6 = UNIDENTIFIED_SENDER (sealed). */
    val type: Int,
    val destinationDeviceId: Int,
    val destinationRegistrationId: Int,
    /** Base64-encoded encrypted Envelope.content. */
    val content: String,
)

/**
 * Wire format for `PUT /v2/keys`. `identityKey` is the long-term ACI/PNI
 * public key (base64-encoded); the server uses it to verify the signed
 * prekey signature.
 */
@Serializable
data class PreKeyUploadRequest(
    val identityKey: String,
    val signedPreKey: SignedPreKeyJson,
    val preKeys: List<OneTimePreKeyJson>,
    val pqLastResortKey: SignedPreKeyJson? = null,
    val pqPreKeys: List<SignedPreKeyJson> = emptyList(),
)

/** One-time Curve25519 prekey wire format (no signature — covered by the signed prekey). */
@Serializable
data class OneTimePreKeyJson(
    val keyId: Int,
    val publicKey: String,
)

/**
 * JSON wire format for `PUT /v1/devices/<code>`. Field names match the
 * server's expectations exactly; do NOT rename them without checking
 * mautrix-signal or Signal-Android's `confirmDevice` impl.
 *
 * `name` is normally an encrypted device-name blob; we send null until
 * we wire up the device-name cipher. The primary phone's "Linked
 * Devices" list will show "Unknown Device" in that case.
 */
@Serializable
data class ConfirmDeviceRequest(
    val verificationCode: String,
    val accountAttributes: AccountAttributes,
    val aciSignedPreKey: SignedPreKeyJson,
    val pniSignedPreKey: SignedPreKeyJson,
    val aciPqLastResortPreKey: SignedPreKeyJson,
    val pniPqLastResortPreKey: SignedPreKeyJson,
)

@Serializable
data class AccountAttributes(
    val fetchesMessages: Boolean = true,
    val registrationId: Int,
    val pniRegistrationId: Int,
    val name: String? = null,
    val capabilities: Capabilities = Capabilities(),
)

/**
 * Capabilities the linked device claims to support. Names must match
 * Signal-Server's [`DeviceCapability`][1] enum exactly — unknown keys
 * are at best ignored, and missing a `requireForNewDevices = true`
 * capability causes the link to fail with HTTP 409 Conflict.
 *
 * As of 2026, the only capability flagged required-for-new-devices is
 * `spqr` (Sparse Post-Quantum Ratchet). `attachmentBackfill` is sent
 * by mautrix-signal to match the official desktop client; we mirror it
 * even though it's not strictly required to link.
 *
 * Deprecated keys that USED to be required but Signal-Server no longer
 * recognises and which cause 409 if sent: `deleteSync`,
 * `versionedExpirationTimer`. Do not add them back without verifying
 * against the current [`DeviceCapability`][1] enum.
 *
 * [1]: https://github.com/signalapp/Signal-Server/blob/main/service/
 * src/main/java/org/whispersystems/textsecuregcm/storage/
 * DeviceCapability.java
 */
@Serializable
data class Capabilities(
    val attachmentBackfill: Boolean = true,
    val spqr: Boolean = true,
)

@Serializable
data class ConfirmDeviceResponse(
    val deviceId: Int,
    val uuid: String? = null,
    val pni: String? = null,
)
