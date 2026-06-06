package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RFC 7748 test vectors for the in-repo X25519 implementation, plus
 * round-trip checks. If these pass, the TweetNaCl port's field arithmetic
 * and Montgomery ladder are correct — they are extremely sensitive to any
 * transcription error (a single wrong register in the ladder fails all of
 * them).
 */
class X25519Test {

    private fun unhex(s: String) =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun rfc7748Section52Vector1() {
        assertArrayEquals(
            unhex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            X25519.scalarMult(
                unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ),
        )
    }

    @Test
    fun rfc7748Section52Vector2() {
        assertArrayEquals(
            unhex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
            X25519.scalarMult(
                unhex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                unhex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ),
        )
    }

    /** RFC 7748 §5.2 iterated vector — 1,000 ladder iterations. Exercises
     *  carry propagation and reduction far more thoroughly than one-shot
     *  vectors. (The 1M-iteration variant is skipped: ~minutes of runtime.) */
    @Test
    fun rfc7748IteratedVector() {
        var k = unhex("0900000000000000000000000000000000000000000000000000000000000000")
        var u = k.copyOf()
        repeat(1000) {
            val r = X25519.scalarMult(k, u)
            u = k
            k = r
        }
        assertArrayEquals(
            unhex("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51"),
            k,
        )
    }

    /** RFC 7748 §6.1 full Diffie-Hellman: pubkey derivation + agreement. */
    @Test
    fun rfc7748DiffieHellman() {
        val alicePriv = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPub = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertArrayEquals(alicePub, X25519.scalarMultBase(alicePriv))
        assertArrayEquals(bobPub, X25519.scalarMultBase(bobPriv))
        assertArrayEquals(shared, X25519.sharedSecret(alicePriv, bobPub))
        assertArrayEquals(shared, X25519.sharedSecret(bobPriv, alicePub))
    }

    @Test
    fun freshKeyPairsAgree() {
        val kp1 = X25519.generateKeyPair()
        val kp2 = X25519.generateKeyPair()
        assertArrayEquals(
            X25519.sharedSecret(kp1.privateKey, kp2.publicKey),
            X25519.sharedSecret(kp2.privateKey, kp1.publicKey),
        )
    }

    @Test
    fun rejectsLowOrderPeerKey() {
        val kp = X25519.generateKeyPair()
        assertThrows(IllegalStateException::class.java) {
            X25519.sharedSecret(kp.privateKey, ByteArray(32))  // all-zero point
        }
    }
}
