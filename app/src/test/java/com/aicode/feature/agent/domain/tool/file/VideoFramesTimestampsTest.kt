package com.aicode.feature.agent.domain.tool.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFramesTimestampsTest {

    @Test
    fun evenTimestampsSkipsHeadAndTail() {
        // 100 秒的视频取 4 帧：首尾各留 5%，落点应全在 5..95 秒之间
        val times = evenFrameTimestamps(100_000L, 4)
        assertEquals(4, times.size)
        assertEquals(5_000L, times.first())
        assertEquals(95_000L, times.last())
    }

    @Test
    fun evenTimestampsAreMonotonic() {
        val times = evenFrameTimestamps(60_000L, 8)
        assertEquals(8, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun evenTimestampsStayWithinDuration() {
        val duration = 3_000L
        val times = evenFrameTimestamps(duration, 8)
        assertTrue(times.all { it in 0..duration })
    }

    @Test
    fun singleFrameFallsInMiddle() {
        // 1 帧时取正中间，避免只抽到片头黑场
        assertEquals(listOf(50_000L), evenFrameTimestamps(100_000L, 1))
    }

    @Test
    fun degenerateInputsYieldEmpty() {
        assertTrue(evenFrameTimestamps(100_000L, 0).isEmpty())
        assertTrue(evenFrameTimestamps(0L, 4).isEmpty())
        assertTrue(evenFrameTimestamps(-1L, 4).isEmpty())
    }

    @Test
    fun veryShortVideoStillProducesRequestedCount() {
        // 极短视频（200ms）取 4 帧：允许重复落点，但不能少于请求数量语义上的顺序性
        val times = evenFrameTimestamps(200L, 4)
        assertEquals(4, times.size)
        assertTrue(times.all { it in 0..200L })
    }
}
