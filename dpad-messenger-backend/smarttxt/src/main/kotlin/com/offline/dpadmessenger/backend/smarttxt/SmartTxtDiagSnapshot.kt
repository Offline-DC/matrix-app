package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The `# state` section of a Smart Txt export's meta.txt: what the phone's Smart Txt
 * state IS at export time, so a bundle can be diagnosed without a bench phone.
 *
 * Built from the 2026-10 "APS not ready" investigation (Connor, IMEI …7870), where each of
 * these had to be reconstructed by hand:
 *  - which identity (dumb) the phone has — QR codes are shared by up to 20 phones;
 *  - which login files exist and since when, and which keystore aliases survive;
 *  - the client UDID and when os_config.plist was written (his dated from a first boot
 *    with the clock at 2026-03-13);
 *  - Kotlin's "signed in" record vs what the native client actually holds (a mismatch
 *    showed "signed in" while sends went nowhere);
 *  - push-cert-rejected / registration state, network reachability of Apple's push
 *    host, and the phone's clock settings.
 *
 * Read-only, IO-thread only (the exporters already run on Dispatchers.IO). Every probe is
 * wrapped so one failure can never cost the bundle. No credentials, tokens, Apple ID or
 * full handles: identity and UDID appear only as short hashes/prefixes.
 */
internal object SmartTxtDiagSnapshot {

    private val LOGIN_FILES = listOf(
        "config.plist", "keystore.plist", "creds.json", "id_cache.plist", "os_config.plist", "dumb",
    )
    private const val APS_HOST = "courier.push.apple.com"
    private const val APS_PORT = 5223

    fun build(context: Context): String = buildString {
        val app = context.applicationContext
        val dir = app.filesDir
        append("\n# state (SmartTxtDiagSnapshot)\n")
        section { identity(dir) }
        section { files(dir) }
        section { keystore(dir) }
        section { udid(dir) }
        section { signedIn(app) }
        section { deepReset(dir) }
        section { clock(app) }
        section { network(app) }
    }

    private inline fun StringBuilder.section(block: StringBuilder.() -> Unit) {
        try { block() } catch (t: Throwable) {
            append("stateError=").append(t.javaClass.simpleName).append(": ").append(t.message).append('\n')
        }
    }

