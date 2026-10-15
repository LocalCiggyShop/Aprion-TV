package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.*
import java.io.File

class LiveChannelTest {
    @Test fun publicVideosTabPaginatesLatestFirstAndBindsChannelCursor() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        try {
            val id = ChannelId("UC16niRr50-MSBwiO3YDb3RA")
            val first = catalog.channel(id)
            assertNotNull("Channel pagination unavailable", first.cursor)
            val second = catalog.channel(id, first.cursor)
            val videos = first.videos + second.videos
            assertTrue(videos.all { it.channelId == id.value && !it.live })
            val dates = videos.mapNotNull { it.uploadedAtMs }
            assertTrue("Insufficient dated uploads", dates.size >= 20)
            // Relative dates are evaluated when each page is fetched; equal upload-age
            // buckets can differ by a few seconds. Check raw provider order, not a sorted copy.
            val skew = dates.zipWithNext().maxOf { (a, b) -> b - a }
            assertTrue("Provider did not return a Latest feed", skew <= 60_000)
            try { catalog.channel(ChannelId("UC38IQsAvIsxxjztdMZQtwHA"), first.cursor); fail("Other channel accepted cursor") }
            catch (e: AppException) { assertEquals(ErrorCode.UPSTREAM_CHANGED, e.code) }
            File("../docs/evidence/channel-provider-check.txt").writeText(
                "pages=2\ndatedUploads=${dates.size}\nrawProviderLatestOrder=true\nmaximumRelativeDateSkewMs=$skew\nchannelCursorBound=true\nshortsAndStreamsTabsRequested=false\n")
        } finally { catalog.cancelPending() }
    }

    @Test fun videoUploaderUsesCanonicalChannelId() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        try {
            assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", catalog.channelForVideo(VideoId("dQw4w9WgXcQ")).value)
            File("../docs/evidence/channel-uploader-check.txt").writeText("videoUploaderResolution=true\ncanonicalChannelIdValidated=true\n")
        } finally { catalog.cancelPending() }
    }
}
