package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class ChannelPagesTest {
    private fun video(n: Int, date: Long? = n.toLong(), live: Boolean = false) =
        VideoSummary(VideoId("%011d".format(n)), "Upload $n", "Fixture", null, 100, live = live, uploadedAtMs = date)

    @Test fun channelIdsRejectNamesUrlsAndPathInjection() {
        assertEquals("UC16niRr50-MSBwiO3YDb3RA", ChannelId("UC16niRr50-MSBwiO3YDb3RA").value)
        for (invalid in listOf("BBC News", "@BBC", "UC16niRr50-MSBwiO3YDb3RA/../", "https://www.youtube.com/channel/UC16niRr50-MSBwiO3YDb3RA")) {
            try { ChannelId(invalid); fail("Invalid channel accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun newestOrderingDuplicatesUnknownDatesAndLiveFiltering() {
        val pages = ChannelPages()
        pages.add(CatalogPage(listOf(video(1, null), video(2, 100), video(3, 200), video(2, 100), video(4, 300, true)), "first"))
        assertEquals(listOf(3, 2, 1), pages.current!!.videos.map { it.id.value.toInt() })
        pages.add(CatalogPage(listOf(video(2, 100), video(5, 50), video(6, 75)), "second"))
        assertEquals(listOf(6, 5), pages.current!!.videos.map { it.id.value.toInt() })
        assertEquals(2, pages.current!!.number)
        assertTrue(pages.newer()); assertEquals(1, pages.current!!.number)
        assertTrue(pages.older()); assertEquals(2, pages.current!!.number)
    }
    @Test fun boundedCacheStillAllowsOlderPagesAndStopsRepeatedCursor() {
        val pages = ChannelPages()
        for (n in 1..20) pages.add(CatalogPage(listOf(video(n)), "page$n"))
        assertEquals(20, pages.current!!.number)
        repeat(3) { assertTrue(pages.newer()) }
        assertEquals(17, pages.current!!.number); assertFalse(pages.newer())
        repeat(3) { assertTrue(pages.older()) }
        assertFalse(pages.older())
        pages.add(CatalogPage(emptyList(), "page20")); assertNull(pages.current!!.cursor)
        pages.clear(); assertNull(pages.current); assertFalse(pages.canGoNewer)
        pages.add(CatalogPage(listOf(video(1)), null)); assertEquals(1, pages.current!!.number)
    }
}
