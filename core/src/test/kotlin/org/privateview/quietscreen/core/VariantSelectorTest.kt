package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class VariantSelectorTest {
    private fun v(height: Int, only: Boolean = true, delivery: Delivery = Delivery.PROGRESSIVE) = VideoVariant("$height-$only", MediaEndpoint("https://r1.googlevideo.com/video"), "video/mp4", "avc1.640028", height, 30, 2000000, only, delivery)
    private fun plan(videos: List<VideoVariant>, audio: List<AudioVariant> = listOf(AudioVariant("a", MediaEndpoint("https://r1.googlevideo.com/audio"), "audio/mp4", "mp4a.40.2", 128000, true, "en", Delivery.PROGRESSIVE))) = PlaybackPlan(VideoId("jNQXAC9IVRw"), "Fixture", 60000, 0, videos, audio, null, null, emptyList())
    @Test fun selectsSeparate1080WithAudioAndHonoursCapability() {
        val p = plan(listOf(v(2160), v(1080), v(720, false)))
        assertTrue(VariantSelector.select(p) is PlaybackSelection.Separate)
        val s = VariantSelector.select(p, supported = { it.height <= 720 }) as PlaybackSelection.Muxed
        assertEquals(720, s.video.height)
    }
    @Test(expected = AppException::class) fun videoOnlyWithoutAudioIsNotPlayable() { VariantSelector.select(plan(listOf(v(1080)), emptyList())) }
    @Test(expected = AppException::class) fun dashRepresentationIsNotAssumedProgressive() { VariantSelector.select(plan(listOf(v(1080, delivery = Delivery.DASH)))) }
    @Test fun refreshAndRetriesAreFinite() { val b = RecoveryBudget(); assertTrue(b.allowRefresh()); assertFalse(b.allowRefresh()); assertEquals(1000L, b.nextRetryDelayMs()); assertEquals(3000L, b.nextRetryDelayMs()); repeat(5) { assertNull(b.nextRetryDelayMs()) } }
    @Test fun endpointDoesNotLeakSignedUrlInLogs() { assertFalse(MediaEndpoint("https://r1.googlevideo.com/?token=secret").toString().contains("secret")) }
    @Test fun emulatorProfileChoosesSupportedAvcAndAacWithinCap() {
        val avc = v(1080)
        val vp9 = v(1080).copy(key = "vp9", codec = "vp09.00.40.08", bitrate = 1000000)
        val p = plan(listOf(v(2160), avc, vp9)).copy(hls = MediaEndpoint("https://r1.googlevideo.com/manifest"))
        val selected = VariantSelector.emulatorAvcSelection(p, 1080) { true }!!
        assertSame(avc, selected.video)
        assertEquals("mp4a.40.2", selected.audio.codec)
        assertTrue("Normal device policy must retain HLS", VariantSelector.select(p) is PlaybackSelection.Manifest)
    }
    @Test fun emulatorProfileFallsBackWhenCompleteSupportedPairIsUnavailable() {
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080))), 720) { true })
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080))), 1080) { false })
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080)), emptyList()), 1080) { true })
        val opus = AudioVariant("opus", MediaEndpoint("https://r1.googlevideo.com/audio"), "audio/webm", "opus", 160000, true, "en", Delivery.PROGRESSIVE)
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080)), listOf(opus)), 1080) { true })
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080).copy(codec = "vp09.00.40.08"))), 1080) { true })
    }
    @Test fun emulatorProfileDoesNotReplaceOriginalAudioWithDubbedAac() {
        val originalOpus = AudioVariant("original", MediaEndpoint("https://r1.googlevideo.com/audio"), "audio/webm", "opus", 160000, true, "en", Delivery.PROGRESSIVE)
        val dubbedAac = AudioVariant("dub", MediaEndpoint("https://r1.googlevideo.com/dub"), "audio/mp4", "mp4a.40.2", 128000, false, "es", Delivery.PROGRESSIVE)
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080)), listOf(originalOpus, dubbedAac)), 1080) { true })
        assertNull(VariantSelector.emulatorAvcSelection(plan(listOf(v(1080)), listOf(dubbedAac)), 1080) { true })
    }
}
