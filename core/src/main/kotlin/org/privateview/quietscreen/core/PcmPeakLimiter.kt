package org.privateview.quietscreen.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.expm1

/** Zero-lookahead, channel-linked PCM16 limiter. No amplification or frame delay. */
class PcmPeakLimiter(val sampleRate: Int, val channels: Int) {
    init { require(sampleRate in 8000..384000 && channels in 1..32) }
    private val release = -expm1(-1.0 / (sampleRate * .250))
    private var gain = 1.0
    fun reset() { gain = 1.0 }
    fun process(input: ByteBuffer, output: ByteBuffer, frames: Int = input.remaining() / (channels * 2)) {
        require(frames >= 0 && frames.toLong() * channels * 2 <= input.remaining() &&
            frames.toLong() * channels * 2 <= output.remaining())
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val start = input.position()
            var peak = 0
            for (channel in 0 until channels) peak = maxOf(peak, abs(input.getShort(start + channel * 2).toInt()))
            val target = if (peak > CEILING) CEILING.toDouble() / peak else 1.0
            gain = if (target < gain) target else minOf(target, gain + release * (target - gain))
            for (channel in 0 until channels) output.putShort((input.short * gain).toInt().toShort())
        }
    }
    companion object { const val CEILING = 16384 } // Half full scale, approximately -6 dBFS.
}
