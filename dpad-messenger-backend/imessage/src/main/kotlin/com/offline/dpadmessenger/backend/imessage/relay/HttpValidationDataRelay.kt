package com.offline.dpadmessenger.backend.imessage.relay

import android.util.Log
import com.offline.dpadmessenger.backend.imessage.HardwareConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for a real validation-data relay — the integration point for the
 * relay Jack is providing. This is a SKELETON: the request/response shape is a
 * best-guess matching the §2.6-option-2 design (POST the hardware identity,
 * receive base64 validation data). Adjust [REQUEST_PATH] and the JSON keys to
 * the relay's actual contract once it exists, then construct this in place of
 * [StubValidationDataRelay] in `RustPushBridge`/`IMessageBackendFactory`.
 *
 * Kept compiling (not commented out) so the OkHttp wiring is real and testable
 * against a local mock server.
 */
class HttpValidationDataRelay(
    private val baseUrl: String,
    /** Optional shared secret the relay requires (sent as a bearer token). */
    private val authToken: String? = null,
    private val client: OkHttpClient = defaultClient(),
) : ValidationDataRelay {

    override suspend fun health(): RelayHealth = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext RelayHealth.NOT_CONFIGURED
        try {
            client.newCall(get("$baseUrl/$HEALTH_PATH")).execute().use { resp ->
                if (resp.isSuccessful) RelayHealth.REACHABLE else RelayHealth.UNREACHABLE
            }
        } catch (t: Throwable) {
            Log.w(TAG, "health check failed: ${t.message}")
            RelayHealth.UNREACHABLE
        }
    }

    override suspend fun fetchValidationData(hardware: HardwareConfig): ValidationData =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw RelayException("relay base URL not configured")
            val payload = JSONObject().apply {
                put("platformSerialNumber", hardware.platformSerialNumber)
                put("mlb", hardware.mlb)
                put("rom", hardware.rom)
                put("productName", hardware.productName)
                put("osBuildNum", hardware.osBuildNum)
                put("deviceClass", hardware.deviceClass)
            }
            val req = post("$baseUrl/$REQUEST_PATH", payload.toString())
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw RelayException("relay HTTP ${resp.code}: ${body.take(200)}")
                    }
                    val b64 = JSONObject(body).optString("validationDataB64", "")
                    if (b64.isBlank()) throw RelayException("relay returned no validationDataB64")
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    Log.i(TAG, "relay issued ${bytes.size}B of validation data")
                    ValidationData(bytes = bytes)
                }
            } catch (e: RelayException) {
                throw e
            } catch (t: Throwable) {
                throw RelayException("relay request failed: ${t.message}", t)
            }
        }

    private fun get(url: String): Request =
        Request.Builder().url(url).apply { authHeader() }.get().build()

    private fun post(url: String, json: String): Request =
        Request.Builder().url(url).apply { authHeader() }
            .post(json.toRequestBody(JSON)).build()

    private fun Request.Builder.authHeader() {
        authToken?.let { header("Authorization", "Bearer $it") }
    }

    private companion object {
        const val TAG = "IMsgRelayHttp"
        const val HEALTH_PATH = "health"
        const val REQUEST_PATH = "validation-data"
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
