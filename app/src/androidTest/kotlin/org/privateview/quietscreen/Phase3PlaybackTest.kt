package org.privateview.quietscreen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.VideoId
import org.privateview.quietscreen.core.StreamResolver
import org.privateview.quietscreen.core.PlaybackPlan
import org.privateview.quietscreen.core.MediaEndpoint
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.privateview.quietscreen.extractor.NewPipeCatalog
import org.privateview.quietscreen.playback.PlayerController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Phase3PlaybackTest {
    @Test fun realDecoderStartsAndSeekResumeWorks() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val id = VideoId(arguments.getString("videoId") ?: "dQw4w9WgXcQ")
        val height = (arguments.getString("height") ?: "720").toInt()
        val separate = arguments.getString("separate") == "true"
        val observationSeconds = (arguments.getString("observationSeconds") ?: "0").toInt().coerceIn(0, 600)
        val expiredFirst = arguments.getString("expiredFirst") == "true"
        val resolves = AtomicInteger(0)
        val catalog = NewPipeCatalog()
        val resolver = object : StreamResolver {
            override fun cancelPending() = catalog.cancelPending()
            override suspend fun resolve(id: VideoId): PlaybackPlan {
                val plan = catalog.resolve(id)
                if (resolves.incrementAndGet() != 1 || !expiredFirst) return plan
                // Deliberately expire our own anonymous, just-extracted URLs in memory.
                // Keep the same HTTPS origin and do not log or persist the URLs.
                fun expired(endpoint: MediaEndpoint) = MediaEndpoint(endpoint.url.toHttpUrl().newBuilder().setQueryParameter("expire", "1").build().toString())
                return plan.copy(videos = plan.videos.map { it.copy(endpoint = expired(it.endpoint)) },
                    audio = plan.audio.map { it.copy(endpoint = expired(it.endpoint)) }, hls = null, dash = null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var controller: PlayerController
        lateinit var surface: androidx.media3.exoplayer.video.PlaceholderSurface
        val error = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        instrument.runOnMainSync {
            controller = PlayerController(instrument.targetContext, resolver, scope)
            surface = androidx.media3.exoplayer.video.PlaceholderSurface.newInstance(instrument.targetContext, false)
            controller.player.setVideoSurface(surface)
            controller.start(id, height, separate)
        }
        try {
            val end = System.nanoTime() + 90_000_000_000L
            while (System.nanoTime() < end && !ready.get() && error.get() == null) {
                instrument.runOnMainSync { error.set(controller.status.value.error?.name); ready.set(controller.player.isPlaying && (controller.player.videoFormat?.height ?: 0) > 0) }
                Thread.sleep(250)
            }
            assertNull("Extraction/player failure", error.get())
            assertTrue("No decoded video started within 90 seconds", ready.get())
            instrument.runOnMainSync {
                assertTrue("Resolution is below requested proof", (controller.player.videoFormat?.height ?: 0) >= height)
                if (separate) assertEquals("Separate video + audio", controller.status.value.source)
                assertNotNull("No audio format selected", controller.player.audioFormat)
                if (expiredFirst) {
                    assertEquals("Exactly one URL refresh expected", 1, controller.status.value.refreshCount)
                    assertEquals("Expired attempt plus fresh attempt", 2, resolves.get())
                }
            }
            // Decode continuously at normal speed; this cannot classify ads baked into content.
            val observedUntil = System.nanoTime() + observationSeconds * 1_000_000_000L
            var ended = false
            var stalledSeconds = 0
            var lastPosition = -1L
            while (System.nanoTime() < observedUntil && !ended) {
                Thread.sleep(1000)
                instrument.runOnMainSync {
                    assertNull("Playback failed during observation", controller.status.value.error)
                    ended = controller.player.playbackState == androidx.media3.common.Player.STATE_ENDED
                    val position = controller.player.currentPosition
                    stalledSeconds = if (position == lastPosition && !ended) stalledSeconds + 1 else 0
                    lastPosition = position
                    assertTrue("Playback stalled for 30 seconds", stalledSeconds < 30)
                    assertFalse("Media3 marked an ad period", controller.player.isPlayingAd)
                }
            }
            var duration = 0L
            instrument.runOnMainSync { duration = controller.player.duration; assertTrue(duration > 0) }
            for (position in listOf(1000L, duration / 2, (duration - 5000).coerceAtLeast(0))) {
                instrument.runOnMainSync { controller.player.pause() }
                Thread.sleep(300)
                instrument.runOnMainSync {
                    assertFalse(controller.player.isPlaying)
                    val expectedStatus = if (controller.player.playbackState == androidx.media3.common.Player.STATE_ENDED) "Ended" else "Paused"
                    assertEquals("Paused playback must not be labelled Playing", expectedStatus, controller.status.value.text)
                    controller.player.seekTo(position); controller.player.play()
                }
                val seekDeadline = System.nanoTime() + 30_000_000_000L
                var resumed = false
                while (System.nanoTime() < seekDeadline && !resumed) {
                    Thread.sleep(250)
                    instrument.runOnMainSync {
                        assertNull(controller.status.value.error)
                        resumed = controller.player.isPlaying && controller.player.currentPosition >= position
                    }
                }
                assertTrue("Playback did not resume after seek", resumed)
                instrument.runOnMainSync { assertEquals("Playing", controller.status.value.text) }
            }
            val endDeadline = System.nanoTime() + 30_000_000_000L
            ended = false
            while (System.nanoTime() < endDeadline && !ended) {
                Thread.sleep(250)
                instrument.runOnMainSync { assertNull(controller.status.value.error); ended = controller.player.playbackState == androidx.media3.common.Player.STATE_ENDED }
            }
            assertTrue("Playback did not reach natural end after final seek", ended)
            println("PHASE3 id=${id.value} height=$height separate=$separate observationSeconds=$observationSeconds decodedAudio=true end=true")
        } finally { instrument.runOnMainSync { controller.release(); surface.release(); scope.cancel() } }
        // Visual advertisement classification and audible A/V sync remain manual.
    }
}
