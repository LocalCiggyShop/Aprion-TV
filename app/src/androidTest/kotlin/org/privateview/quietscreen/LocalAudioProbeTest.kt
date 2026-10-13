package org.privateview.quietscreen

import android.util.Log
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.playback.PlayerController
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Generated, public-domain test signal. No download, extractor, network or microphone.
 * Uses the production controller, load control, focus handling and default audio sink.
 * Only source creation is local; the production network allowlist remains untouched.
 * This is an audio-only isolation test, not a video/sync or listening qualification.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalAudioProbeTest {
    @Test fun deterministicPcmThroughProductionPlayer() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val seconds = (args.getString("probeSeconds") ?: "720").toInt().coerceIn(30, 1800)
        val rate = (args.getString("sampleRate") ?: "44100").toInt()
        require(rate == 44100 || rate == 48000)
        val compressed = args.getString("compressed") == "true"
        val fixture = File(instrument.targetContext.cacheDir, if (compressed) "generated-audio-probe.m4a" else "generated-audio-probe.wav")
        // One continuous file avoids loop transitions. A small reusable block bounds memory.
        if (compressed) writeAac(fixture, rate, seconds + 30) else writeWave(fixture, rate, seconds + 30)
        val resolver = object : StreamResolver {
            override fun cancelPending() {}
            override suspend fun resolve(id: VideoId): PlaybackPlan = error("Local probe must not resolve online")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var controller: PlayerController
        instrument.runOnMainSync {
            controller = PlayerController(instrument.targetContext, resolver, scope)
            controller.player.setMediaSource(ProgressiveMediaSource.Factory(DefaultDataSource.Factory(instrument.targetContext))
                .createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(fixture))))
            controller.player.prepare()
            controller.player.play()
        }
        fun awaitPlaying() {
            val deadline = System.nanoTime() + 30_000_000_000L
            var playing = false
            while (!playing && System.nanoTime() < deadline) {
                instrument.runOnMainSync {
                    assertNull(controller.player.playerError)
                    playing = controller.player.isPlaying
                }
                Thread.sleep(100)
            }
            assertTrue("Local PCM did not play", playing)
        }
        try {
            awaitPlaying()
            Log.i("AprionTVProbe", "START rate=$rate seconds=$seconds compressed=$compressed amplitude=0.25 stereo left=440Hz right=660Hz")
            var previous = -1L
            var stalled = 0
            repeat(seconds) { elapsed ->
                Thread.sleep(1000)
                instrument.runOnMainSync {
                    assertNull("Local PCM decoder error", controller.player.playerError)
                    assertTrue("Unexpected local PCM end", controller.player.playbackState != Player.STATE_ENDED)
                    val position = controller.player.currentPosition
                    stalled = if (position <= previous) stalled + 1 else 0
                    assertTrue("Local PCM position stalled for five seconds", stalled < 5)
                    previous = position
                    if (elapsed % 15 == 0) Log.i("AprionTVProbe", "SAMPLE position=$position playing=${controller.player.isPlaying} suppression=${controller.player.playbackSuppressionReason} rate=${controller.player.audioFormat?.sampleRate}")
                }
            }
            // Exercise the same real sink after sustained playback, without recreating it.
            for (position in listOf(1000L, seconds * 500L, (seconds + 28) * 1000L)) {
                instrument.runOnMainSync { controller.player.pause() }
                Thread.sleep(1000)
                instrument.runOnMainSync {
                    assertFalse(controller.player.isPlaying)
                    assertEquals("Paused", controller.status.value.text)
                    controller.player.seekTo(position)
                    controller.player.play()
                }
                awaitPlaying()
                Thread.sleep(250)
                instrument.runOnMainSync {
                    assertEquals("Playing", controller.status.value.text)
                    assertTrue("Seek did not land near requested position", kotlin.math.abs(controller.player.currentPosition - position) < 1500)
                }
            }
            val deadline = System.nanoTime() + 15_000_000_000L
            var ended = false
            while (!ended && System.nanoTime() < deadline) {
                Thread.sleep(100)
                instrument.runOnMainSync { assertNull(controller.player.playerError); ended = controller.player.playbackState == Player.STATE_ENDED }
            }
            assertTrue("PCM did not reach natural end", ended)
            Log.i("AprionTVProbe", "COMPLETE seconds=$seconds rate=$rate compressed=$compressed controls=true end=true audibleQuality=UNVERIFIED")
        } finally {
            instrument.runOnMainSync { controller.release(); scope.cancel() }
            fixture.delete()
        }
    }

    // Android's installed AAC encoder is the only additional component. No external
    // encoder executable or downloaded media is required. Generation precedes observation.
    private fun writeAac(file: File, rate: Int, seconds: Int) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var codecStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); codecStarted = true
            val info = MediaCodec.BufferInfo()
            val totalFrames = rate.toLong() * seconds
            var frame = 0L
            var inputEnded = false
            var outputEnded = false
            var track = -1
            val deadline = System.nanoTime() + 180_000_000_000L
            while (!outputEnded) {
                check(System.nanoTime() < deadline) { "AAC generation timed out" }
                if (!inputEnded) {
                    val input = codec.dequeueInputBuffer(1000)
                    if (input >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(input)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val start = frame
                        val count = minOf(buffer.remaining() / 4, (totalFrames - frame).toInt())
                        repeat(count) {
                            buffer.putShort((8191 * sin(2 * PI * 440 * frame / rate)).toInt().toShort())
                            buffer.putShort((8191 * sin(2 * PI * 660 * frame / rate)).toInt().toShort())
                            frame++
                        }
                        inputEnded = frame == totalFrames
                        codec.queueInputBuffer(input, 0, count * 4, start * 1_000_000 / rate,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 1000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted)
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted)
                        val buffer = checkNotNull(codec.getOutputBuffer(output))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
        } finally {
            try { if (codecStarted) codec.stop() } finally { codec.release() }
            try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
        }
    }

    private fun writeWave(file: File, rate: Int, seconds: Int) {
        val dataBytes = rate * seconds * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(dataBytes + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4)
            .putShort(4).putShort(16).put("data".toByteArray()).putInt(dataBytes)
        val block = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until rate) {
            block.putShort((8191 * sin(2 * PI * 440 * frame / rate)).toInt().toShort())
            block.putShort((8191 * sin(2 * PI * 660 * frame / rate)).toInt().toShort())
        }
        FileOutputStream(file).buffered().use { output ->
            output.write(header.array())
            repeat(seconds) { output.write(block.array()) }
        }
    }
}
