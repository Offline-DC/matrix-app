package com.offline.dpadmessenger.backend.gmessages

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * The UKey2 half of the GAIA emoji-match handshake (port of the crypto in
 * mautrix-gmessages `pair_google.go`). NIST P-256 ECDH + SHA-512 commitment +
 * HKDF-SHA256 key derivation, plus the verification emoji lists.
 *
 * One instance per pairing attempt: [preparePayloads] builds CLIENT_INIT /
 * CLIENT_FINISH, [processServerInit] consumes SERVER_INIT and returns the emoji,
 * [deriveSessionKeys] produces the AES/HMAC session keys after confirmation.
 */
/**
 * Thrown when SERVER_INIT asks for a verification-emoji list version this build
 * doesn't ship (i.e. Google advanced the emoji set past what we ported from
 * mautrix-gmessages). Carries the [version] so the caller can tell the user to
 * update rather than display a wrong emoji.
 */
class UnsupportedPairingEmojiVersionException(val version: Int) :
    RuntimeException("unsupported pairing emoji version $version (app emoji list is out of date)")

class UKey2Session {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** Full Ukey2Message(CLIENT_INIT) bytes (kept for the auth-string HKDF). */
    lateinit var initMessage: ByteArray
        private set

    /** Full Ukey2Message(CLIENT_FINISH) bytes (sent at the finish step). */
    lateinit var finishMessage: ByteArray
        private set

    private var nextKey: ByteArray = ByteArray(0)

    /** Build CLIENT_INIT + CLIENT_FINISH. Returns (initMessage, finishMessage). */
    fun preparePayloads(): Pair<ByteArray, ByteArray> {
        val pub = keyPair.public as ECPublicKey
        val x33 = with33LeadingZero(to32(pub.w.affineX))
        val y33 = with33LeadingZero(to32(pub.w.affineY))

        // GenericPublicKey { type=EC_P256(1), ec_p256_public_key=2 { x=1, y=2 } }
        val ecP256 = ProtoWriter().bytes(1, x33).bytes(2, y33)
        val genericPubKey = ProtoWriter().int32(1, PUBKEY_TYPE_EC_P256).message(2, ecP256)

        // Ukey2ClientFinished { public_key=1 } -> Ukey2Message(CLIENT_FINISH)
        val finishPayload = ProtoWriter().message(1, genericPubKey).toByteArray()
        finishMessage = ukey2Message(UKEY2_CLIENT_FINISH, finishPayload)

        // commitment = SHA-512(finishMessage)
        val commitment = sha512(finishMessage)

        // Ukey2ClientInit { version=1, random[32]=2,
        //   cipher_commitments=3 [{ handshake_cipher=P256_SHA512, commitment }],
        //   next_protocol=4 } -> Ukey2Message(CLIENT_INIT)
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipherCommitment = ProtoWriter()
            .int32(1, UKEY2_CIPHER_P256_SHA512)
            .bytes(2, commitment)
        val clientInit = ProtoWriter()
            .int32(1, 1)
            .bytes(2, random)
            .message(3, cipherCommitment)
            .string(4, "AES_256_CBC-HMAC_SHA256")
            .toByteArray()
        initMessage = ukey2Message(UKEY2_CLIENT_INIT, clientInit)

        return initMessage to finishMessage
    }

    /**
     * Consume SERVER_INIT (the raw Ukey2Message bytes from
     * GaiaPairingResponseContainer.data), do the ECDH, derive the emoji and
     * NextKey. Returns the verification emoji.
     */
    fun processServerInit(serverInitMessage: ByteArray, verificationCodeVersion: Int): String {
        val ukey = ProtoReader.fields(serverInitMessage)
        val type = ukey[1]?.value?.toInt() ?: -1
        require(type == UKEY2_SERVER_INIT) { "unexpected ukey message type $type" }
        val serverInit = ProtoReader.fields(ukey[2]?.bytes ?: error("no server init data"))
        val version = serverInit[1]?.value?.toInt() ?: -1
        val cipher = serverInit[3]?.value?.toInt() ?: -1
        require(version == 1) { "unexpected server init version $version" }
        require(cipher == UKEY2_CIPHER_P256_SHA512) { "unexpected handshake cipher $cipher" }

        val genericPub = ProtoReader.fields(serverInit[4]?.bytes ?: error("no server public key"))
        val ecP256 = ProtoReader.fields(genericPub[2]?.bytes ?: error("no ec_p256 key"))
        val x = stripLeadingZero(ecP256[1]?.bytes ?: error("no x"))
        val y = stripLeadingZero(ecP256[2]?.bytes ?: error("no y"))

        val diffieHellman = ecdh(x, y)
        val sharedSecret = sha256(diffieHellman)
        val authInfo = initMessage + serverInitMessage
        val ukeyV1Auth = GMCrypto.hkdfSha256(sharedSecret, authInfo, 32, salt = "UKEY2 v1 auth".toByteArray())
        nextKey = GMCrypto.hkdfSha256(sharedSecret, authInfo, 32, salt = "UKEY2 v1 next".toByteArray())

        val authNumber = ((ukeyV1Auth[0].toLong() and 0xFF) shl 24) or
            ((ukeyV1Auth[1].toLong() and 0xFF) shl 16) or
            ((ukeyV1Auth[2].toLong() and 0xFF) shl 8) or
            (ukeyV1Auth[3].toLong() and 0xFF)
        // Pick the emoji list Google expects. We must NOT silently fall back to
        // an older list for an unknown version: the phone would compute the
        // emoji from the newer list, ours would differ, and the user would hunt
        // for a match that isn't there ("the QR/pairing won't register"). Fail
        // loudly so the caller can tell the user to update instead.
        val list = when (verificationCodeVersion) {
            0 -> pairingEmojisV0
            1 -> pairingEmojisV1
            else -> throw UnsupportedPairingEmojiVersionException(verificationCodeVersion)
        }
        return list[(authNumber % list.size).toInt()]
    }

