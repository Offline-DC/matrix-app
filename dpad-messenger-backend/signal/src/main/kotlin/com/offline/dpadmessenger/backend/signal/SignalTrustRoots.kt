package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.ecc.ECPublicKey

/**
 * Signal's production "Unidentified Delivery" trust root public keys.
 *
 * These are the long-term ECDSA public keys whose private halves the
 * Signal server uses to sign each per-device [SenderCertificate]. We
 * need them to validate the sender-certificate chain embedded inside
 * every sealed-sender envelope; without this the
 * [org.signal.libsignal.metadata.SealedSessionCipher] cannot accept
 * any inbound message from a known contact (because Signal's default
 * is to send everything sealed-sender).
 *
 * Signal maintains an array of valid trust roots so they can rotate
 * the signing key without breaking existing clients. The set below is
 * the production set from Signal-Android's `app/build.gradle.kts`
 * `UNIDENTIFIED_SENDER_TRUST_ROOTS` build config field as of late 2026.
 * If linking succeeds but inbound sealed messages start failing with
 * `InvalidMetadataMessage`, refresh this list from the upstream
 * build.gradle.kts.
 */
object SignalTrustRoots {

    /**
     * Production trust roots from Signal-Android. URL:
     * https://github.com/signalapp/Signal-Android/blob/main/app/build.gradle.kts
     * (search `UNIDENTIFIED_SENDER_TRUST_ROOTS`).
     */
    private val PRODUCTION_BASE64 = listOf(
        "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
        "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
    )

    /**
     * A single [CertificateValidator] that accepts certificates signed
     * by ANY of the trust roots above. libsignal only accepts one
     * trust-root per validator, so we hand back the list and let the
     * caller try each one until decrypt succeeds.
     */
    val productionValidators: List<CertificateValidator> by lazy {
        PRODUCTION_BASE64.map { b64 ->
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            CertificateValidator(ECPublicKey(bytes))
        }
    }
}
