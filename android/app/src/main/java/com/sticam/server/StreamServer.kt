package com.sticam.server

import android.media.MediaCodec
import android.util.Log
import com.sticam.engine.CameraEngine
import com.sticam.security.SecureChannel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.Socket

/**
 * STICam Android Transport Client
 *
 * Opens an outbound connection to the Windows listener, authenticates it with
 * the configured pairing key, and encrypts each typed packet with AES-GCM.
 * The framing below describes the plaintext inside the secure v2 records.
 *
 * Wire protocol (upstream — Android → Windows):
 *   [1B type] [4B big-endian length] [data]
 *   0x01 = SPS config   (sent once on connect)
 *   0x02 = PPS config   (sent once on connect)
 *   0x00 = video frame  (repeating)
 *
 * Wire protocol (downstream — Windows → Android):
 *   [1B type=0x10] [4B length] [JSON payload]
 *   Control commands: {"cmd":"set_params","iso":400,"shutterNs":...}
 *
 * Also handles command ingestion on a separate read thread.
 */
class StreamServer(
    private val host: String,
    private val port: Int = 8765,
    private val engine: CameraEngine,
    private val pairingKey: String,
) {
    companion object {
        private const val TAG = "SticamServer"
        private const val TYPE_FRAME: Byte = 0x00
        private const val TYPE_SPS: Byte   = 0x01
        private const val TYPE_PPS: Byte   = 0x02
        private const val TYPE_CMD: Byte   = 0x10
    }

    private class FramePacket(val type: Byte, val data: ByteArray)
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<FramePacket>(30)
    private val frameShedding = FrameSheddingPolicy(congestionThreshold = 10)

    private var activeSocket: Socket? = null
    private var scope: CoroutineScope? = null
    @Volatile private var secureChannel: SecureChannel? = null

    // SPS/PPS extracted from the first encoder output
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    var onClientConnected: (() -> Unit)?    = null
    var onClientDisconnected: (() -> Unit)? = null
    /** Fired when SPS and PPS are extracted. Used by ViewModel to enable recording. */
    var onConfigData: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
    var onSignalStrengthChanged: ((bars: Int) -> Unit)? = null
    var onParamsChangedFromHost: ((zoom: Float?, faceTracking: Boolean?, iso: Int?, brightness: Float?, focus: Float?, flash: Boolean?, cameraId: String?, resolution: String?, arFilter: String?, lutFilter: String?) -> Unit)? = null

    fun sendCommand(json: String) {
        val channel = secureChannel ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                channel.send(TYPE_CMD, json.toByteArray(Charsets.UTF_8))
                Log.i(TAG, "Sent command to client: $json")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send command: ${e.message}")
            }
        }
    }

    fun start() {
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = serverScope

        // Wire up the encoder callback (chain if already set by recording)
        val existing = engine.onEncodedData
        engine.onEncodedData = { data, info ->
            existing?.invoke(data, info)
            offerEncodedData(data, info)
        }

        serverScope.launch {
            while (isActive) {
                try {
                    Log.i(TAG, "Connecting to $host:$port...")
                    val client = Socket()
                    client.connect(java.net.InetSocketAddress(host, port), 5000)
                    client.tcpNoDelay = true
                    client.sendBufferSize = 256 * 1024
                    activeSocket = client
                    val channel = try {
                        SecureChannel.connect(client, pairingKey)
                    } catch (e: Exception) {
                        runCatching { client.close() }
                        throw e
                    }
                    secureChannel = channel
                    Log.i(TAG, "Authenticated encrypted connection: ${client.remoteSocketAddress}")
                    onClientConnected?.invoke()

                    engine.requestKeyFrame()

                    handleClient(client, channel)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Connection error: ${e.message}")
                    }
                }
                if (isActive) {
                    delay(2000)
                }
            }
        }
    }

    private var lastSignalBars = 4

    private fun updateSignalStrength() {
        val qSize = sendQueue.size
        val bars = when {
            secureChannel == null -> 0
            qSize == 0 -> 4
            qSize <= 2 -> 3
            qSize <= 5 -> 2
            else -> 1
        }
        if (bars != lastSignalBars) {
            lastSignalBars = bars
            scope?.launch(Dispatchers.Main) {
                onSignalStrengthChanged?.invoke(bars)
            }
        }
    }

    fun stop() {
        engine.onEncodedData = null
        secureChannel?.close()
        secureChannel = null
        scope?.cancel(); scope = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        sendQueue.clear()
        frameShedding.reset()
        updateSignalStrength()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Client handling
    // ═════════════════════════════════════════════════════════════════════

    private suspend fun handleClient(socket: Socket, channel: SecureChannel) {
        socket.use { s ->
            sendQueue.clear()
            frameShedding.reset()
            updateSignalStrength()

            // Queue initial configuration (SPS/PPS) if we have it cached
            sendConfig()

            // Start reader and sender jobs
            val clientScope = scope ?: return
            val readerJob = clientScope.launch { readCommands(s, channel) }
            val senderJob = clientScope.launch(Dispatchers.IO) {
                try {
                    while (activeSocket == s && !s.isClosed) {
                        val packet = sendQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (packet != null) {
                            channel.send(packet.type, packet.data)
                            updateSignalStrength()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sender loop error: ${e.message}")
                    runCatching { s.close() }
                }
            }

            try {
                // Keep alive while connected
                while (scope?.isActive == true && !s.isClosed) {
                    delay(500)
                }
            } finally {
                secureChannel = null
                channel.close()
                readerJob.cancel()
                senderJob.cancel()
                sendQueue.clear()
                updateSignalStrength()
                onClientDisconnected?.invoke()
                Log.i(TAG, "Client disconnected")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Encoder output → network
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Public entry point for ViewModel to call when recording needs
     * to share the encoder stream without replacing engine.onEncodedData.
     */
    fun offerEncodedData(data: ByteArray, info: MediaCodec.BufferInfo) =
        onEncoderOutput(data, info)

    /**
     * Called from the encoder thread for each encoded NAL packet.
     */
    private fun onEncoderOutput(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // This buffer contains SPS + PPS in Annex-B format, extract it immediately!
            extractConfig(data)
            return
        }

        if (secureChannel == null) return
        if (spsData == null || ppsData == null) return

        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        queueFrame(TYPE_FRAME, data, isKey)
    }

    private fun queueFrame(type: Byte, data: ByteArray, isKeyFrame: Boolean) {
        if (type != TYPE_FRAME) {
            enqueuePacket(type, data)
            return
        }

        when (frameShedding.decide(sendQueue.size, isKeyFrame)) {
            FrameQueueAction.ENQUEUE -> enqueuePacket(type, data)
            FrameQueueAction.DROP_UNTIL_KEYFRAME -> return
            FrameQueueAction.DROP_AND_REQUEST_KEYFRAME -> {
                sendQueue.clear()
                updateSignalStrength()
                engine.requestKeyFrame()
            }
            FrameQueueAction.RESYNC_WITH_KEYFRAME -> {
                sendQueue.clear()
                spsData?.let { enqueuePacket(TYPE_SPS, it) }
                ppsData?.let { enqueuePacket(TYPE_PPS, it) }
                enqueuePacket(TYPE_FRAME, data)
            }
        }
    }

    private fun enqueuePacket(type: Byte, data: ByteArray) {
        sendQueue.offer(FramePacket(type, data))
        updateSignalStrength()
    }

    /**
     * Parses Annex-B data for SPS (NAL type 7) and PPS (NAL type 8), and caches them.
     */
    private fun extractConfig(annexB: ByteArray) {
        val nals = H264AnnexB.parse(annexB)
        for (nal in nals) {
            when (nal.type) {
                7 -> spsData = nal.bytes
                8 -> ppsData = nal.bytes
            }
        }
        val sps = spsData; val pps = ppsData
        if (sps != null && pps != null) {
            onConfigData?.invoke(sps, pps)
            if (secureChannel != null) {
                queueFrame(TYPE_SPS, sps, true)
                queueFrame(TYPE_PPS, pps, true)
            }
        }
    }

    private fun sendConfig() {
        val sps = spsData
        val pps = ppsData
        if (sps != null) queueFrame(TYPE_SPS, sps, true)
        if (pps != null) queueFrame(TYPE_PPS, pps, true)
        Log.i(TAG, "Config queued: SPS=${sps?.size}B PPS=${pps?.size}B")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Command ingestion (Windows → Android)
    // ═════════════════════════════════════════════════════════════════════

    private fun readCommands(socket: Socket, channel: SecureChannel) {
        try {
            while (true) {
                val packet = channel.receive()
                if (packet.type == TYPE_CMD) {
                    if (packet.payload.isEmpty() || packet.payload.size > 64 * 1024) {
                        throw SecurityException("Invalid command payload length")
                    }
                    handleCommand(String(packet.payload, Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            // Client disconnected or read error
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleCommand(json: String) {
        Log.i(TAG, "Received command from host: $json")
        try {
            val obj = JSONObject(json)
            when (obj.optString("cmd")) {
                "set_params" -> {
                    val zoom = if (obj.has("zoom")) obj.getDouble("zoom").toFloat() else null
                    val faceTracking = if (obj.has("face_tracking")) obj.getBoolean("face_tracking") else null
                    val iso = if (obj.has("iso")) obj.getInt("iso") else null
                    val brightness = if (obj.has("brightness")) obj.getDouble("brightness").toFloat() else null
                    val focus = if (obj.has("focus")) obj.getDouble("focus").toFloat() else null
                    val flash = if (obj.has("flash")) obj.getBoolean("flash") else null
                    val cameraId = if (obj.has("camera_id")) obj.getString("camera_id") else null
                    val resolution = if (obj.has("resolution")) obj.getString("resolution") else null
                    val arFilter = if (obj.has("ar_filter")) obj.getString("ar_filter") else null
                    val lutFilter = if (obj.has("lut_filter")) obj.getString("lut_filter") else null
                    
                    if (zoom != null || faceTracking != null || iso != null || brightness != null || focus != null || flash != null || cameraId != null || resolution != null || arFilter != null || lutFilter != null) {
                        scope?.launch(Dispatchers.Main) {
                            onParamsChangedFromHost?.invoke(zoom, faceTracking, iso, brightness, focus, flash, cameraId, resolution, arFilter, lutFilter)
                        }
                    }
                }
                "request_idr" -> engine.requestKeyFrame()
                else -> Log.w(TAG, "Unknown cmd: $json")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command parse error: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Protocol helpers
    // ═════════════════════════════════════════════════════════════════════

}
