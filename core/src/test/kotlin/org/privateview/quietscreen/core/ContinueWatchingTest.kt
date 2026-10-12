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
    @Test fun rejectsUnstartedLiveInvalidAndUnknownDurationWithoutAssumingEarlyCompletion() {
        val state = ContinueWatchingState()
        assertEquals(state, state.record(video(0), 4_999, 120_000, false, null))
        assertEquals(state, state.record(video(0).copy(live = true), 10_000, 120_000, false, null))
        assertEquals(state, state.record(video(0), 10_000, 0, false, null))
        assertEquals(state, state.record(video(0), 10_000, Long.MAX_VALUE, false, null))
        assertEquals(1, state.record(video(0), 119_000, 120_000, false, null).videos.size)
    }
}
