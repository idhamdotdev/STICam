package com.sticam.server

import android.media.MediaCodec
import android.util.Log
import com.sticam.engine.CameraEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Sticam Stream Server
 *
 * Listens on a TCP port and serves encoded H.264 frames from CameraEngine
 * using the typed-packet protocol (compatible with PortablePad wire format).
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
    private val engine: CameraEngine
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

    private var activeSocket: Socket? = null
    private var scope: CoroutineScope? = null
    private var clientOutput: OutputStream? = null

    // SPS/PPS extracted from the first encoder output
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    var onClientConnected: (() -> Unit)?    = null
    var onClientDisconnected: (() -> Unit)? = null
    /** Fired when SPS and PPS are extracted. Used by ViewModel to enable recording. */
    var onConfigData: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
    var onSignalStrengthChanged: ((bars: Int) -> Unit)? = null

    fun sendCommand(json: String) {
        val out = clientOutput ?: return
        scope?.launch(Dispatchers.IO) {
            try {
                synchronized(this@StreamServer) {
                    sendTypedPacket(out, TYPE_CMD, json.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Log.i(TAG, "Sent command to client: $json")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send command: ${e.message}")
            }
        }
    }

    fun start() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Wire up the encoder callback (chain if already set by recording)
        val existing = engine.onEncodedData
        engine.onEncodedData = { data, info ->
            existing?.invoke(data, info)
            offerEncodedData(data, info)
        }

        scope!!.launch {
            while (isActive) {
                try {
                    Log.i(TAG, "Connecting to $host:$port...")
                    val client = Socket()
                    client.connect(java.net.InetSocketAddress(host, port), 5000)
                    client.tcpNoDelay = true
                    client.sendBufferSize = 256 * 1024
                    activeSocket = client
                    Log.i(TAG, "Connected to host: ${client.remoteSocketAddress}")
                    onClientConnected?.invoke()

                    engine.requestKeyFrame()

                    handleClient(client)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Connection error: ${e.message}")
                        delay(2000)
                    }
                }
            }
        }
    }

    private var lastSignalBars = 4

    private fun updateSignalStrength() {
        val qSize = sendQueue.size
        val bars = when {
            clientOutput == null -> 0
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
        clientOutput = null
        scope?.cancel(); scope = null
        runCatching { activeSocket?.close() }
        activeSocket = null
        sendQueue.clear()
        updateSignalStrength()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Client handling
    // ═════════════════════════════════════════════════════════════════════

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            clientOutput = out
            sendQueue.clear()
            updateSignalStrength()

            // Queue initial configuration (SPS/PPS) if we have it cached
            sendConfig()

            // Start reader and sender jobs
            val readerJob = scope!!.launch { readCommands(s, inp) }
            val senderJob = scope!!.launch(Dispatchers.IO) {
                try {
                    while (activeSocket == s && !s.isClosed) {
                        val packet = sendQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (packet != null) {
                            synchronized(this@StreamServer) {
                                sendTypedPacket(out, packet.type, packet.data)
                                out.flush()
                            }
                            updateSignalStrength()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sender loop error: ${e.message}")
                }
            }

            try {
                // Keep alive while connected
                while (scope?.isActive == true && !s.isClosed) {
                    delay(500)
                }
            } finally {
                clientOutput = null
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

        if (clientOutput == null) return
        if (spsData == null || ppsData == null) return

        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        queueFrame(TYPE_FRAME, data, isKey)
    }

    private fun queueFrame(type: Byte, data: ByteArray, isKeyFrame: Boolean) {
        if (type == TYPE_FRAME) {
            // Keep latency low: if queue is getting full, drop non-keyframes
            if (sendQueue.size >= 10 && !isKeyFrame) {
                return // Drop this delta frame to avoid lag
            }
        }

        // Force-clear oldest frames if we hit hard limit to keep it real-time
        while (sendQueue.size >= 15) {
            sendQueue.poll()
        }
        sendQueue.offer(FramePacket(type, data.clone()))
        updateSignalStrength()
    }

    /**
     * Parses Annex-B data for SPS (NAL type 7) and PPS (NAL type 8), and caches them.
     */
    private fun extractConfig(annexB: ByteArray) {
        val nals = parseNalUnits(annexB)
        for ((type, bytes) in nals) {
            when (type) {
                7 -> spsData = bytes
                8 -> ppsData = bytes
            }
        }
        val sps = spsData; val pps = ppsData
        if (sps != null && pps != null) {
            onConfigData?.invoke(sps, pps)
            if (clientOutput != null) {
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

    private fun readCommands(socket: Socket, inp: InputStream) {
        val hdr = ByteArray(5)
        try {
            while (true) {
                readFully(inp, hdr)
                val type = hdr[0]
                val len = readBigEndianInt(hdr, 1)
                if (len <= 0 || len > 1_000_000) continue

                val payload = ByteArray(len)
                readFully(inp, payload)

                if (type == TYPE_CMD) {
                    handleCommand(String(payload, Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            // Client disconnected or read error
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleCommand(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("cmd")) {
                "set_params" -> {
                    // Auto mode: only zoom is remotely adjustable
                    if (obj.has("zoom")) engine.setZoom(obj.getDouble("zoom").toFloat())
                    Log.d(TAG, "Params updated from host: $json")
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

    /** Writes [type(1B)][length(4B big-endian)][data] */
    @Synchronized
    private fun sendTypedPacket(out: OutputStream, type: Byte, data: ByteArray) {
        val hdr = ByteArray(5)
        hdr[0] = type
        hdr[1] = (data.size shr 24 and 0xFF).toByte()
        hdr[2] = (data.size shr 16 and 0xFF).toByte()
        hdr[3] = (data.size shr 8  and 0xFF).toByte()
        hdr[4] = (data.size        and 0xFF).toByte()
        out.write(hdr)
        out.write(data)
    }

    /** Parses Annex-B into (nalType, nalBytes) pairs. */
    private fun parseNalUnits(annexB: ByteArray): List<Pair<Int, ByteArray>> {
        data class Start(val pos: Int, val scLen: Int)
        val starts = mutableListOf<Start>()
        var i = 0
        while (i <= annexB.size - 3) {
            if (annexB[i] == 0.toByte() && annexB[i+1] == 0.toByte()) {
                if (i+3 < annexB.size && annexB[i+2] == 0.toByte() && annexB[i+3] == 1.toByte()) {
                    starts.add(Start(i, 4)); i += 4; continue
                } else if (annexB[i+2] == 1.toByte()) {
                    starts.add(Start(i, 3)); i += 3; continue
                }
            }
            i++
        }
        return starts.mapIndexed { idx, s ->
            val end = if (idx + 1 < starts.size) starts[idx + 1].pos else annexB.size
            val nalType = annexB[s.pos + s.scLen].toInt() and 0x1F
            val nalBytes = annexB.copyOfRange(s.pos, end)
            nalType to nalBytes
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) error("EOS")
            off += n
        }
    }

    private fun readBigEndianInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off+1].toInt() and 0xFF) shl 16) or
        ((b[off+2].toInt() and 0xFF) shl 8) or
         (b[off+3].toInt() and 0xFF)
}
