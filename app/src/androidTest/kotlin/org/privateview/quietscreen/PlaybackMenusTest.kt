package org.privateview.quietscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.video.PlaceholderSurface
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.extractor.NewPipeCatalog
import org.privateview.quietscreen.extractor.NetworkAudit
import org.privateview.quietscreen.playback.PlayerController
import org.privateview.quietscreen.playback.MediaSourceFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackMenusTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(description: String, seconds: Int = 45, predicate: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + seconds * 1000
        var complete = false
        while (android.os.SystemClock.elapsedRealtime() < until && !complete) {
            onMain { complete = predicate() }
            if (!complete) Thread.sleep(200)
        }
        assertTrue(description, complete)
    }
    private fun withPlayer(resolver: StreamResolver = NewPipeCatalog(), block: (PlayerController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var controller: PlayerController
        lateinit var surface: PlaceholderSurface
        onMain {
            controller = PlayerController(instrument.targetContext, resolver, scope)
            surface = PlaceholderSurface.newInstance(instrument.targetContext, false)
            controller.player.setVideoSurface(surface)
        }
        try { block(controller) }
        finally { onMain { controller.release(); surface.release(); scope.cancel() } }
    }

    @Test fun fixedQualityRetainsPausedPositionAndAutoRetainsPlayback() = withPlayer { controller ->
        onMain { controller.start(VideoId("9bZkp7q19f0"), 720) }
        await("Initial video did not start", 90) {
            assertNull(controller.status.value.error)
            controller.player.isPlaying && controller.player.videoFormat != null
        }
        onMain {
            assertTrue("Fixture has no supported 1080p fixed source", 1080 in controller.qualityHeights())
            controller.player.pause(); controller.player.seekTo(35000)
            controller.selectQuality(1080, 720)
        }
        await("Fixed 1080p source did not become ready") {
            assertNull(controller.status.value.error)
            controller.player.playbackState == Player.STATE_READY && (controller.player.videoFormat?.height ?: 0) == 1080
        }
        onMain {
            assertFalse(controller.player.playWhenReady)
            assertTrue(kotlin.math.abs(controller.player.currentPosition - 35000) <= 2000)
            assertTrue(controller.status.value.fixedQuality)
            controller.player.play()
        }
        await("Fixed source did not resume") { controller.player.isPlaying }
        var switchedAt = 0L
        onMain { switchedAt = controller.player.currentPosition; controller.selectQuality(null, 720) }
        await("Automatic source did not resume") {
            assertNull(controller.status.value.error)
            controller.player.isPlaying && (controller.player.videoFormat?.height ?: 0) in 1..720
        }
        onMain {
            assertTrue("Quality switch lost position", kotlin.math.abs(controller.player.currentPosition - switchedAt) <= 5000)
            assertFalse("No audio track after source switch", controller.audioChoices().isEmpty())
            val audio = controller.audioChoices().first()
            controller.selectAudio(audio.key)
        }
        println("TV_PLAYBACK fixed1080p=true pausedPositionPreserved=true autoCap720=true audioMenuTrackSelected=true")
    }

    @Test fun realCaptionsRenderOrReportAnExplicitFailureWithoutStoppingVideo() = withPlayer { controller ->
        NetworkAudit.begin()
        try {
            onMain { controller.start(VideoId("iG9CE55wbtY"), 720) }
            await("TED fixture did not start", 90) { assertNull(controller.status.value.error); controller.player.isPlaying }
            var caption: String? = null
            onMain {
                caption = controller.captionChoices().firstOrNull { it.key.startsWith("caption-") && it.label.startsWith("English") }?.key
                    ?: controller.captionChoices().firstOrNull { it.key != "off" }?.key
                assertNotNull("No supported caption supplied for this fixture", caption)
                controller.selectCaption(caption!!)
                controller.player.seekTo(15000)
            }
            var failed = false
            var rendered = false
            await("Caption neither rendered nor reported a bounded failure", 45) {
                assertNull("Caption load must not stop video", controller.status.value.error)
                failed = controller.status.value.captionError
                rendered = controller.player.currentCues.cues.any { it.text?.isNotEmpty() == true }
                failed || rendered
            }
            onMain {
                controller.selectCaption("off")
                assertTrue(controller.captionChoices().first().selected)
            }
            await("Video stopped after caption off") { controller.player.isPlaying }
            println("TV_CAPTIONS rendered=$rendered explicitLoadFailure=$failed videoContinues=true off=true")
            println("TV_NETWORK_HOSTS ${NetworkAudit.snapshot()}")
            // Load-error handling passing does not qualify the optional rendering
            // feature. A real render is mandatory before enabling it in a candidate.
            assertTrue("Optional caption rendering is not qualified", rendered)
        } finally { NetworkAudit.end() }
    }

    @Test fun midPlaybackExpiredSourceRefreshRestoresPositionExactlyOnce() {
        val catalog = NewPipeCatalog()
        val resolutions = AtomicInteger()
        val resolver = object : StreamResolver {
            override fun cancelPending() = catalog.cancelPending()
            override suspend fun resolve(id: VideoId): PlaybackPlan { resolutions.incrementAndGet(); return catalog.resolve(id) }
        }
        withPlayer(resolver) { controller ->
            onMain { controller.start(VideoId("9bZkp7q19f0"), 1080, true) }
            await("Initial separate stream did not start", 90) { assertNull(controller.status.value.error); controller.player.isPlaying }
            onMain { controller.player.seekTo(45000) }
            await("Initial seek did not complete") { controller.player.isPlaying && controller.player.currentPosition >= 45000 }
            var injectedAt = 0L
            onMain {
                injectedAt = controller.player.currentPosition
                val p = controller.plan!!
                fun expired(e: MediaEndpoint) = MediaEndpoint(e.url.toHttpUrl().newBuilder().setQueryParameter("expire", "1").build().toString())
                val stale = p.copy(hls = null, dash = null, videos = p.videos.map { it.copy(endpoint = expired(it.endpoint)) }, audio = p.audio.map { it.copy(endpoint = expired(it.endpoint)) })
                controller.player.setMediaSource(MediaSourceFactory().build(VariantSelector.select(stale, 1080, true)), injectedAt)
                controller.player.prepare(); controller.player.play()
            }
            await("Mid-playback expiry did not recover", 90) {
                assertNull(controller.status.value.error)
                controller.status.value.refreshCount == 1 && controller.player.isPlaying
            }
            onMain {
                assertEquals(1, controller.status.value.refreshCount)
                assertEquals("One initial resolution plus exactly one refresh", 2, resolutions.get())
                assertTrue("Refresh lost playback position", kotlin.math.abs(controller.player.currentPosition - injectedAt) <= 5000)
            }
            println("TV_PLAYBACK midExpiry=true freshResolutions=2 refreshes=1 positionPreserved=true")
        }
    }
}
