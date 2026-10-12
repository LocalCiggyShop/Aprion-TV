package org.privateview.quietscreen

import android.util.Log
import androidx.media3.common.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.extractor.NewPipeCatalog
import org.privateview.quietscreen.playback.MediaSourceFactory
import org.privateview.quietscreen.playback.PlayerController
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Diagnostic controls only. No production policy override or signed-URL logging. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioVideoComparisonTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val id = VideoId(args.getString("videoId") ?: "plN7JMbadRg")
    private val seconds = (args.getString("phaseSeconds") ?: "120").toInt().coerceIn(30, 600)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: PlayerController
    private lateinit var surface: PlaceholderSurface
    private val sinkError = AtomicReference<String?>(null)
    private val underruns = AtomicInteger()
    private val droppedFrames = AtomicInteger()

    private fun create(resolver: StreamResolver, profile: Boolean = false) = instrument.runOnMainSync {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        controller = PlayerController(instrument.targetContext, resolver, scope, enableEmulatorProfile = profile)
        surface = PlaceholderSurface.newInstance(instrument.targetContext, false)
        controller.player.setVideoSurface(surface)
        controller.player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                underruns.incrementAndGet()
                Log.w("QuietScreenProbe", "UNDERRUN bytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")
            }
            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                // Avoid exception messages, which can contain URLs; preserve class names.
                sinkError.set(audioSinkError.javaClass.simpleName)
                Log.e("QuietScreenProbe", "SINK_ERROR type=${audioSinkError.javaClass.simpleName}")
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i("QuietScreenProbe", "AUDIO_DECODER name=$decoderName initMs=$initializationDurationMs")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i("QuietScreenProbe", "VIDEO_DECODER name=$decoderName initMs=$initializationDurationMs")
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
                droppedFrames.addAndGet(dropped)
            }
        })
    }

    private fun awaitPlaying(video: Boolean) {
        val deadline = System.nanoTime() + 90_000_000_000L
        var ready = false
        while (!ready && System.nanoTime() < deadline) {
            instrument.runOnMainSync {
                assertNull("Player failed", controller.status.value.error)
                assertNull(controller.player.playerError)
                val videoSelected = controller.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                ready = controller.player.isPlaying && controller.player.audioFormat != null && videoSelected == video
            }
            Thread.sleep(100)
        }
        assertTrue("Expected playback/track selection did not become ready", ready)
    }

    private fun observe(name: String, video: Boolean, durationSeconds: Int = seconds) {
        awaitPlaying(video)
        val previousUnderruns = underruns.get()
        val previousDropped = droppedFrames.get()
        var audioIdentity = ""
        instrument.runOnMainSync {
            val a = checkNotNull(controller.player.audioFormat)
            audioIdentity = "${a.sampleMimeType}/${a.sampleRate}/${a.channelCount}/${a.codecs}"
            Log.i("QuietScreenProbe", "PHASE_START name=$name seconds=$durationSeconds player=${System.identityHashCode(controller.player)} audio=$audioIdentity")
        }
        var lastPosition = -1L
        var stall = 0
        var buffering = 0
        var previousState = -1
        repeat(durationSeconds) { elapsed ->
            Thread.sleep(1000)
            instrument.runOnMainSync {
                val p = controller.player
                assertNull(controller.status.value.error)
                assertNull(p.playerError)
                assertNull("Audio sink error", sinkError.get())
                assertTrue("Unexpected completion", p.playbackState != Player.STATE_ENDED)
                // Network buffering is a different failure from a READY clock stall.
                // Keep the existing Phase3 network limit; do not retry the player.
                val ready = p.playbackState == Player.STATE_READY && p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                stall = if (p.currentPosition <= lastPosition && ready) stall + 1 else 0
                buffering = if (p.playbackState == Player.STATE_BUFFERING) buffering + 1 else 0
                if (p.playbackState != previousState) Log.i("QuietScreenProbe", "PHASE_STATE name=$name state=${p.playbackState} position=${p.currentPosition} bufferedMs=${p.totalBufferedDuration}")
                previousState = p.playbackState
                assertTrue("READY position stalled for five seconds", stall < 5)
                assertTrue("Network buffering exceeded the existing 30-second limit", buffering < 30)
                lastPosition = p.currentPosition
                val a = checkNotNull(p.audioFormat)
                assertEquals("Audio format changed within phase", audioIdentity, "${a.sampleMimeType}/${a.sampleRate}/${a.channelCount}/${a.codecs}")
                if (elapsed % 15 == 0) {
                    val v = p.videoFormat
                    Log.i("QuietScreenProbe", "PHASE_SAMPLE name=$name position=${p.currentPosition} bufferedMs=${p.totalBufferedDuration} suppression=${p.playbackSuppressionReason} height=${v?.height ?: 0} fps=${v?.frameRate ?: 0f} videoMime=${v?.sampleMimeType} videoBitrate=${v?.bitrate ?: 0} underruns=${underruns.get()-previousUnderruns} dropped=${droppedFrames.get()-previousDropped}")
                }
            }
        }
        Log.i("QuietScreenProbe", "PHASE_END name=$name underruns=${underruns.get()-previousUnderruns} dropped=${droppedFrames.get()-previousDropped} audibleQuality=UNVERIFIED")
    }

    private fun release() = instrument.runOnMainSync { controller.release(); surface.release(); scope.cancel() }

    @Test fun sameStreamVideoAndAdaptationComparison() {
        val catalog = NewPipeCatalog()
        val resolves = AtomicInteger()
        val resolver = object : StreamResolver {
            override fun cancelPending() = catalog.cancelPending()
            override suspend fun resolve(id: VideoId): PlaybackPlan { resolves.incrementAndGet(); return catalog.resolve(id) }
        }
        create(resolver)
        try {
            instrument.runOnMainSync { controller.start(id, 1080) }
            awaitPlaying(true)
            fun configure(video: Boolean, highest: Boolean) = instrument.runOnMainSync {
                controller.player.trackSelectionParameters = controller.player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !video).setForceHighestSupportedBitrate(highest).build()
            }
            configure(true, true); observe("video-fixed-a", true)
            configure(false, true); observe("audio-only", false)
            configure(true, true); observe("video-fixed-b", true)
            configure(true, false); observe("video-adaptive", true)
            assertEquals("Comparison unexpectedly resolved a new stream", 1, resolves.get())
            Log.i("QuietScreenProbe", "COMPARISON_COMPLETE samePlayer=true resolves=${resolves.get()}")
        } finally { release() }
    }

    @Test fun pairedCodecComparison() {
        val catalog = NewPipeCatalog()
        val plan = runBlocking { catalog.resolve(id) }
        val videos = plan.videos.filter { it.videoOnly && it.delivery == Delivery.PROGRESSIVE && it.height <= 1080 }
        val avc = videos.filter { MimeTypes.getMediaMimeType(it.codec) == MimeTypes.VIDEO_H264 }
        val vp9 = videos.filter { MimeTypes.getMediaMimeType(it.codec) == MimeTypes.VIDEO_VP9 }
        val pairs = avc.flatMap { a -> vp9.filter { it.height == a.height && it.fps == a.fps }.map { a to it } }
        val pair = pairs.maxWithOrNull(compareBy<Pair<VideoVariant, VideoVariant>> { it.first.height }.thenBy { it.first.fps })
        val audio = plan.audio.filter { it.delivery == Delivery.PROGRESSIVE && MimeTypes.getMediaMimeType(it.codec) == MimeTypes.AUDIO_AAC }
            .sortedWith(compareByDescending<AudioVariant> { it.original }.thenByDescending { it.bitrate }).firstOrNull()
        assumeTrue("No same-resolution/frame-rate AVC/VP9 pair with AAC; comparison unavailable", pair != null && audio != null)
        create(object : StreamResolver {
            override fun cancelPending() {}
            override suspend fun resolve(id: VideoId): PlaybackPlan = error("No source refresh in paired diagnostic")
        })
        try {
            for ((name, video) in listOf("paired-vp9" to pair!!.second, "paired-avc" to pair.first)) {
                instrument.runOnMainSync {
                    controller.player.setMediaSource(MediaSourceFactory().build(PlaybackSelection.Separate(video, audio!!)))
                    controller.player.prepare(); controller.player.play()
                }
                awaitPlaying(true)
                instrument.runOnMainSync {
                    assertEquals(video.height, controller.player.videoFormat!!.height)
                    assertEquals(MimeTypes.getMediaMimeType(video.codec), controller.player.videoFormat!!.sampleMimeType)
                }
                observe(name, true)
            }
            Log.i("QuietScreenProbe", "PAIRED_COMPLETE sameAudioEndpoint=true height=${pair!!.first.height} fps=${pair.first.fps}")
        } finally { release() }
    }

    @Test fun emulatorProfileComparisonAndControls() {
        val catalog = NewPipeCatalog()
        val plan = runBlocking { catalog.resolve(id) }
        val resolver = object : StreamResolver {
            override fun cancelPending() {}
            override suspend fun resolve(id: VideoId): PlaybackPlan = plan
        }
        // Same APK, AVD and resolved plan. The source-profile flag is the only
        // application configuration variable; both phases start at position zero.
        val profiles = if (args.getString("onlyProfile") == "true") listOf(true) else listOf(false, true)
        for (profile in profiles) {
            create(resolver, profile)
            try {
                instrument.runOnMainSync { controller.start(id, 1080) }
                awaitPlaying(true)
                if (profile) instrument.runOnMainSync {
                    assertEquals("Separate video + audio", controller.status.value.source)
                    assertEquals(MimeTypes.VIDEO_H264, controller.player.videoFormat!!.sampleMimeType)
                    assertEquals(1080, controller.player.videoFormat!!.height)
                }
                val duration = if (profile) (args.getString("profileSeconds") ?: "600").toInt().coerceIn(30, 1800) else seconds
                observe(if (profile) "profile-avc" else "profile-original", true, duration)
                if (profile) {
                    var endPosition = 0L
                    instrument.runOnMainSync { endPosition = controller.player.duration }
                    assertTrue(endPosition > 0)
                    for (position in listOf(1000L, endPosition / 2, endPosition - 5000L)) {
                        instrument.runOnMainSync { controller.player.pause() }
                        Thread.sleep(500)
                        instrument.runOnMainSync {
                            assertEquals("Paused", controller.status.value.text)
                            controller.player.seekTo(position); controller.player.play()
                        }
                        awaitPlaying(true)
                        instrument.runOnMainSync {
                            assertTrue(kotlin.math.abs(controller.player.currentPosition-position) < 1500)
                            assertEquals("Playing", controller.status.value.text)
                        }
                    }
                    var ended = false
                    val deadline = System.nanoTime()+30_000_000_000L
                    while (!ended && System.nanoTime()<deadline) {
                        Thread.sleep(100)
                        instrument.runOnMainSync { assertNull(controller.status.value.error); ended=controller.player.playbackState==Player.STATE_ENDED }
                    }
                    assertTrue("Profile did not complete after final seek", ended)
                    Log.i("QuietScreenProbe", "PROFILE_CONTROLS_COMPLETE pauseResume=true seeks=3 end=true")
                }
            } finally { release() }
        }
    }
}
