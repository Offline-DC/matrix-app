package com.offline.dpadmessenger.backend.signal

import android.util.Base64
import android.util.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a linked-device display name the way Signal expects, so the primary
 * device can decrypt it and show e.g. "Dumbphone 2" in Settings → Linked
 * Devices (instead of "Unnamed Device" when no name is sent).
 *
 * Scheme (verified against Signal-Android `DeviceNameCipher.kt`):
 *  1. masterSecret = ECDH(ephemeral_private, account_identity_public)
 *  2. syntheticIv  = HMAC(HMAC(masterSecret,"auth"),   plaintext)[0:16]
 *  3. cipherKey    = HMAC(HMAC(masterSecret,"cipher"), syntheticIv)
 *  4. ciphertext   = AES-256-CTR(plaintext, key=cipherKey, iv=ALL-ZERO[16])
 *  5. wrap as `DeviceName { ephemeralPublic, syntheticIv, ciphertext }`, base64.
 *
 * The double-HMAC key derivation and the all-zero AES-CTR IV are exact — the
 * primary's `decryptDeviceName` recomputes the syntheticIv from the recovered
 * plaintext and rejects the name if it doesn't match (which is what made an
 * earlier, single-HMAC version show "Unnamed Device").
 *
 * The encrypted name is set at LINK time (see [SignalProvisioningClient]); to
 * change it, re-link the device (Signal can't rename a linked device in place).
 */
object SignalDeviceName {

    private const val TAG = "SignalDeviceName"

    /** Returns the base64 encrypted DeviceName, or null on failure. */
    fun encrypt(name: String, identityKeyPair: IdentityKeyPair): String? = try {
        val plaintext = name.toByteArray(Charsets.UTF_8)
        // Account identity public key (ECPublicKey under the IdentityKey).
        val identityPublic = identityKeyPair.publicKey.publicKey
        val ephemeral = ECKeyPair.generate()
        val masterSecret = ephemeral.privateKey.calculateAgreement(identityPublic)

        // Double-HMAC derivation: an "auth"/"cipher"-keyed sub-key first, then
        // HMAC over the plaintext / syntheticIv. Must match the primary exactly.
        val syntheticIv = hmac(hmac(masterSecret, AUTH), plaintext).copyOfRange(0, 16)
        val cipherKey = hmac(hmac(masterSecret, CIPHER), syntheticIv)

        // AES-256-CTR with an all-zero counter block — the syntheticIv is carried
        // separately in the proto for verification, NOT used as the cipher IV.
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(ByteArray(16)))
        val ciphertext = cipher.doFinal(plaintext)

        val proto = deviceNameProto(ephemeral.publicKey.serialize(), syntheticIv, ciphertext)
        Base64.encodeToString(proto, Base64.NO_WRAP or Base64.NO_PADDING)
    } catch (t: Throwable) {
        Log.w(TAG, "device name encrypt failed", t)
        null
    }

    private val AUTH = "auth".toByteArray(Charsets.UTF_8)
    private val CIPHER = "cipher".toByteArray(Charsets.UTF_8)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Hand-encode `DeviceName { 1: ephemeralPublic, 2: syntheticIv, 3: ciphertext }`. */
    private fun deviceNameProto(
        ephemeralPublic: ByteArray,
        syntheticIv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeBytesField(out, 1, ephemeralPublic)
        writeBytesField(out, 2, syntheticIv)
        writeBytesField(out, 3, ciphertext)
        return out.toByteArray()
    }

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, value: ByteArray) {
        out.write((fieldNumber shl 3) or 2)  // wire type 2 = length-delimited
        writeVarint(out, value.size)
        out.write(value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
}
