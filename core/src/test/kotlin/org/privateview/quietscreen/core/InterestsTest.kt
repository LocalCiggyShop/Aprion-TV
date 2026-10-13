package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class InterestsTest {
    private fun video(index: Int, title: String = "Bass fishing river techniques", channel: String = "Creator $index") =
        VideoSummary(VideoId("%011d".format(index)), title, channel, null, 120)

    @Test fun unclassifiedAndUnicodeTitlesLearnWithoutRawSearchOrVideoHistory() {
        val opened = video(1)
        assertNull(GenreClassifier.classify(opened))
        val profile = InterestProfile().watched(opened, 60_000, 120_000)
        assertTrue(profile.topics.getValue("fishing") > 0)
        assertTrue(profile.channels.getValue("creator 1") > 0)
        assertTrue(profile.dismissed.isEmpty() && profile.shown.isEmpty())
        assertEquals(setOf("cuisine", "française", "日本料理"), InterestMetadata.topics("Cuisine française 日本料理"))
        assertEquals(2, profile.discoveryQuery()!!.split(' ').size)
    }

    @Test fun shortSamplingAndEarlyExitUseExistingEngagementThresholds() {
        val original = InterestProfile().feedback(video(1), RecommendationFeedback.MORE)
        assertSame(original, original.watched(video(1), 9_000, 9_000))
        val early = original.watched(video(1), 15_000, 600_000)
        assertTrue(early.channels.getValue("creator 1") < original.channels.getValue("creator 1"))
        assertTrue(InterestProfile().watched(video(1), 15_000, 18_000).personalized)
    }

    @Test fun explicitFeedbackBlocksStableChannelsAndMoreLikeThisCanUndo() {
        val opened = video(1).copy(channelId = "UC" + "a".repeat(22))
        val blocked = InterestProfile().feedback(opened, RecommendationFeedback.BLOCK_CHANNEL)
        assertFalse(blocked.permits(opened.copy(id = video(2).id, channel = "Renamed creator")))
        val restored = blocked.feedback(opened, RecommendationFeedback.MORE)
        assertTrue(restored.permits(opened))
        assertTrue(restored.channels.isNotEmpty())
        assertFalse(restored.feedback(opened, RecommendationFeedback.LESS).permits(opened))
    }

    @Test fun legacyNameOnlyMetadataSharesAffinityAndExclusionsWithKnownChannelIds() {
        val known = video(1).copy(channelId = "UC" + "a".repeat(22))
        val legacy = known.copy(id = video(2).id, channelId = null)
        val sameNameOtherId = known.copy(id = video(3).id, channelId = "UC" + "b".repeat(22))
        val knownProfile = InterestProfile().watched(known, 60_000, 120_000)
        assertTrue(knownProfile.channelAffinity(legacy) > 0)
        assertEquals(0.0, knownProfile.channelAffinity(sameNameOtherId), 0.0)
        assertTrue(InterestProfile().watched(legacy, 60_000, 120_000).channelAffinity(known) > 0)
        val blocked = knownProfile.feedback(known, RecommendationFeedback.BLOCK_CHANNEL)
        assertFalse(blocked.permits(legacy))
        assertFalse(blocked.permits(known.copy(id = video(5).id, channel = "Renamed")))
        assertTrue(blocked.permits(sameNameOtherId))
        assertFalse(InterestProfile().feedback(legacy, RecommendationFeedback.BLOCK_CHANNEL).permits(known))
        val restored = blocked.feedback(legacy, RecommendationFeedback.MORE)
        assertTrue(restored.permits(known.copy(id = video(6).id)))
        assertFalse(restored.permits(known)) // A different video's explicit exclusion remains.
        assertTrue(blocked.feedback(known, RecommendationFeedback.MORE).blockedNames.isEmpty())
    }

    @Test fun topicAndChannelAffinityAreDisabledWhileExclusionsRemain() {
        val preferred = video(2, "Blues guitar performance")
        val unrelated = video(1, "Bread baking recipe")
        val profile = InterestProfile().feedback(preferred, RecommendationFeedback.MORE)
        assertEquals(preferred, InterestRanking.rank(listOf(unrelated, preferred), profile, true).first())
        assertEquals(unrelated, InterestRanking.rank(listOf(unrelated, preferred), profile, false).first())
        assertEquals(listOf(unrelated), InterestRanking.rank(listOf(unrelated, preferred), profile.feedback(preferred, RecommendationFeedback.LESS), false))
    }

    @Test fun contextualRankingExcludesCurrentAndPrefersSameTopicOrCreator() {
        val current = video(1, "Blues guitar lesson", "Guitar teacher")
        val related = video(3, "Blues guitar chords", "Guitar teacher")
        val unrelated = video(2, "Bread baking recipe")
        assertEquals(listOf(related, unrelated), InterestRanking.rank(listOf(current, unrelated, related), InterestProfile(), false, current))
        val selected = RecommendedVideos.select(GenrePreferences(), false, emptyMap(), emptySet(), limit = 12,
            extras = listOf(current, unrelated, related, related), context = current)
        assertEquals(2, selected.size)
        assertEquals(related, selected.first().video)
        assertEquals(2, selected.map { it.video.id }.distinct().size)
    }

    @Test fun unseenVideosRotateAheadOfPreviouslyShownWithoutPermanentlyRemovingThem() {
        val candidates = (0..39).map { video(it) }
        val shown = InterestProfile().exposed(candidates.take(24))
        val ranked = InterestRanking.rank(candidates, shown, false)
        assertTrue(ranked.take(16).all { it.id.value !in shown.shown })
        assertEquals(40, ranked.size)
        assertNotEquals(InterestRanking.rank(candidates, InterestProfile(), false).first(),
            InterestRanking.rank(candidates, InterestProfile(), false, rotation = 1).first())
    }

    @Test fun allGenresHaveAnInitialDiscoveryTurnAndLearnedGenresSurviveRotation() {
        val cold = GenrePreferences()
        assertEquals(Genre.entries.toSet(), (0..7).flatMap { cold.recommendationSources(true, it) }.toSet())
        val trained = GenrePreferences(mapOf(Genre.GAMING to 70.0, Genre.ARTS to 20.0))
        for (round in 0..7) assertEquals(listOf(Genre.GAMING, Genre.ARTS), trained.recommendationSources(true, round).take(2))
    }

    @Test fun candidateAndPersistenceBoundsRejectCorruptionAndAvoidDuplicateOrCreatorFlooding() {
        val profile = InterestProfile((0..100).associate { "topic${it}" to 1000.0 } + mapOf("invalid space" to 5.0, "nan" to Double.NaN),
            (0..60).associate { "creator $it" to 1.0 }, (0..150).map { video(it).id.value } + "bad",
            (0..40).map { "creator $it" }, (0..150).map { video(it).id.value })
        assertEquals(64, profile.topics.size); assertEquals(32, profile.channels.size)
        assertEquals(80, profile.dismissed.size); assertEquals(24, profile.blocked.size); assertEquals(96, profile.shown.size)
        assertTrue(profile.topics.values.all { it == 100.0 })
        val candidates = (0..50).map { video(it, channel = if (it < 20) "Flooding creator" else "Creator $it") }
        val selected = RecommendedVideos.select(GenrePreferences(), false, mapOf(Genre.SCIENCE to candidates), emptySet())
        assertEquals(24, selected.size)
        assertEquals(3, selected.count { it.video.channel == "Flooding creator" })
        val duplicates = RecommendedVideos.select(GenrePreferences(), true, mapOf(Genre.MUSIC to candidates), emptySet(), extras = candidates)
        assertEquals(24, duplicates.map { it.video.id }.distinct().size)
    }

    @Test fun fullBoundedPoolRankingKeepsOutputSmall() {
        val candidates = Genre.entries.associateWith { genre -> (0..59).map {
            video(genre.ordinal * 100 + it, "Blues guitar lesson $it", "Creator $it") } }
        val profile = InterestProfile().feedback(video(900, "Blues guitar lesson"), RecommendationFeedback.MORE)
        val start = System.nanoTime()
        repeat(20) {
            val selected = RecommendedVideos.select(GenrePreferences(mapOf(Genre.MUSIC to 50.0)), true, candidates,
                emptySet(), interests = profile, extras = (900..929).map { video(it) }, rotation = it)
            assertEquals(24, selected.size); assertEquals(24, selected.map { it.video.id }.distinct().size)
        }
        println("Bounded 480+30 candidate ranking: average ${(System.nanoTime() - start) / 20 / 1_000_000.0} ms on JVM host; physical TV unmeasured")
    }
}
