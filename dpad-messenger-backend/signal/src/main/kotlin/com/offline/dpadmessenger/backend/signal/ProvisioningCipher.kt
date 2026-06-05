package com.offline.dpadmessenger.backend.signal

import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kdf.HKDF
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the ProvisionEnvelope sent by the primary Signal device after
 * the user scans the QR code.
 *
 * Hand-implemented because libsignal-android 0.46 doesn't ship a
 * `ProvisioningCipher` class directly — that one lives in libsignal-
 * service-java (which we don't depend on, to keep the artifact set small).
 * What libsignal-android *does* expose is everything we need: ECC ops via
 * [Curve], HKDF via [HKDF]. The cipher format below matches
 * Signal-Android's own ProvisioningCipher.java exactly.
 *
 * Wire format of an encrypted envelope body:
 * ```
 * version(1) || iv(16) || ciphertext || hmac-sha256(32)
 * ```
 * where the HMAC covers `version || iv || ciphertext`.
 *
 * Key derivation:
 *   sharedSecret = ECDH(ourPrivate, theirPublic)
 *   derivedKeys  = HKDF(sharedSecret, "TextSecure Provisioning Message", 64)
 *   aesKey       = derivedKeys[0..32]
 *   macKey       = derivedKeys[32..64]
 *
 * @param ourPrivate the private half of the ephemeral keypair we generated
 *        at the start of provisioning. The matching public was sent in
 *        the sgnl:// link URL.
 */
class ProvisioningCipher(private val ourPrivate: ECPrivateKey) {

    fun decrypt(envelopePublicKey: ByteArray, envelopeBody: ByteArray): ByteArray {
        require(envelopeBody.size >= 1 + IV_LEN + 1 + MAC_LEN) {
            "Provisioning envelope too short: ${envelopeBody.size} bytes"
        }
        require(envelopeBody[0] == VERSION) {
            "Unsupported provisioning version: ${envelopeBody[0]}"
        }

        // libsignal 0.8x: ECC ops moved off the static `Curve` helper.
        // Decoding a 33-byte serialized point is now ECPublicKey(bytes);
        // ECDH is now ourPrivate.calculateAgreement(theirPublic).
        val theirPublic = ECPublicKey(envelopePublicKey)
        val sharedSecret = ourPrivate.calculateAgreement(theirPublic)
        val derived = HKDF.deriveSecrets(sharedSecret, INFO_BYTES, 64)
        val aesKey = derived.copyOfRange(0, 32)
        val macKey = derived.copyOfRange(32, 64)

        val macStart = envelopeBody.size - MAC_LEN
        val payload = envelopeBody.copyOfRange(0, macStart)
        val theirMac = envelopeBody.copyOfRange(macStart, envelopeBody.size)

        val ourMac = hmac(macKey, payload)
        check(MessageDigest.isEqual(ourMac, theirMac)) {
            "Provisioning MAC verification failed"
        }

        val iv = envelopeBody.copyOfRange(1, 1 + IV_LEN)
        val ciphertext = envelopeBody.copyOfRange(1 + IV_LEN, macStart)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    companion object {
        private const val VERSION: Byte = 0x01
        private const val IV_LEN = 16
        private const val MAC_LEN = 32
        private val INFO_BYTES = "TextSecure Provisioning Message".toByteArray()
    }
}
