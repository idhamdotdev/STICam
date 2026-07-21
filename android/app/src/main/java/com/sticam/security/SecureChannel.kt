package com.sticam.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SecurePacket(val type: Byte, val payload: ByteArray)

internal object SecureProtocolCrypto {
    val magic = "STICAM2\n".toByteArray(StandardCharsets.US_ASCII)
    val kdfSalt = "STICam secure transport v2".toByteArray(StandardCharsets.UTF_8)
    val packetAad = "STICAM2-PACKET".toByteArray(StandardCharsets.US_ASCII)
    val clientLabel = "client".toByteArray(StandardCharsets.US_ASCII)
    val serverLabel = "server".toByteArray(StandardCharsets.US_ASCII)
    val sessionLabel = "session".toByteArray(StandardCharsets.US_ASCII)
    val clientFinishLabel = "client-finish".toByteArray(StandardCharsets.US_ASCII)
    val clientToServerLabel = "client-to-server".toByteArray(StandardCharsets.US_ASCII)
    val serverToClientLabel = "server-to-client".toByteArray(StandardCharsets.US_ASCII)
    const val pbkdf2Iterations = 120_000

    fun deriveBaseKey(pairingKey: String): ByteArray {
        val spec = PBEKeySpec(pairingKey.toCharArray(), kdfSalt, pbkdf2Iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun sequenceBytes(sequence: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(sequence)
        .array()

    fun nonce(sequence: Long): ByteArray = ByteArray(12).also { result ->
        sequenceBytes(sequence).copyInto(result, destinationOffset = 4)
    }

    fun aad(sequence: Long): ByteArray = packetAad + sequenceBytes(sequence)
}

/**
 * Authenticated version 2 transport for the STICam typed-packet protocol.
 * Fresh directional keys are derived for each TCP session and every record is
 * encrypted with AES-256-GCM plus a strictly ordered authenticated sequence.
 */
class SecureChannel private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: BufferedOutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
) : AutoCloseable {

    companion object {
        private const val RANDOM_SIZE = 16
        private const val PROOF_SIZE = 32
        private const val TAG_BITS = 128
        private const val TAG_SIZE = TAG_BITS / 8
        private const val MAX_ENCRYPTED_PACKET = 20 * 1024 * 1024
        private const val MAX_PAYLOAD = MAX_ENCRYPTED_PACKET - TAG_SIZE - 5
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        fun connect(socket: Socket, pairingKey: String): SecureChannel {
            val normalizedKey = pairingKey.trim().uppercase()
            require(
                normalizedKey.length == 32 &&
                    normalizedKey.all { it in '0'..'9' || it in 'A'..'F' },
            ) { "Pairing key must contain exactly 32 hexadecimal characters" }
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
            val baseKey = SecureProtocolCrypto.deriveBaseKey(normalizedKey)
            var sessionKey: ByteArray? = null
            try {
                val clientRandom = ByteArray(RANDOM_SIZE).also(SecureRandom()::nextBytes)
                val clientProof = SecureProtocolCrypto.hmac(
                    baseKey,
                    SecureProtocolCrypto.clientLabel,
                    clientRandom,
                )

                output.write(SecureProtocolCrypto.magic)
                output.write(clientRandom)
                output.write(clientProof)
                output.flush()

                val serverRandom = readExactly(input, RANDOM_SIZE)
                val serverProof = readExactly(input, PROOF_SIZE)
                val expectedProof = SecureProtocolCrypto.hmac(
                    baseKey,
                    SecureProtocolCrypto.serverLabel,
                    clientRandom,
                    serverRandom,
                )
                if (!MessageDigest.isEqual(serverProof, expectedProof)) {
                    throw SecurityException("Host pairing verification failed")
                }

                sessionKey = SecureProtocolCrypto.hmac(
                    baseKey,
                    SecureProtocolCrypto.sessionLabel,
                    clientRandom,
                    serverRandom,
                )
                val clientFinish = SecureProtocolCrypto.hmac(
                    sessionKey,
                    SecureProtocolCrypto.clientFinishLabel,
                    SecureProtocolCrypto.magic,
                    clientRandom,
                    serverRandom,
                    clientProof,
                    serverProof,
                )
                output.write(clientFinish)
                output.flush()
                val sendKey = SecureProtocolCrypto.hmac(
                    sessionKey,
                    SecureProtocolCrypto.clientToServerLabel,
                )
                val receiveKey = SecureProtocolCrypto.hmac(
                    sessionKey,
                    SecureProtocolCrypto.serverToClientLabel,
                )
                socket.soTimeout = 0
                return SecureChannel(socket, input, output, sendKey, receiveKey)
            } finally {
                baseKey.fill(0)
                sessionKey?.fill(0)
            }
        }

        private fun readExactly(input: InputStream, size: Int): ByteArray {
            val data = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(data, offset, size - offset)
                if (read < 0) throw java.io.EOFException("Secure transport closed")
                offset += read
            }
            return data
        }
    }

    private val sendLock = Any()
    private val receiveLock = Any()
    private val closed = AtomicBoolean(false)
    private var sendSequence = 0L
    private var receiveSequence = 0L

    fun send(type: Byte, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD) { "Packet is too large" }
        synchronized(sendLock) {
            check(!closed.get()) { "Secure channel is closed" }
            if (sendSequence == Long.MAX_VALUE) throw SecurityException("Send sequence exhausted")
            val sequence = sendSequence++
            val plain = ByteBuffer.allocate(5 + payload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(type)
                .putInt(payload.size)
                .put(payload)
                .array()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(sendKey, "AES"),
                    GCMParameterSpec(TAG_BITS, SecureProtocolCrypto.nonce(sequence)),
                )
                cipher.updateAAD(SecureProtocolCrypto.aad(sequence))
                val encrypted = cipher.doFinal(plain)
                val header = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(encrypted.size)
                    .array()
                output.write(header)
                output.write(SecureProtocolCrypto.sequenceBytes(sequence))
                output.write(encrypted)
                output.flush()
            } finally {
                plain.fill(0)
            }
        }
    }

    fun receive(): SecurePacket = synchronized(receiveLock) {
        check(!closed.get()) { "Secure channel is closed" }
        val length = ByteBuffer.wrap(readExactly(input, 4)).order(ByteOrder.BIG_ENDIAN).int
        if (length !in (TAG_SIZE + 5)..MAX_ENCRYPTED_PACKET) {
            throw SecurityException("Invalid encrypted packet length: $length")
        }
        val sequence = ByteBuffer.wrap(readExactly(input, Long.SIZE_BYTES))
            .order(ByteOrder.BIG_ENDIAN)
            .long
        if (sequence != receiveSequence || receiveSequence == Long.MAX_VALUE) {
            throw SecurityException("Unexpected secure record sequence")
        }
        receiveSequence++

        val encrypted = readExactly(input, length)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(receiveKey, "AES"),
            GCMParameterSpec(TAG_BITS, SecureProtocolCrypto.nonce(sequence)),
        )
        cipher.updateAAD(SecureProtocolCrypto.aad(sequence))
        val plain = cipher.doFinal(encrypted)
        try {
            if (plain.size < 5) throw SecurityException("Invalid secure packet")
            val buffer = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
            val type = buffer.get()
            val payloadLength = buffer.int
            if (payloadLength < 0 || payloadLength != buffer.remaining()) {
                throw SecurityException("Invalid secure payload length")
            }
            val payload = ByteArray(payloadLength)
            buffer.get(payload)
            SecurePacket(type, payload)
        } finally {
            plain.fill(0)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        synchronized(sendLock) { sendKey.fill(0) }
        synchronized(receiveLock) { receiveKey.fill(0) }
    }
}
