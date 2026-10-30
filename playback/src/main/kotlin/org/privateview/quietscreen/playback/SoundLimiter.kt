package org.privateview.quietscreen.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.privateview.quietscreen.core.PcmPeakLimiter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only installed when enabled. Buffers are reused; incomplete frames carry across calls. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SoundLimiterProcessor : BaseAudioProcessor() {
    private var limiter: PcmPeakLimiter? = null
    private var frame = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount !in 1..32 || format.sampleRate !in 8000..384000)
            throw AudioProcessor.UnhandledAudioFormatException(format)
        return format
    }
    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val active = checkNotNull(limiter)
        val frameBytes = frame.capacity()
        val bytes = (input.remaining() + frame.position()) / frameBytes * frameBytes
        val output = replaceOutputBuffer(bytes)
        if (frame.position() != 0) {
            while (input.hasRemaining() && frame.hasRemaining()) frame.put(input.get())
            if (!frame.hasRemaining()) { frame.flip(); active.process(frame, output); frame.clear() }
        }
        active.process(input, output)
        while (input.hasRemaining()) frame.put(input.get())
        output.flip()
    }
    override fun onFlush(metadata: AudioProcessor.StreamMetadata) {
        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) return
        limiter = PcmPeakLimiter(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        frame = ByteBuffer.allocate(inputAudioFormat.channelCount * 2).order(ByteOrder.LITTLE_ENDIAN)
    }
    override fun onReset() { limiter = null; frame = ByteBuffer.allocate(0) }
}

/** Preserve Media3's timing/silence/speed chain; cap peaks after its processing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class LimitedAudioChain : DefaultAudioSink.DefaultAudioProcessorChain() {
    private val processors = super.getAudioProcessors() + SoundLimiterProcessor()
    override fun getAudioProcessors(): Array<AudioProcessor> = processors
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SoundLimiterRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(LimitedAudioChain()).build()
        // Compressed passthrough/offload bypass processors. Ask the normal codec
        // renderer to decode to PCM while limiting, retaining channel count/timing.
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatSupport(format: Format): Int = if (format.sampleMimeType == MimeTypes.AUDIO_RAW)
                super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }
}
