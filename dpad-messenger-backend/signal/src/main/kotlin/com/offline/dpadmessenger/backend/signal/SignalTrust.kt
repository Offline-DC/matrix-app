package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds an [OkHttpClient] whose [X509TrustManager] trusts both Android's
 * default CAs **and** Signal's own root certificate. Signal pins their
 * chat / CDN endpoints to a private "Open Whisper Systems" root that isn't
 * in any device trust store — without this you get
 * `CertPathValidatorException: Trust anchor for certification path not found`.
 *
 * Signal-Android, signald, and mautrix-signal all do the same thing.
 *
 * The cert lives at `signal/src/main/res/raw/signal_ca.pem`. See that file
 * for how to obtain / refresh it.
 *
 * ## Why the TLS stack is bundled (Conscrypt) rather than the platform's
 *
 * chat.signal.org holds two leaf certificates: Ed25519 and RSA. To a TLS 1.3
 * client it offers whichever the client can verify (modern Androids end up
 * with RSA); to a TLS 1.2 client it always sends the Ed25519 one. Android 8.1
 * (HIT K1) is TLS 1.2-only and its Conscrypt cannot parse Ed25519 keys, so the
 * chain check fails every time with
 * `Unacceptable certificate: Rejecting unknown key class X509PublicKey`.
 * The bundled Conscrypt speaks TLS 1.3, gets the RSA leaf, and validates it.
 * Same approach Signal-Android takes. The provider is used directly here and
 * NOT inserted into the global provider list, so only Signal traffic changes.
 * If its native library fails to load we fall back to the platform stack and
 * say so in logcat.
 */
object SignalTrust {

    private const val TAG = "SignalTrust"

    /** Bundled TLS provider, or null when it is unavailable on this device.
     *  On Android, Conscrypt omits its TrustManagerFactory unless asked (so a
     *  globally installed copy never silently replaces the system's); we use
     *  the provider directly, so ask for it. */
    private val bundledTls: Provider? by lazy {
        runCatching { Conscrypt.newProviderBuilder().provideTrustManager(true).build() }
            .onSuccess {
                val v = Conscrypt.version()
                Log.i(TAG, "TLS via bundled Conscrypt ${v.major()}.${v.minor()}.${v.patch()}")
            }
            .onFailure { Log.w(TAG, "bundled Conscrypt unavailable; using platform TLS", it) }
            .getOrNull()
    }

    fun buildOkHttp(context: Context): OkHttpClient {
        val provider = bundledTls
        val trustManager = compositeTrustManager(context, provider)
        val sslContext = when (provider) {
            null -> SSLContext.getInstance("TLS")
            else -> SSLContext.getInstance("TLS", provider)
        }.apply { init(null, arrayOf(trustManager), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun compositeTrustManager(context: Context, provider: Provider?): X509TrustManager {
        // 1. Load Signal's PEM-encoded root cert from res/raw.
        val signalCerts: List<X509Certificate> = runCatching {
            context.resources.openRawResource(R.raw.signal_ca).use { stream ->
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificates(stream).filterIsInstance<X509Certificate>()
            }
        }.getOrDefault(emptyList())

        // 2. Build a keystore containing Signal's certs + the system defaults.
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)  // grabs the system defaults
        val systemTm = tmf.trustManagers.first() as X509TrustManager
        systemTm.acceptedIssuers.forEachIndexed { i, cert ->
            ks.setCertificateEntry("system_$i", cert)
        }
        signalCerts.forEachIndexed { i, cert -> ks.setCertificateEntry("signal_$i", cert) }

        // 3. Re-derive a TrustManagerFactory from the combined keystore — from the
        //    bundled provider when we have it, so the chain check is the modern one.
        val combined = when (provider) {
            null -> TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            else -> TrustManagerFactory.getInstance("PKIX", provider)
        }
        combined.init(ks)
        return combined.trustManagers.first() as X509TrustManager
    }
}
