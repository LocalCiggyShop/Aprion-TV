package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class PlayerFeaturesTest {
    private val a = VideoId("00000000001")
    private val b = VideoId("00000000002")
    @Test fun countsKeepUnknownZeroSingularAndLargeValuesDistinct() {
        assertEquals("Views unavailable", viewCountLabel(null))
        assertEquals("Views unavailable", viewCountLabel(-1))
        assertEquals("0 views", viewCountLabel(0))
        assertEquals("1 view", viewCountLabel(1))
        assertEquals("1.2K views", viewCountLabel(1234))
        assertEquals("999.9K views", viewCountLabel(999999))
        assertEquals("1M views", viewCountLabel(1_000_000))
        assertEquals("1,234,567 views", viewCountLabel(1234567, false))
        assertTrue(viewCountLabel(Long.MAX_VALUE).endsWith("T views"))
    }
    @Test fun seekIsTransactionalClampedAndDoesNotLoseOriginalOrPlayState() {
        val initial = SeekSession(15_000, 15_000, 35_000, true)
        val target = initial.move(true).move(true).move(true)
        assertEquals(35_000L, target.pendingMs)
        assertEquals(15_000L, target.originalMs); assertTrue(target.resume)
        assertEquals(0L, target.move(false, 50).pendingMs)
        assertEquals(15_000L, initial.pendingMs)
        assertFalse(initial.copy(resume = false).move(true).resume)
        assertEquals(Long.MAX_VALUE, SeekSession(0, Long.MAX_VALUE - 1, Long.MAX_VALUE, false).move(true).pendingMs)
    }
    @Test fun nonSquareStoryboardBoundsAndLastFrameAreSafe() {
        val story = PreviewStoryboard(List(2) { MediaEndpoint("https://i.ytimg.com/fixture$it") }, 160, 90, 3, 2, 10, 1000)
        assertEquals(PreviewFrame(0, 320, 90, 160, 90), story.frame(5000))
        assertEquals(PreviewFrame(1, 0, 0, 160, 90), story.frame(6000))
        assertEquals(PreviewFrame(1, 0, 90, 160, 90), story.frame(Long.MAX_VALUE))
        assertEquals(story.frame(0), story.frame(-1))
        assertTrue(story.frame(5000).fits(480, 180))
        assertFalse(story.frame(5000).fits(479, 180))
        assertFalse(story.frame(5000).fits(480, 179))
        assertFalse(story.sheets.first().toString().contains("fixture"))
    }
    @Test fun invalidPreviewDescriptorsAreRejected() {
        for (invalid in listOf<() -> Unit>(
            { PreviewStoryboard(emptyList(), 160, 90, 3, 2, 10, 1000) },
            { PreviewStoryboard(listOf(MediaEndpoint("redacted")), 160, 90, 0, 2, 1, 1000) },
            { PreviewStoryboard(listOf(MediaEndpoint("redacted")), 160, 90, 3, 2, 10, 1000) },
            { PreviewStoryboard(listOf(MediaEndpoint("redacted")), 160, 90, 3, 2, 1, 0) }
        )) { try { invalid(); fail("Invalid descriptor accepted") } catch (_: IllegalArgumentException) { } }
    }
    @Test fun autoplayUsesFifteenSecondsAndPinsCandidateAcrossRankingChanges() {
        val timer = AutoplayCountdown()
        assertEquals(15, timer.update(true, true, listOf(a, b), 1000))
        assertEquals(14, timer.update(true, true, listOf(b, a), 2000)); assertEquals(a, timer.next)
        assertEquals(1, timer.update(true, true, listOf(a), 15999))
        assertEquals(0, timer.update(true, true, listOf(a), 16000))
    }
    @Test fun cancellationOffMissingCandidateAndReplayCannotRaceAdvance() {
        val timer = AutoplayCountdown()
        assertNull(timer.update(true, false, listOf(a), 0))
        timer.update(false, true, emptyList(), 0)
        assertNull(timer.update(true, true, emptyList(), 0))
        assertEquals(15, timer.update(true, true, listOf(a), 1000))
        timer.cancel(); assertNull(timer.update(true, true, listOf(a), 30000))
        assertNull(timer.update(false, true, listOf(a), 30000))
        assertEquals(15, timer.update(true, true, listOf(a), 40000))
        assertNull(timer.update(true, true, listOf(b), 41000))
        assertNull(timer.update(true, true, listOf(b), 60000))
        timer.reset(); timer.update(true, true, listOf(a), 0)
        assertNull(timer.update(true, false, listOf(a), 1000))
        assertNull(timer.update(true, true, listOf(a), 20000))
    }
}
