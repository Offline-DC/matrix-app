package com.offline.dpadmessenger.backend.smarttxt

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Export logs" for Smart Txt: zips the [SmartTxtLogRing] window and uploads it
 * to the backend via the same presigned-S3 flow the launcher's diagnostics
 * "Submit logs" uses (`POST /diag-logs/upload-url` -> `PUT` to S3). The point is
 * for support to pull one user's Smart Txt logs on demand without walking them
 * through turning on the launcher diagnostics "rolling adb logs".
 *
 * Self-contained (rather than reusing the launcher's `DiagLogUploader`, which is
 * `internal` to the app module): same endpoint, same two-step upload, same
 * deviceId resolution — including reading the launcher's cached IMEI straight
 * from its SharedPreferences so a Smart Txt bundle lands under the *same* device
 * id as that device's diagnostics uploads. (At runtime this library is merged
 * into the launcher APK, so the prefs are shared; in the standalone demo harness
 * they're simply absent and we fall back to ANDROID_ID.)
 *
 * Synchronous; callers run it on Dispatchers.IO.
 */
internal object SmartTxtLogExporter {

    private const val TAG = "IMsgLogExport"

    // Mirrors DiagLogUploader / DeviceRegistrar.API_BASE.
    private const val API_BASE =
        "https://offline-dc-backend-ba4815b2bcc8.herokuapp.com/api/v1"

    // Launcher's DeviceRegistrar cache (read by name — same package at runtime).
    private const val REG_PREFS = "device_registration"
    private const val REG_KEY_IMEI = "last_imei"

    private val JSON_TYPE = "application/json".toMediaType()
    private val ZIP_TYPE = "application/zip".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Zip the Smart Txt log ring and upload it. Returns [Result.success] with
     * the server-assigned object key (a short reference support can look up), or
     * [Result.failure] with a throwable whose message is safe to Toast.
     */
    fun export(context: Context): Result<String> = try {
        Result.success(exportOrThrow(context))
    } catch (t: Throwable) {
        Log.w(TAG, "export failed", t)
        Result.failure(t)
    }

    private fun exportOrThrow(context: Context): String {
        SmartTxtLogRing.ensureStarted(context)   // no-op if already running
        SmartTxtLogRing.flush()

        val ringDir = SmartTxtLogRing.directory()
        val zipFile = File(context.cacheDir, "smarttxt-logs-${System.currentTimeMillis()}.zip")
        try {
            val logCount = zipLogs(ringDir, context, zipFile)
            if (logCount == 0) throw IOException("no Smart Txt logs collected yet")
            val sizeBytes = zipFile.length()
            Log.i(TAG, "Bundle ready: $logCount log files, $sizeBytes bytes")

            val deviceId = resolveDeviceId(context)
            val (uploadUrl, key) = requestUploadUrl(deviceId, sizeBytes)
            putToS3(zipFile, uploadUrl)
            Log.i(TAG, "Uploaded $key ($sizeBytes bytes)")
            return key
        } finally {
            zipFile.delete()
        }
    }

    /**
     * Zip a small meta.txt (device/app context) plus every `.log` file in the
     * ring. Returns the number of LOG files written (meta.txt excluded), so the
     * caller can refuse to upload a bundle with no actual logs.
     */
    private fun zipLogs(ringDir: File?, context: Context, dest: File): Int {
        var logCount = 0
        ZipOutputStream(FileOutputStream(dest).buffered()).use { zip ->
            try {
                zip.putNextEntry(ZipEntry("meta.txt"))
                zip.write(buildMeta(context).toByteArray())
                zip.closeEntry()
            } catch (t: Throwable) {
                Log.w(TAG, "meta.txt failed", t)
            }
            val files = ringDir
                ?.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            for (file in files) {
                try {
                    zip.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                    logCount++
                } catch (t: Throwable) {
                    Log.w(TAG, "skip ${file.name}", t)
                }
            }
        }
        return logCount
    }

    private fun buildMeta(context: Context): String {
        val pkg = context.packageName
        val versionName = try {
            context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
        } catch (_: Throwable) { "?" }
        return buildString {
            append("component=smarttxt-export\n")
            append("package=").append(pkg).append('\n')
            append("appVersion=").append(versionName).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android=").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("capturedAtMs=").append(System.currentTimeMillis()).append('\n')
        }
    }

    /** Launcher's cached IMEI when present (shared prefs, same package at
     *  runtime); ANDROID_ID otherwise. Matches DiagLogUploader's semantics so a
     *  device's Smart Txt and diagnostics uploads share one id. */
    @SuppressLint("HardwareIds")
    private fun resolveDeviceId(context: Context): String {
        try {
            val imei = context.getSharedPreferences(REG_PREFS, Context.MODE_PRIVATE)
                .getString(REG_KEY_IMEI, null)
            if (!imei.isNullOrBlank()) return imei
        } catch (_: Throwable) {}
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (androidId.isNullOrBlank()) "unknown" else "aid-$androidId"
    }

    /** Step 1: ask the backend for a presigned PUT URL. */
    private fun requestUploadUrl(deviceId: String, sizeBytes: Long): Pair<String, String> {
        val body = JSONObject().put("deviceId", deviceId).put("sizeBytes", sizeBytes)
        val request = Request.Builder()
            .url("$API_BASE/diag-logs/upload-url")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                Log.e(TAG, "upload-url failed: ${response.code} — $bodyStr")
                throw IOException("server refused upload (${response.code})")
            }
            val json = JSONObject(bodyStr)
            val uploadUrl = json.optString("uploadUrl")
            val key = json.optString("key")
            if (uploadUrl.isBlank() || key.isBlank()) throw IOException("malformed upload-url response")
            return uploadUrl to key
        }
    }

    /** Step 2: PUT the zip straight to S3 (Content-Type must be application/zip). */
    private fun putToS3(zipFile: File, uploadUrl: String) {
        val request = Request.Builder()
            .url(uploadUrl)
            .put(zipFile.asRequestBody(ZIP_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "S3 PUT failed: ${response.code} — ${response.body?.string()?.take(500)}")
                throw IOException("upload failed (${response.code})")
            }
        }
    }
}
