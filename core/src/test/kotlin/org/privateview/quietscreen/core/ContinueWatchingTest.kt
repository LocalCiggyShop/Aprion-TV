package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingTest {
    private fun video(i: Int) = VideoSummary(VideoId("%011d".format(i)), "Video $i", "Channel", null, 120)
    @Test fun resumesDeduplicateBoundAndExcludeFinishedReplays() {
        var state = ContinueWatchingState()
        for (i in 0..30) state = state.record(video(i), 10_000, 120_000, false, Genre.SCIENCE)
        assertEquals(24, state.videos.size)
        state = state.record(video(30), 60_000, 120_000, false, Genre.MUSIC)
        assertEquals(24, state.videos.size)
        assertEquals(60_000, state.videos.first().positionMs)
        state = state.record(video(30), 120_000, 120_000, true, Genre.MUSIC)
        assertFalse(state.videos.any { it.video.id == video(30).id })
        assertEquals(state, state.record(video(30), 50_000, 120_000, false, Genre.MUSIC))
        for (i in 0..200) state = state.record(video(i), 120_000, 120_000, true, null)
        assertEquals(100, state.finished.size)
        assertTrue(state.videos.isEmpty())
    }
    @Test fun briefWatchesUseSummaryDurationWithoutAssumingEarlyCompletion() {
        val state = ContinueWatchingState()
        assertEquals(state, state.record(video(0), 999, 120_000, false, null))
        assertEquals(1_000, state.record(video(0), 1_000, 120_000, false, null).videos.single().positionMs)
        assertEquals(state, state.record(video(0).copy(live = true), 10_000, 120_000, false, null))
        assertEquals(120_000, state.record(video(0), 10_000, 0, false, null).videos.single().durationMs)
        assertEquals(state, state.record(video(0).copy(durationSeconds = null), 10_000, 0, false, null))
        assertEquals(state, state.record(video(0).copy(durationSeconds = Long.MAX_VALUE), 10_000, 0, false, null))
        assertEquals(state, state.record(video(0), 10_000, Long.MAX_VALUE, false, null))
        assertEquals(1, state.record(video(0), 119_000, 120_000, false, null).videos.size)
    }
    @Test fun actualEndRemovesUnknownDurationRecordAndPreventsReplay() {
        val item = video(1)
        val partial = ContinueWatchingState().record(item, 100_000, 120_000, false, null)
        assertEquals(1, partial.videos.size)
        val ended = partial.record(item.copy(durationSeconds = null), 0, 0, true, null)
        assertTrue(ended.videos.isEmpty())
        assertEquals(listOf(item.id.value), ended.finished)
        assertEquals(ended, ended.record(item, 2_000, 120_000, false, null))
    }
}
