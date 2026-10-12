package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class RecommendationsTest {
    @Test fun repeatedEnjoymentOutweighsOneLongWatchAndEarlyExitsReduceOnlyThatGenre() {
        val long = GenrePreferences().watched(Genre.SCIENCE, 600_000, 600_000)
        var repeated = GenrePreferences()
        repeat(3) { repeated = repeated.watched(Genre.SCIENCE, 120_000, 120_000) }
        assertTrue(repeated.scores.getValue(Genre.SCIENCE) > long.scores.getValue(Genre.SCIENCE))
        val initial = GenrePreferences(mapOf(Genre.SCIENCE to 20.0, Genre.MUSIC to 18.0))
        val skipped = initial.watched(Genre.SCIENCE, 15_000, 600_000)
        assertTrue(skipped.scores.getValue(Genre.SCIENCE) < initial.scores.getValue(Genre.SCIENCE))
        assertEquals(18.0, skipped.scores.getValue(Genre.MUSIC), 0.0)
        assertEquals(Genre.MUSIC, skipped.order().first())
    }
    @Test fun diverseStartAndBoundedLearningPreserveAllGenres() {
        val initial = GenrePreferences()
        assertEquals(Genre.entries, initial.order())
        assertFalse(initial.personalized)
        val partial = initial.watched(Genre.GAMING, 120_000, 600_000)
        val complete = initial.watched(Genre.GAMING, 120_000, 120_000)
        assertTrue(complete.scores.getValue(Genre.GAMING) > partial.scores.getValue(Genre.GAMING))
        assertEquals(Genre.GAMING, complete.order().first())
        var repeated = complete
        repeat(10_000) { repeated = repeated.watched(Genre.GAMING, Long.MAX_VALUE, 120_000) }
        assertEquals(8, repeated.scores.size)
        assertEquals(Genre.entries.toSet(), repeated.order().toSet())
        assertTrue(repeated.scores.values.all { it.isFinite() && it in 0.0..100.0 })
    }
    @Test fun corruptScoresAndInsignificantWatchesDoNotPoisonPreferences() {
        val profile = GenrePreferences(mapOf(Genre.MUSIC to Double.NaN, Genre.SCIENCE to Double.POSITIVE_INFINITY,
            Genre.TRAVEL to -9.0, Genre.ARTS to 1_000.0))
        assertEquals(0.0, profile.scores.getValue(Genre.MUSIC), 0.0)
        assertEquals(100.0, profile.scores.getValue(Genre.ARTS), 0.0)
        assertSame(profile, profile.watched(Genre.MUSIC, 9_000, 9_000))
        assertTrue(GenrePreferences().watched(Genre.MUSIC, 15_000, 18_000).personalized)
        assertFalse(GenrePreferences().watched(Genre.MUSIC, -1, 0).personalized)
    }
    @Test fun seeksPausesStallsAndBackgroundGapsEarnNoWatchCredit() {
        val watch = WatchTime()
        watch.sample(0, 0, true)
        watch.sample(500, 500, true)
        watch.sample(1000, 60_000, true) // seek to near the end
        watch.sample(1500, 60_500, false)
        watch.sample(2000, 60_500, false)
        watch.sample(2500, 60_500, true)
        watch.sample(3000, 60_500, true) // stalled
        watch.sample(10_000, 67_500, true) // suspended observer
        watch.sample(10_500, 68_000, false)
        assertEquals(1500, watch.watchedMs)
    }
    @Test fun smallSeekAndStateChangesDoNotCountAsCompletion() {
        val watch = WatchTime()
        watch.sample(0, 0, true)
        watch.sample(500, 500, true)
        watch.discontinuity()
        watch.sample(600, 700, true) // even a small seek resets the observation interval
        watch.sample(1100, 1200, false)
        watch.sample(5000, 1200, true) // paused interval
        watch.sample(5500, 1700, false)
        assertEquals(1500, watch.watchedMs)
    }
}