    /** After the phone confirms, derive (AES, HMAC) session keys. */
    fun deriveSessionKeys(keyDerivationVersion: Int): Pair<ByteArray, ByteArray> {
        val clientKey = GMCrypto.hkdfSha256(nextKey, "client".toByteArray(), 32, salt = ENCRYPTION_KEY_INFO)
        val serverKey = GMCrypto.hkdfSha256(nextKey, "server".toByteArray(), 32, salt = ENCRYPTION_KEY_INFO)
        return when (keyDerivationVersion) {
            0 -> clientKey to serverKey
            1 -> {
                val concatted = ByteArray(96)
                System.arraycopy(ENCRYPTION_KEY_INFO, 0, concatted, 0, 32)
                if (byteHash(clientKey) < byteHash(serverKey)) {
                    System.arraycopy(clientKey, 0, concatted, 32, 32)
                    System.arraycopy(serverKey, 0, concatted, 64, 32)
                } else {
                    System.arraycopy(serverKey, 0, concatted, 32, 32)
                    System.arraycopy(clientKey, 0, concatted, 64, 32)
                }
                val hash = sha256(concatted)
                val aes = GMCrypto.hkdfSha256(hash, "Ditto info 1".toByteArray(), 32, salt = "Ditto salt 1".toByteArray())
                val hmac = GMCrypto.hkdfSha256(hash, "Ditto info 2".toByteArray(), 32, salt = "Ditto salt 2".toByteArray())
                aes to hmac
            }
            else -> error("unsupported key derivation version $keyDerivationVersion")
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun ukey2Message(type: Int, data: ByteArray): ByteArray =
        ProtoWriter().int32(1, type).bytes(2, data).toByteArray()

    private fun ecdh(serverX: ByteArray, serverY: ByteArray): ByteArray {
        val params = (keyPair.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, serverX), BigInteger(1, serverY))
        val serverPub = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(point, params))
        return KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(serverPub, true)
            generateSecret() // P-256 shared X coordinate (32 bytes)
        }
    }

    private fun to32(bi: BigInteger): ByteArray {
        val b = bi.toByteArray()
        val out = ByteArray(32)
        when {
            b.size == 32 -> System.arraycopy(b, 0, out, 0, 32)
            b.size > 32 -> System.arraycopy(b, b.size - 32, out, 0, 32) // drop sign byte
            else -> System.arraycopy(b, 0, out, 32 - b.size, b.size)
        }
        return out
    }

    /** 33-byte big-endian two's complement (leading 0x00 + 32-byte value). */
    private fun with33LeadingZero(b32: ByteArray): ByteArray =
        ByteArray(33).also { System.arraycopy(b32, 0, it, 1, 32) }

    private fun stripLeadingZero(b: ByteArray): ByteArray =
        if (b.size == 33 && b[0].toInt() == 0) b.copyOfRange(1, 33) else b

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)

    /** Java String.hashCode-style hash over signed bytes (matches mautrix). */
    private fun byteHash(bytes: ByteArray): Int {
        var out = 1
        for (b in bytes) out = 31 * out + b.toInt()
        return out
    }

    companion object {
        // ukey.proto enum values
        private const val UKEY2_CLIENT_INIT = 2
        private const val UKEY2_SERVER_INIT = 3
        private const val UKEY2_CLIENT_FINISH = 4
        private const val UKEY2_CIPHER_P256_SHA512 = 100
        private const val PUBKEY_TYPE_EC_P256 = 1

        // mautrix doHKDF salt constant for the session keys (16... actually 32 bytes)
        private val ENCRYPTION_KEY_INFO: ByteArray = intArrayOf(
            130, 170, 85, 160, 211, 151, 248, 131, 70, 202, 28, 238, 141, 57, 9, 185,
            95, 19, 250, 125, 235, 29, 74, 179, 131, 118, 184, 37, 109, 168, 85, 16,
        ).map { it.toByte() }.toByteArray()

        // Verification emoji list V0 (exact order from mautrix-gmessages).
        private val pairingEmojisV0: List<String> = listOf(
            "😁", "😅", "🤣", "🫠", "🥰", "😇", "🤩", "😘", "😜", "🤗", "🤔", "🤐", "😴", "🥶", "🤯", "🤠",
            "🥳", "🥸", "😎", "🤓", "🧐", "🥹", "😭", "😱", "😖", "🥱", "😮‍💨", "🤡", "💩", "👻", "👽", "🤖",
            "😻", "💌", "💘", "💕", "❤", "💢", "💥", "💫", "💬", "🗯", "💤", "👋", "🙌", "🙏", "✍", "🦶",
            "👂", "🧠", "🦴", "👀", "🧑", "🧚", "🧍", "👣", "🐵", "🐶", "🐺", "🦊", "🦁", "🐯", "🦓", "🦄",
            "🐑", "🐮", "🐷", "🐿", "🐰", "🦇", "🐻", "🐨", "🐼", "🦥", "🐾", "🐔", "🐥", "🐦", "🕊", "🦆",
            "🦉", "🪶", "🦩", "🐸", "🐢", "🦎", "🐍", "🐳", "🐬", "🦭", "🐠", "🐡", "🦈", "🪸", "🐌", "🦋",
            "🐛", "🐝", "🐞", "🪱", "💐", "🌸", "🌹", "🌻", "🌱", "🌲", "🌴", "🌵", "🌾", "☘", "🍁", "🍂",
            "🍄", "🪺", "🍇", "🍈", "🍉", "🍋", "🍌", "🍍", "🍎", "🍐", "🍒", "🍓", "🥝", "🥥", "🥑", "🥕",
            "🌽", "🌶", "🫑", "🥦", "🥜", "🍞", "🥐", "🥨", "🧀", "🍗", "🍔", "🍟", "🍕", "🌭", "🌮", "🥗",
            "🥣", "🍿", "🦀", "🦑", "🍦", "🍩", "🍪", "🍫", "🍰", "🍬", "🍭", "☕", "🫖", "🍹", "🥤", "🧊",
            "🥢", "🍽", "🥄", "🧭", "🏔", "🌋", "🏕", "🏖", "🪵", "🏗", "🏡", "🏰", "🛝", "🚂", "🛵", "🛴",
            "🛼", "🚥", "⚓", "🛟", "⛵", "✈", "🚀", "🛸", "🧳", "⏰", "🌙", "🌡", "🌞", "🪐", "🌠", "🌧",
            "🌀", "🌈", "☂", "⚡", "❄", "⛄", "🔥", "🎇", "🧨", "✨", "🎈", "🎉", "🎁", "🏆", "🏅", "⚽",
            "⚾", "🏀", "🏐", "🏈", "🎾", "🎳", "🏓", "🥊", "⛳", "⛸", "🎯", "🪁", "🔮", "🎮", "🧩", "🧸",
            "🪩", "🖼", "🎨", "🧵", "🧶", "🦺", "🧣", "🧤", "🧦", "🎒", "🩴", "👟", "👑", "👒", "🎩", "🧢",
            "💎", "🔔", "🎤", "📻", "🎷", "🪗", "🎸", "🎺", "🎻", "🥁", "📺", "🔋", "💻", "💿", "☎", "🕯",
            "💡", "📖", "📚", "📬", "✏", "✒", "🖌", "🖍", "📝", "💼", "📋", "📌", "📎", "🔑", "🔧", "🧲",
            "🪜", "🧬", "🔭", "🩹", "🩺", "🪞", "🛋", "🪑", "🛁", "🧹", "🧺", "🔱", "🏁", "🐪", "🐘", "🦃",
            "🍞", "🍜", "🍠", "🚘", "🤿", "🃏", "👕", "📸", "🏷", "✂", "🧪", "🚪", "🧴", "🧻", "🪣", "🧽", "🚸",
        )

        // V1 = dedup(V0) + added, minus removed (exact mautrix transform).
        private val pairingEmojisV1: List<String> = run {
            val added = listOf("🍋‍🟩", "🐦‍🔥", "🐲", "🪅", "🦜", "🏺", "🗿", "🫐", "⛽", "🍱", "🥡", "🧋", "🍼", "📐")
            val removed = setOf("💻", "🤗", "💬", "👋", "😁", "😎", "😇", "🥰", "🤓", "🤩")
            (pairingEmojisV0.distinct() + added).filterNot { removed.contains(it) }
        }
    }
}
