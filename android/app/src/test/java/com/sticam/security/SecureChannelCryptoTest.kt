package com.sticam.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureChannelCryptoTest {
    private val pairingKey = "00112233445566778899AABBCCDDEEFF"
    private val clientRandom = hex("000102030405060708090a0b0c0d0e0f")
    private val serverRandom = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")

    @Test
    fun handshakeDerivation_matchesWindowsVector() {
        val base = SecureProtocolCrypto.deriveBaseKey(pairingKey)
        assertEquals(
            "0a95e6086dadb687e6173045f32e65cfbf036844891d9ecdb7440e8121d1b142",
            base.toHex(),
        )
        val clientProof = SecureProtocolCrypto.hmac(
            base,
            SecureProtocolCrypto.clientLabel,
            clientRandom,
        )
        assertEquals(
            "b3e5261ee8d0a6c71077286c9ee82f69140eb3a3141fea60a21983195d5e4424",
            clientProof.toHex(),
        )
        val serverProof = SecureProtocolCrypto.hmac(
            base,
            SecureProtocolCrypto.serverLabel,
            clientRandom,
            serverRandom,
        )
        assertEquals(
            "1436ceef1c7f7250dcdcf51b2efef3eabcd5fefcfeffd19482445714df1470f2",
            serverProof.toHex(),
        )
        val session = SecureProtocolCrypto.hmac(
            base,
            SecureProtocolCrypto.sessionLabel,
            clientRandom,
            serverRandom,
        )
        assertEquals(
            "486e18028ccdd51ceb66d634491332b50acc500e63148aae902b8a6076ed7a98",
            SecureProtocolCrypto.hmac(
                session,
                SecureProtocolCrypto.clientFinishLabel,
                SecureProtocolCrypto.magic,
                clientRandom,
                serverRandom,
                clientProof,
                serverProof,
            ).toHex(),
        )
        assertEquals(
            "a0ebd5dea4e3e4b4ac8e3a483fc7c1f9f00caa01761976a0c315d41383bd1882",
            SecureProtocolCrypto.hmac(session, SecureProtocolCrypto.clientToServerLabel).toHex(),
        )
        assertEquals(
            "3577699ee9bd14b3685e07dac1407a89f3e98da6ada5f28e136df624cda1660a",
            SecureProtocolCrypto.hmac(session, SecureProtocolCrypto.serverToClientLabel).toHex(),
        )
    }

    @Test
    fun sequenceZeroRecord_matchesWindowsVector() {
        val key = hex("a0ebd5dea4e3e4b4ac8e3a483fc7c1f9f00caa01761976a0c315d41383bd1882")
        val payload = "{\"cmd\":\"request_idr\"}".toByteArray()
        val plain = ByteBuffer.allocate(5 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0x10)
            .putInt(payload.size)
            .put(payload)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, SecureProtocolCrypto.nonce(0)),
        )
        cipher.updateAAD(SecureProtocolCrypto.aad(0))
        val encrypted = cipher.doFinal(plain)

        assertEquals(
            "dc6e5984a336e8a4377a92e6fa9d9592901c74e4a032fdecb37ae1c6e80e4a765fa102f25413ae7081a4",
            encrypted.toHex(),
        )
    }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
