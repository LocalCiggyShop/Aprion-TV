package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

class PcmPeakLimiterTest {
    private fun samples(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach { putShort(it.toShort()) }; flip()
    }
    private fun run(limiter: PcmPeakLimiter, values: IntArray): IntArray {
        val input = samples(*values); val out = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        limiter.process(input, out); assertFalse(input.hasRemaining()); out.flip()
        return IntArray(values.size) { out.short.toInt() }
    }
    @Test fun quietAudioAndSilenceAreUnchangedWithoutMakeupGain() {
        val values = intArrayOf(0, 120, -300, 16000, -16384, 1000)
        assertArrayEquals(values, run(PcmPeakLimiter(48000, 1), values))
    }
    @Test fun linkedChannelsCapBothPolaritiesAndNeverAmplify() {
        val values = intArrayOf(32767, 16384, -32768, 0, 100, -200, 30000, -30000)
        val out = run(PcmPeakLimiter(48000, 2), values)
        for (i in values.indices) {
            assertTrue(abs(out[i]) <= PcmPeakLimiter.CEILING)
            assertTrue(abs(out[i]) <= abs(values[i]))
        }
        assertEquals(16384, out[0]); assertTrue(abs(out[1] - 8192) <= 1)
        assertEquals(-16384, out[2])
    }
    @Test fun chunkBoundariesPreserveSamplesAndResetRestoresQuietPassThrough() {
        val values = IntArray(48000 * 2) { (sin(it * .13) * if (it < 1000) 32767 else 4000).toInt() }
        val whole = run(PcmPeakLimiter(48000, 2), values)
        val limiter = PcmPeakLimiter(48000, 2)
        val pieces = ArrayList<Int>()
        var index = 0
        while (index < values.size) {
            val end = minOf(values.size, index + 274)
            pieces.addAll(run(limiter, values.copyOfRange(index, end)).toList()); index = end
        }
        assertArrayEquals(whole, pieces.toIntArray())
        assertTrue(whole.all { abs(it) <= PcmPeakLimiter.CEILING })
        limiter.reset(); assertArrayEquals(intArrayOf(4000, -4000), run(limiter, intArrayOf(4000, -4000)))
    }
    @Test fun malformedFormatsAndInsufficientOutputFailBeforeConsumption() {
        assertThrows(IllegalArgumentException::class.java) { PcmPeakLimiter(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { PcmPeakLimiter(48000, 0) }
        val input = samples(32767, -32768)
        assertThrows(IllegalArgumentException::class.java) { PcmPeakLimiter(48000, 2).process(input, ByteBuffer.allocate(2)) }
        assertEquals(0, input.position())
    }
}
