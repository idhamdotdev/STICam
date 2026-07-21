package com.sticam.server

/** Pure protocol helpers kept independent from Android and socket APIs. */
internal object TypedPacketHeader {
    const val SIZE = 5

    fun create(type: Byte, payloadLength: Int): ByteArray {
        require(payloadLength >= 0) { "Negative payload length" }
        return byteArrayOf(
            type,
            (payloadLength ushr 24 and 0xFF).toByte(),
            (payloadLength ushr 16 and 0xFF).toByte(),
            (payloadLength ushr 8 and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte(),
        )
    }

    fun payloadLength(header: ByteArray, offset: Int = 1): Int {
        require(offset >= 0 && offset + 4 <= header.size) { "Incomplete packet header" }
        return ((header[offset].toInt() and 0xFF) shl 24) or
            ((header[offset + 1].toInt() and 0xFF) shl 16) or
            ((header[offset + 2].toInt() and 0xFF) shl 8) or
            (header[offset + 3].toInt() and 0xFF)
    }
}

internal data class NalUnit(val type: Int, val bytes: ByteArray)

internal object H264AnnexB {
    fun parse(data: ByteArray): List<NalUnit> {
        data class StartCode(val position: Int, val length: Int)

        val starts = mutableListOf<StartCode>()
        var index = 0
        while (index <= data.size - 3) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (
                    index + 3 < data.size &&
                    data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte()
                ) {
                    starts += StartCode(index, 4)
                    index += 4
                    continue
                }
                if (data[index + 2] == 1.toByte()) {
                    starts += StartCode(index, 3)
                    index += 3
                    continue
                }
            }
            index++
        }

        return starts.mapIndexedNotNull { nalIndex, start ->
            val end = starts.getOrNull(nalIndex + 1)?.position ?: data.size
            val payloadStart = start.position + start.length
            if (payloadStart >= end) return@mapIndexedNotNull null
            NalUnit(
                type = data[payloadStart].toInt() and 0x1F,
                bytes = data.copyOfRange(start.position, end),
            )
        }
    }
}

internal enum class FrameQueueAction {
    ENQUEUE,
    DROP_AND_REQUEST_KEYFRAME,
    DROP_UNTIL_KEYFRAME,
    RESYNC_WITH_KEYFRAME,
}

/**
 * Once congestion drops a reference frame, all following deltas are unsafe.
 * This policy drops them until an IDR arrives, then starts a fresh queue.
 */
internal class FrameSheddingPolicy(
    private val congestionThreshold: Int = 10,
) {
    private var awaitingKeyFrame = false

    fun decide(queueSize: Int, isKeyFrame: Boolean): FrameQueueAction {
        if (awaitingKeyFrame) {
            return if (isKeyFrame) {
                awaitingKeyFrame = false
                FrameQueueAction.RESYNC_WITH_KEYFRAME
            } else {
                FrameQueueAction.DROP_UNTIL_KEYFRAME
            }
        }

        if (queueSize < congestionThreshold) return FrameQueueAction.ENQUEUE

        if (isKeyFrame) return FrameQueueAction.RESYNC_WITH_KEYFRAME

        awaitingKeyFrame = true
        return FrameQueueAction.DROP_AND_REQUEST_KEYFRAME
    }

    fun reset() {
        awaitingKeyFrame = false
    }
}
