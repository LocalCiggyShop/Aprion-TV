package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class RecommendationsV3Test {
    private fun video(id: Int, title: String = "River fishing techniques", channel: String = "Creator $id") =
        VideoSummary(VideoId("%011d".format(id)), title, channel, null, 120)

    @Test fun interruptionsAndEndEventsDoNotManufactureDislikeOrCompletion() {
        val trained = InterestProfile().feedback(video(1), RecommendationFeedback.MORE)
        assertSame(trained, trained.watched(video(1), WatchOutcome(15_000, 600_000, interrupted = true, failed = true)))
        assertEquals(0.0, WatchOutcome(9_000, 600_000, ended = true, sought = true).gain, 0.0)
        assertEquals(0.0, WatchOutcome(15_000, 18_000, sought = true).gain, 0.0)
        assertTrue(WatchOutcome(60_000, 120_000, interrupted = true).gain > 0)
        assertTrue(WatchOutcome(60_000, 120_000, sought = true).gain < WatchOutcome(60_000, 120_000).gain)
        assertEquals(WatchOutcome(60_000, 120_000).gain * .5, WatchOutcome(60_000, 120_000, autoplay = true).gain, 0.0)
    }

    @Test fun repeatCreditSaturatesAndResetRestoresIt() {
        val credits = SessionCredits()
        var total = 0.0
        repeat(50) { total += 3 * credits.weight(video(1).id, 3.0) }
        assertEquals(6.0, total, 0.0)
        assertEquals(1.0, credits.weight(video(2).id, 3.0), 0.0)
        credits.clear()
        assertEquals(1.0, credits.weight(video(1).id, 3.0), 0.0)
    }

    @Test fun impressionRequiresContinuousVisibleDwellAndRecordsOnlyOnce() {
        val impressions = RecommendationImpressions()
        val a = video(1).id.value; val b = video(2).id.value
        assertTrue(impressions.update(setOf(a), 0).isEmpty())
        assertTrue(impressions.update(emptySet(), 700).isEmpty())
        assertTrue(impressions.update(setOf(a, b), 800).isEmpty())
        assertEquals(setOf(a, b), impressions.update(setOf(a, b), 1550))
        assertTrue(impressions.update(setOf(a, b), 3000).isEmpty())
        assertFalse(impressions.selected(a))
        impressions.clear()
        assertTrue(impressions.selected(a))
        assertTrue(impressions.update(setOf(a), 0).isEmpty())
        assertTrue(impressions.update(setOf(a), 1000).isEmpty())
    }

    @Test fun legacyTopicsNeverCreateAnUnobservedCombinedQuery() {
        val legacy = InterestProfile(topics = mapOf("fishing" to 80.0, "astronomy" to 79.0))
        assertEquals("fishing", legacy.discoveryQuery())
        assertEquals("astronomy", legacy.discoveryQuery(1))
        val trained = legacy.watched(video(1, "River fishing techniques"), 60_000, 120_000)
        val query = trained.discoveryQuery()!!
        assertFalse(query.contains("astronomy") && query.contains("fishing"))
        assertTrue(query.split(' ').size <= 2)
        assertTrue(trained.pairs.keys.all { pair -> pair.split(' ').all { it in setOf("river", "fishing", "techniques") } })
    }

    @Test fun pairsAreBoundedAndCorruptionCannotBecomeAQuery() {
        val bad = InterestProfile(topics = mapOf("river" to 4.0, "fishing" to 3.0),
            pairs = mapOf("river fishing extra" to 100.0, "fishing river" to Double.NaN, "https://bad" to 90.0))
        assertEquals("river", bad.discoveryQuery())
        var learned = InterestProfile()
        repeat(100) { learned = learned.watched(video(it, "subject$it topic$it example$it"), 60_000, 120_000) }
        assertTrue(learned.pairs.size <= 16)
        assertTrue(learned.topics.size <= 64)
    }

    @Test fun nicheRelevanceBeatsUnrelatedBucketCandidatesInFirstEight() {
        val target = video(100, "River fishing tackle")
        val profile = InterestProfile().feedback(target, RecommendationFeedback.MORE)
        val unrelated = (0..29).map { video(it, "Galaxy telescope astronomy") }
        val relevant = (100..109).map { video(it, "River fishing tackle") }
        val selected = RecommendedVideos.select(GenrePreferences(), true, mapOf(Genre.SCIENCE to unrelated),
            emptySet(), interests = profile, extras = relevant)
        assertEquals(8, selected.take(8).count { it.video in relevant })
        assertEquals(24, selected.map { it.video.id }.distinct().size)
    }

    @Test fun unknownDatesAndUnknownCreatorsRemainEligibleAndFiltersNeverRelax() {
        val candidates = (0..29).map { video(it, channel = "") }
        val blocked = InterestProfile().feedback(candidates[0], RecommendationFeedback.LESS)
        val selected = RecommendedVideos.select(GenrePreferences(), true, mapOf(Genre.NATURE to candidates),
            setOf(candidates[1].id.value), interests = blocked)
        assertEquals(24, selected.size)
        assertTrue(selected.none { it.video.id == candidates[0].id || it.video.id == candidates[1].id })
        assertEquals(24, selected.map { it.video.id }.distinct().size)
    }

    @Test fun firstEightHaveCreatorVarietyAndSparsePoolsStillFill() {
        val candidates = (0..39).map { video(it, channel = if (it < 20) "Flood" else "Creator $it") }
        val selected = RecommendedVideos.select(GenrePreferences(), false, mapOf(Genre.NATURE to candidates), emptySet())
        assertTrue(selected.take(8).count { it.video.channel == "Flood" } <= 2)
        assertTrue(selected.zipWithNext().take(8).none { (a, b) -> a.video.channel == b.video.channel })
        val sparse = candidates.take(4)
        assertEquals(4, RecommendedVideos.select(GenrePreferences(), false, emptyMap(), emptySet(), extras = sparse).size)
    }

    @Test fun cooperativeCancellationIsCheckedDuringPreparationAndSelection() {
        class Stop : RuntimeException()
        var calls = 0
        try {
            RecommendationPool(mapOf(Genre.NATURE to (0..59).map { video(it) }), check = { if (++calls == 10) throw Stop() })
            fail("Cancellation was not observed")
        } catch (_: Stop) { assertEquals(10, calls) }
    }

    @Test fun equallyEstablishedInterestsBothAppearNearTheStart() {
        val catalogs = mapOf(
            Genre.MUSIC to (0..29).map { video(it, "Blues guitar concert") },
            Genre.GAMING to (100..129).map { video(it, "Minecraft gameplay") })
        val result = RecommendedVideos.select(GenrePreferences(mapOf(Genre.MUSIC to 80.0, Genre.GAMING to 80.0)),
            true, catalogs, emptySet()).take(8)
        assertTrue(result.count { it.genre == Genre.MUSIC } >= 3)
        assertTrue(result.count { it.genre == Genre.GAMING } >= 3)
    }

    @Test fun sharedBoundedPoolProducesDeterministicResultsAndReportsHostPercentiles() {
        val catalogs = Genre.entries.associateWith { genre -> (0..59).map { video(genre.ordinal * 100 + it) } }
        val pool = RecommendationPool(catalogs, (900..929).map { video(it) }, (1000..1031).map { video(it) })
        assertEquals(542, pool.items.size)
        val profile = InterestProfile().feedback(video(900), RecommendationFeedback.MORE)
        fun select() = RecommendedVideos.select(GenrePreferences(), true, pool, emptySet(), interests = profile, nowMs = 1_000_000)
        val expected = select()
        repeat(5) { assertEquals(expected, select()) }
        val times = (0..29).map { val start = System.nanoTime(); assertEquals(expected, select()); (System.nanoTime() - start) / 1_000_000.0 }.sorted()
        println("V3 shared 542-card host ranking: p50=${times[15]}ms p95=${times[28]}ms n=30; physical TV unmeasured")
    }
}
