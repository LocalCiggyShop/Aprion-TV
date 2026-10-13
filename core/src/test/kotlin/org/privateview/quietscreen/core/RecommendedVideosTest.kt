package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class RecommendedVideosTest {
    private fun video(number: Int, title: String = "Video", channel: String = "Channel") =
        VideoSummary(VideoId("%011d".format(number)), title, channel, null, 120)
    private fun candidates(count: Int = 60) = Genre.entries.associateWith { genre ->
        (0 until count).map { video(genre.ordinal * 100 + it) }
    }

    @Test fun coldPausedAndResetProfilesRetainDiverseDeterministicSuggestions() {
        val catalog = candidates()
        val cold = RecommendedVideos.select(GenrePreferences(), true, catalog, emptySet())
        assertEquals(24, cold.size)
        assertEquals(Genre.entries, cold.take(8).map { it.genre })
        assertEquals(Genre.entries.toSet(), cold.map { it.genre }.toSet())
        val trained = GenrePreferences(mapOf(Genre.GAMING to 100.0))
        assertEquals(cold, RecommendedVideos.select(trained, false, catalog, emptySet()))
        assertEquals(cold, RecommendedVideos.select(GenrePreferences(), true, catalog, emptySet()))
        assertEquals(Genre.entries.take(4), trained.recommendationSources(false))
    }

    @Test fun learnedInterestsDominateWhileExplorationSurvivesAndSourcesAreBounded() {
        val profile = GenrePreferences(mapOf(Genre.GAMING to 100.0, Genre.MUSIC to 20.0))
        val selected = RecommendedVideos.select(profile, true, candidates(), emptySet())
        assertEquals(18, selected.count { it.genre == Genre.GAMING || it.genre == Genre.MUSIC })
        assertTrue(selected.count { it.genre == Genre.GAMING } > selected.count { it.genre == Genre.MUSIC })
        assertEquals(6, selected.count { it.genre != Genre.GAMING && it.genre != Genre.MUSIC })
        assertTrue(selected.filterIndexed { index, _ -> index % 4 == 3 }.all {
            it.genre != Genre.GAMING && it.genre != Genre.MUSIC
        })
        val sources = profile.recommendationSources(true)
        assertEquals(4, sources.size)
        assertEquals(listOf(Genre.GAMING, Genre.MUSIC), sources.take(2))
        assertEquals(4, sources.distinct().size)
        assertEquals(Genre.entries.toSet(), profile.order().toSet())
    }

    @Test fun unavailablePreferredGenreFallsBackAndDuplicateExcludedLiveAndOversizeCandidatesAreBounded() {
        val shared = video(999)
        val excluded = video(998)
        val science = listOf(shared, excluded, video(997).copy(live = true)) +
            (0 until 80).map { video(it) }
        val catalog = mapOf(Genre.SCIENCE to science, Genre.MUSIC to listOf(shared, video(996)))
        val profile = GenrePreferences(mapOf(Genre.GAMING to 100.0))
        val selected = RecommendedVideos.select(profile, true, catalog, setOf(excluded.id.value), Int.MAX_VALUE)
        assertEquals(24, selected.size)
        assertEquals(selected.size, selected.map { it.video.id }.distinct().size)
        assertFalse(selected.any { it.video.id == excluded.id || it.video.live })
        assertTrue(selected.all { it.video.id.value.toInt() < 57 || it.video.id == shared.id || it.video.id.value == "%011d".format(996) })
        assertTrue(RecommendedVideos.select(profile, true, catalog, emptySet(), -1).isEmpty())
        val sparse = RecommendedVideos.select(profile, true, mapOf(Genre.MUSIC to listOf(shared)), emptySet())
        assertEquals(listOf(RecommendedVideo(shared, Genre.MUSIC)), sparse)
        val onlyBeyondCap = List(60) { shared } + video(995)
        assertEquals(listOf(shared), RecommendedVideos.select(profile, true,
            mapOf(Genre.MUSIC to onlyBeyondCap), emptySet()).map { it.video })
    }

    @Test fun localMetadataClassificationRequiresClearBoundedTokenEvidence() {
        assertEquals(Genre.MUSIC, GenreClassifier.providerCategory("Music"))
        assertEquals(Genre.GAMING, GenreClassifier.providerCategory("Gaming"))
        assertEquals(Genre.SCIENCE, GenreClassifier.providerCategory("Science & Technology"))
        assertEquals(Genre.SPORT, GenreClassifier.providerCategory("Sports"))
        assertEquals(Genre.TRAVEL, GenreClassifier.providerCategory("Travel & Events"))
        assertNull(GenreClassifier.providerCategory("Education"))
        assertNull(GenreClassifier.providerCategory("Entertainment"))
        assertNull(GenreClassifier.providerCategory("x".repeat(65) + "Music"))
        assertEquals(Genre.GAMING, GenreClassifier.classify(video(0, "MINECRAFT gameplay highlights")))
        assertEquals(Genre.SCIENCE, GenreClassifier.classify(video(0, "A surprising experiment", "Veritasium")))
        assertEquals(Genre.TRAVEL, GenreClassifier.classify(video(0, "Tokyo walking tour")))
        assertEquals(Genre.COOKING, GenreClassifier.classify(video(0, "Bread baking recipe")))
        assertEquals(Genre.MUSIC, GenreClassifier.classify(video(0, "Piano cover")))
        assertEquals(Genre.NATURE, GenreClassifier.classify(video(0, "Wildlife documentary")))
        assertEquals(Genre.ARTS, GenreClassifier.classify(video(0, "Origami tutorial")))
        assertEquals(Genre.SPORT, GenreClassifier.classify(video(0, "Football highlights")))
        assertNull(GenreClassifier.classify(video(0, "My favourite things")))
        assertNull(GenreClassifier.classify(video(0, "Football science")))
        assertNull(GenreClassifier.classify(video(0, "Musical space game trailer")))
        assertNull(GenreClassifier.classify(video(0, "x".repeat(512) + " science", "x".repeat(160) + " veritasium")))
    }
}
