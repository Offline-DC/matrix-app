package com.offline.dpadmessenger.backend.signal

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
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
 */
object SignalTrust {

    fun buildOkHttp(context: Context): OkHttpClient {
        val trustManager = compositeTrustManager(context)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun compositeTrustManager(context: Context): X509TrustManager {
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

        // 3. Re-derive a TrustManagerFactory from the combined keystore.
        val combined = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        combined.init(ks)
        return combined.trustManagers.first() as X509TrustManager
    }
}
