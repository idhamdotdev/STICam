package com.sticam.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProtocolTest {

    @Test
    fun typedHeader_roundTripsPayloadLength() {
        val header = TypedPacketHeader.create(type = 0x10, payloadLength = 0x01020304)

        assertArrayEquals(
            byteArrayOf(0x10, 0x01, 0x02, 0x03, 0x04),
            header,
        )
        assertEquals(0x01020304, TypedPacketHeader.payloadLength(header))
    }

    @Test
    fun annexBParser_supportsThreeAndFourByteStartCodes() {
        val data = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x11,
            0, 0, 1, 0x68, 0x22,
        )

        val units = H264AnnexB.parse(data)

        assertEquals(listOf(7, 8), units.map { it.type })
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x11), units[0].bytes)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x68, 0x22), units[1].bytes)
    }

    @Test
    fun annexBParser_ignoresTrailingEmptyStartCode() {
        val data = byteArrayOf(0, 0, 1, 0x67, 0, 0, 1)

        val units = H264AnnexB.parse(data)

        assertEquals(1, units.size)
        assertEquals(7, units.single().type)
    }

    @Test
    fun shedding_dropsDependencyChainAndRequestsOnlyOneKeyframe() {
        val policy = FrameSheddingPolicy(congestionThreshold = 2)

        assertEquals(FrameQueueAction.ENQUEUE, policy.decide(queueSize = 1, isKeyFrame = false))
        assertEquals(
            FrameQueueAction.DROP_AND_REQUEST_KEYFRAME,
            policy.decide(queueSize = 2, isKeyFrame = false),
        )
        assertEquals(
            FrameQueueAction.DROP_UNTIL_KEYFRAME,
            policy.decide(queueSize = 0, isKeyFrame = false),
        )
        assertEquals(
            FrameQueueAction.RESYNC_WITH_KEYFRAME,
            policy.decide(queueSize = 0, isKeyFrame = true),
        )
        assertEquals(FrameQueueAction.ENQUEUE, policy.decide(queueSize = 0, isKeyFrame = false))
    }

    @Test
    fun shedding_resyncsImmediatelyWhenCongestedFrameIsKeyframe() {
        val policy = FrameSheddingPolicy(congestionThreshold = 2)

        assertEquals(
            FrameQueueAction.RESYNC_WITH_KEYFRAME,
            policy.decide(queueSize = 2, isKeyFrame = true),
        )
    }
}
