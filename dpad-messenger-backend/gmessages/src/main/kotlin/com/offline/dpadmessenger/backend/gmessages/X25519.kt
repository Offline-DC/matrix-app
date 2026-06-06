package com.offline.dpadmessenger.backend.gmessages

import java.security.SecureRandom

/**
 * Pure-Kotlin X25519 (Curve25519 ECDH, RFC 7748).
 *
 * Why this exists instead of a library dependency: the only thing the
 * Google Messages pairing handshake needs from libsignal-android was this
 * one primitive, and libsignal ships ~4MB of native code plus Java records
 * that break the launcher's D8/dexing pipeline (the "Record desugaring
 * without a global-synthetics consumer" build failure). Android's built-in
 * `KeyAgreement.getInstance("XDH")` would also work but is API 33+, and we
 * target minSdk 24.
 *
 * The field/curve arithmetic is a line-for-line port of TweetNaCl's
 * `crypto_scalarmult` (public domain, by Bernstein et al.) — the canonical
 * compact constant-time implementation. Field elements are 16 limbs of 16
 * bits in a LongArray; the Montgomery ladder runs in constant time with
 * branchless conditional swaps.
 *
 * Verified against the RFC 7748 §5.2 test vectors and the Diffie-Hellman
 * vector from §6.1 (see X25519Test).
 */
internal object X25519 {

    const val KEY_SIZE = 32

    /** X25519 keypair: raw 32-byte little-endian keys, no type prefix.
     *  (libsignal's serialize() prepended a 0x05 djb-type byte — Google's
     *  wire format wants the bare 32 bytes, so we never add one.) */
    data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPair) return false
            return publicKey.contentEquals(other.publicKey) &&
                privateKey.contentEquals(other.privateKey)
        }

        override fun hashCode(): Int =
            31 * publicKey.contentHashCode() + privateKey.contentHashCode()
    }

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val priv = ByteArray(KEY_SIZE).also(random::nextBytes)
        // RFC 7748 clamping: clear bits 0-2, clear bit 255, set bit 254.
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = ((priv[31].toInt() and 127) or 64).toByte()
        return KeyPair(publicKey = scalarMultBase(priv), privateKey = priv)
    }

    /** Compute the shared secret: X25519(ourPrivate, theirPublic). */
    fun sharedSecret(ourPrivate: ByteArray, theirPublic: ByteArray): ByteArray {
        require(ourPrivate.size == KEY_SIZE) { "private key must be 32 bytes (got ${ourPrivate.size})" }
        require(theirPublic.size == KEY_SIZE) { "public key must be 32 bytes (got ${theirPublic.size})" }
        val out = scalarMult(ourPrivate, theirPublic)
        // All-zero output means the peer key was a low-order point — reject,
        // matching libsignal's behaviour (it throws on degenerate keys).
        check(!out.all { it == 0.toByte() }) { "X25519: degenerate (low-order) peer public key" }
        return out
    }

    /** X25519(scalar, basepoint=9) — derive the public key for a private key. */
    fun scalarMultBase(scalar: ByteArray): ByteArray {
        val base = ByteArray(KEY_SIZE)
        base[0] = 9
        return scalarMult(scalar, base)
    }

    // ---------------------------------------------------------------------
    // TweetNaCl crypto_scalarmult port. Field element = LongArray(16),
    // 16-bit limbs, little-endian.
    // ---------------------------------------------------------------------

    private val F121665: LongArray = LongArray(16).also { it[0] = 0xDB41; it[1] = 1 }

    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val z = ByteArray(32)
        scalar.copyInto(z, endIndex = 32)
        z[0] = (z[0].toInt() and 248).toByte()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()

        val x = unpack25519(point)
        val a = LongArray(16).also { it[0] = 1 }
        val b = x.copyOf()
        val c = LongArray(16)
        val d = LongArray(16).also { it[0] = 1 }
        val e = LongArray(16)
        val f = LongArray(16)

        for (i in 254 downTo 0) {
            val r = ((z[i shr 3].toInt() ushr (i and 7)) and 1).toLong()
            sel25519(a, b, r)
            sel25519(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            mul(d, e, e)
            mul(f, a, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            mul(b, a, a)
            sub(c, d, f)
            mul(a, c, F121665)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            mul(b, e, e)
            sel25519(a, b, r)
            sel25519(c, d, r)
        }

        val inv = LongArray(16)
        inv25519(inv, c)
        mul(a, a, inv)
        return pack25519(a)
    }

    /** Carry propagation across the 16 limbs (mod 2^255-19 wraparound on the top limb). */
    private fun car25519(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val carry = o[i] shr 16
            val next = if (i < 15) i + 1 else 0
            o[next] += if (i < 15) carry - 1 else 38 * (carry - 1)
            o[i] -= carry shl 16
        }
    }

    /** Branchless conditional swap: swap p and q iff b == 1. */
    private fun sel25519(p: LongArray, q: LongArray, b: Long) {
        val mask = (b - 1).inv()  // b=1 -> all ones, b=0 -> all zeros
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    /** Serialize a field element to 32 bytes, fully reduced mod 2^255-19. */
    private fun pack25519(n: LongArray): ByteArray {
        val t = n.copyOf()
        car25519(t)
        car25519(t)
        car25519(t)
        val m = LongArray(16)
        repeat(2) {
            m[0] = t[0] - 0xFFED
            for (i in 1 until 15) {
                m[i] = t[i] - 0xFFFF - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xFFFF
            }
            m[15] = t[15] - 0x7FFF - ((m[14] shr 16) and 1)
            val borrow = (m[15] shr 16) and 1
            m[14] = m[14] and 0xFFFF
            sel25519(t, m, 1 - borrow)
        }
        val out = ByteArray(32)
        for (i in 0 until 16) {
            out[2 * i] = (t[i] and 0xFF).toByte()
            out[2 * i + 1] = ((t[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Parse 32 little-endian bytes into a field element; high bit masked per RFC 7748. */
    private fun unpack25519(n: ByteArray): LongArray {
        val o = LongArray(16)
        for (i in 0 until 16) {
            o[i] = (n[2 * i].toLong() and 0xFF) or ((n[2 * i + 1].toLong() and 0xFF) shl 8)
        }
        o[15] = o[15] and 0x7FFF
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    /** Schoolbook multiply with the 38x fold-back for the 2^256 = 38 (mod p) reduction. */
    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) {
            for (j in 0 until 16) {
                t[i + j] += a[i] * b[j]
            }
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car25519(o)
        car25519(o)
    }

    /** Field inversion via Fermat: a^(p-2) = a^(2^255-21). Fixed square-and-multiply chain. */
    private fun inv25519(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }
}
