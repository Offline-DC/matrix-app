package com.offline.dpadmessenger.backend.gmessages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * Wire-format tests for the hand-rolled pairing protobufs. The expected
 * base64 blobs were produced by Google's canonical protobuf encoder
 * (python `protobuf` against the field definitions copied from
 * mautrix-gmessages `authentication.proto`). If these match, our
 * [ProtoWriter] output is byte-identical to what protoc would emit, so
 * Google's servers will parse it.
 */
class GMPairingProtoTest {

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    @Test
    fun registerPhoneRelayRequestMatchesProtoc() {
        val pub = ByteArray(91) { it.toByte() }
        val got = GMPairingProto.registerPhoneRelayRequest(
            requestId = "11111111-2222-3333-4444-555555555555",
            ecdsaPubX509 = pub,
        )
        assertEquals(
            "CjoKJDExMTExMTExLTIyMjItMzMzMy00NDQ0LTU1NTU1NTU1NTU1NRoFQnVnbGU6CxjqDyADKBI4BEgGGn" +
                "IKZU1vemlsbGEvNS4wIChMaW51eDsgQW5kcm9pZCAxNCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRN" +
                "TCwgbGlrZSBHZWNrbykgQ2hyb21lLzE0Ni4wLjAuMCBTYWZhcmkvNTM3LjM2EAEaBWxpYmdtMAIiYT" +
                "JfCAISWwABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0" +
                "NTY3ODk6Ozw9Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=",
            b64(got),
        )
    }

    @Test
    fun authOnlyContainerMatchesProtoc() {
        val got = GMPairingProto.authOnlyContainer("abc", byteArrayOf(1, 2, 3))
        assertEquals("Ch4KA2FiYxoFQnVnbGUyAwECAzoLGOoPIAMoEjgESAY=", b64(got))
    }

    @Test
    fun urlDataMatchesProtoc() {
        val got = GMPairingProto.urlData(
            pairingKey = byteArrayOf(0x50, 0x4b, 0x00, 0xff.toByte()),
            aesKey = ByteArray(32) { 7 },
            hmacKey = ByteArray(32) { 9 },
        )
        assertEquals(
            "CgRQSwD/EiAHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBxogCQkJCQkJCQkJCQkJCQkJCQkJCQ" +
                "kJCQkJCQkJCQkJCQk=",
            b64(got),
        )
    }

    @Test
    fun parsesRegisterRelayResponse() {
        // Produced by protoc: RegisterPhoneRelayResponse{pairingKey, validFor,
        // authKeyData{tachyonAuthToken, TTL}, responseID}.
        val resp = unb64("GgOqu8wgkBwqCgoE3q2+7xCAowUyBXJpZC0x")
        val r = GMPairingProto.parseRegisterRelayResponse(resp)
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()), r.pairingKey)
        assertArrayEquals(
            byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()),
            r.tachyonAuthToken,
        )
    }
}
