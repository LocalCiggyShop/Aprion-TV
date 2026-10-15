package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class ChapterSeekTest {
    private val chapters = listOf(VideoChapter("Intro", 0), VideoChapter("First", 20_000), VideoChapter("Second", 70_000))

    @Test fun chaptersAreBoundedOrderedUniqueAndSafeToDisplay() {
        val normalized = normalizeVideoChapters(listOf(VideoChapter("Outside", 90_000), VideoChapter("Bad", -1),
            VideoChapter("First\npart", 20_000), VideoChapter("Duplicate", 20_000), VideoChapter("", 0),
            VideoChapter("x".repeat(500), 70_000)), 90_000)
        assertEquals(listOf(0L, 20_000L, 70_000L), normalized.map { it.startMs })
        assertEquals("Chapter 1", normalized[0].title)
        assertEquals("First part", normalized[1].title)
        assertEquals(200, normalized[2].title.length)
        assertTrue(normalizeVideoChapters(chapters, 0).isEmpty())
        assertEquals(256, normalizeVideoChapters(List(300) { VideoChapter("Chapter", it.toLong()) }, 1_000).size)
    }

    @Test fun chapterStepsStayTransactionalWithoutAccelerationOrWrapping() {
        val original = SeekSession(15_000, 35_000, 90_000, true)
        val selected = original.selectChapters(chapters)
        assertTrue(selected.chapterMode)
        assertEquals(20_000L, selected.pendingMs)
        val next = selected.move(true, 50, chapters)
        assertEquals(70_000L, next.pendingMs)
        assertEquals(next, next.move(true, 50, chapters))
        val first = next.move(false, 0, chapters).move(false, 0, chapters)
        assertEquals(0L, first.pendingMs)
        assertEquals(first, first.move(false, 50, chapters))
        assertEquals(15_000L, next.originalMs)
        assertTrue(next.resume)
        assertEquals(35_000L, original.pendingMs)
        assertFalse(original.copy(resume = false).selectChapters(chapters).move(true, 0, chapters).resume)
    }

    @Test fun emptyChaptersAndReturningToTimeSeekingPreserveNormalControls() {
        val original = SeekSession(15_000, 35_000, 90_000, false)
        assertEquals(original, original.selectChapters(emptyList()))
        assertEquals(45_000L, original.move(true).pendingMs)
        val timeMode = original.selectChapters(chapters).copy(chapterMode = false)
        assertEquals(30_000L, timeMode.move(true).pendingMs)
        assertEquals(80_000L, timeMode.move(true, 50).pendingMs)
        assertEquals(20_000L, original.copy(pendingMs = 0).selectChapters(chapters.drop(1)).pendingMs)
    }
}