    private fun iso(ms: Long): String =
        if (ms <= 0L) "-" else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))

    private fun sha12(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)

    /** sha12 of the dumb's TEXT exactly as written by provisioning — the same value
     *  `smarttxt-identity-bench-test.sh` prints, so it maps straight to a qr_codes row. */
    private fun StringBuilder.identity(dir: File) {
        val dumb = File(dir, "dumb")
        if (!dumb.exists() || dumb.length() == 0L) { append("identity=MISSING (no dumb)\n"); return }
        val text = dumb.readText().trim()
        append("identitySha12=").append(sha12(text.toByteArray())).append('\n')
        append("identityChars=").append(text.length).append('\n')
    }

    private fun StringBuilder.files(dir: File) {
        for (name in LOGIN_FILES) {
            val f = File(dir, name)
            append("file.").append(name).append('=')
            if (f.exists()) append(f.length()).append("B mtime=").append(iso(f.lastModified())) else append("absent")
            append('\n')
        }
        val anis = File(dir, "anisette")
        val anisFiles = anis.walkTopDown().filter { it.isFile }.toList()
        append("file.anisette/=")
        if (anis.exists()) append(anisFiles.size).append(" file(s) ").append(anisFiles.sumOf { it.length() })
            .append("B newest=").append(iso(anisFiles.maxOfOrNull { it.lastModified() } ?: 0L))
        else append("absent")
        append('\n')
    }

    /** Alias NAMES only (e.g. `activation:HH39VWH722`) — never key material. Two
     *  `activation:` aliases, or one for another serial, means state survived a sign-out. */
    private fun StringBuilder.keystore(dir: File) {
        val f = File(dir, "keystore.plist")
        if (!f.exists()) { append("keystoreAliases=absent\n"); return }
        val aliases = Regex("<key>([a-z][a-z0-9_-]*:[^<]{1,80})</key>").findAll(f.readText())
            .map { it.groupValues[1] }.distinct().toList()
        append("keystoreAliases=").append(aliases.size).append(' ').append(aliases.joinToString(",")).append('\n')
    }

    private fun StringBuilder.udid(dir: File) {
        val f = File(dir, "os_config.plist")
        if (!f.exists()) { append("clientUdid=absent\n"); return }
        val udid = Regex("[0-9a-fA-F]{64}").find(f.readText())?.value
        append("clientUdid=").append(udid?.take(12)?.plus("…") ?: "none").append(" (os_config.plist written ")
            .append(iso(f.lastModified())).append(")\n")
    }

    /** Kotlin's record vs the native client. MISMATCH = the UI says signed in but the
     *  native side has no registration (or the reverse). */
    private fun StringBuilder.signedIn(app: Context) {
        val store = SmartTxtAccountStore(app)
        val kRegistered = runCatching { store.isRegistered() }.getOrNull()
        append("account.isRegistered=").append(kRegistered).append('\n')
        append("account.isSeeded=").append(runCatching { store.isSeeded() }.getOrNull()).append('\n')
        append("account.lastRegistered=").append(iso(runCatching { store.lastRegisteredMs() }.getOrDefault(0L))).append('\n')
        append("account.handlesConfigured=").append(runCatching { store.handlesConfigured() }.getOrNull()).append('\n')
        append("ui.status=").append(SmartTxtRepository.status.value).append('\n')
        append("ui.pushCertRejected=").append(SmartTxtRepository.pushCertRejected.value).append('\n')
        append("native.loaded=").append(RustPushNative.loaded).append('\n')
        if (!RustPushNative.loaded) return
        val nRegistered = runCatching { RustPushNative.nativeIsRegistered() }.getOrNull()
        append("native.isRegistered=").append(nRegistered).append('\n')
        append("native.hasClient=").append(runCatching { RustPushNative.nativeHasClient() }.getOrNull()).append('\n')
        append("native.isConnected=").append(runCatching { RustPushNative.nativeIsConnected() }.getOrNull()).append('\n')
        append("native.registerState=").append(RustPushNative.runCatchingNativeRegisterState().take(300)).append('\n')
        append("native.handleCount=").append(RustPushNative.runCatchingNativeHandles().size).append('\n')
        if (kRegistered != null && nRegistered != null && kRegistered != nRegistered) {
            append("signedInMismatch=YES — account store says registered=$kRegistered, native says $nRegistered\n")
        } else {
            append("signedInMismatch=no\n")
        }
    }

    /** Written by the launcher's Diagnostics "smart txt deep reset" (SmartTxtDeepReset). */
    private fun StringBuilder.deepReset(dir: File) {
        val last = File(dir, "smarttxt_deep_reset.last").takeIf { it.exists() }?.readText()?.trim()
        append("deepResetLast=").append(last ?: "never").append('\n')
        append("deepResetPending=").append(File(dir, "smarttxt_deep_reset.pending").exists()).append('\n')
    }

    private fun StringBuilder.clock(app: Context) {
        val cr = app.contentResolver
        append("clock.now=").append(iso(System.currentTimeMillis())).append(" tz=").append(TimeZone.getDefault().id).append('\n')
        append("clock.autoTime=").append(runCatching { Settings.Global.getInt(cr, Settings.Global.AUTO_TIME) }.getOrNull())
            .append(" autoTimeZone=").append(runCatching { Settings.Global.getInt(cr, Settings.Global.AUTO_TIME_ZONE) }.getOrNull()).append('\n')
        append("clock.uptime=").append(SystemClock.elapsedRealtime() / 1000).append("s bootCount=")
            .append(runCatching { Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT) }.getOrNull()).append('\n')
        val jumps = runCatching { SmartTxtEvents.file(app).readLines().count { " CLOCK_JUMP " in it } }.getOrDefault(0)
        append("clock.jumpsRecorded=").append(jumps).append(" (see events.txt)\n")
    }

    /** Separates "Apple refused us" (APSConnectError) from "we couldn't reach Apple". */
    private fun StringBuilder.network(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        append("net.transport=").append(transport)
            .append(" validated=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n')
        val t0 = SystemClock.elapsedRealtime()
        val addr = runCatching { InetAddress.getByName(APS_HOST) }
        val dnsMs = SystemClock.elapsedRealtime() - t0
        append("net.dns.").append(APS_HOST).append('=')
            .append(addr.fold({ "ok ${dnsMs}ms" }, { "FAIL ${dnsMs}ms ${it.javaClass.simpleName}" })).append('\n')
        val ip = addr.getOrNull() ?: return
        val t1 = SystemClock.elapsedRealtime()
        val tcp = runCatching { Socket().use { it.connect(InetSocketAddress(ip, APS_PORT), 4000) } }
        val tcpMs = SystemClock.elapsedRealtime() - t1
        append("net.tcp.").append(APS_HOST).append(':').append(APS_PORT).append('=')
            .append(tcp.fold({ "ok ${tcpMs}ms" }, { "FAIL ${tcpMs}ms ${it.javaClass.simpleName}" })).append('\n')
    }
}
