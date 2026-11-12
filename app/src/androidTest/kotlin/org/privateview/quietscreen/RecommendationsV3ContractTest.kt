package org.privateview.quietscreen

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.*

class RecommendationsV3ContractTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)
    private fun video(id: Int, title: String = "River fishing tackle") =
        VideoSummary(VideoId("%011d".format(id)), title, "Creator $id", null, 120)
    private fun await(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!check() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue("Bounded recommendation assertion timed out", check())
    }

    @Test fun changedInterestsFetchMissingTopicOnceAndFailedSourceRequiresExplicitRetry() {
        val requests = mutableListOf<String>()
        var active = 0; var maximum = 0; var failAstronomy = true
        val catalog = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?): CatalogPage {
                requests += query; active++; maximum = maxOf(maximum, active)
                try {
                    delay(20)
                    if (query == "astronomy" && failAstronomy) throw IllegalStateException("Fixture failure")
                    return CatalogPage((0..39).map { video(it) }, null)
                } finally { active-- }
            }
            override fun cancelPending() {}
        }
        lateinit var model: BrowseViewModel
        val fishing = InterestProfile(topics = mapOf("fishing" to 40.0))
        val astronomy = InterestProfile(topics = mapOf("astronomy" to 40.0))
        main { model = BrowseViewModel(catalog); model.arrange(GenrePreferences(), true, interests = fishing); model.enterHome() }
        try {
            await { model.rows.value.first().loaded && !model.rows.value.first().busy }
            assertEquals(4, requests.size); assertEquals(1, maximum)
            assertEquals(1, requests.count { it == "fishing" })
            main { model.arrange(GenrePreferences(), true, interests = astronomy) }
            await { model.rows.value.first().loaded && !model.rows.value.first().busy && requests.contains("astronomy") }
            assertEquals(5, requests.size) // Three compatible genre caches were reused.
            assertFalse(model.rows.value.first().videos.isEmpty())
            main { model.loadRow("recommended") }
            Thread.sleep(150)
            assertEquals(1, requests.count { it == "astronomy" })
            failAstronomy = false
            main { model.loadRow("recommended", retry = true) }
            await { !model.rows.value.first().busy && requests.count { it == "astronomy" } == 2 }
            assertEquals(1, maximum)
        } finally { main { model.leaveHome(); model.clear() } }
    }

    @Test fun impressionsAndStableVisitOrderingRespectVisibilityPauseAndExclusions() {
        val exposed = mutableListOf<String>(); var ranks = 0
        val catalog = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?) = CatalogPage((0..39).map { video(it) }, null)
            override fun cancelPending() {}
        }
        lateinit var model: BrowseViewModel
        val profile = InterestProfile(topics = mapOf("fishing" to 20.0))
        main { model = BrowseViewModel(catalog, { exposed.addAll(it.map { v -> v.id.value }) }, { ranks++ })
            model.arrange(GenrePreferences(), true, interests = profile); model.enterHome() }
        try {
            await { model.rows.value.first().loaded && !model.rows.value.first().busy }
            val before = model.rows.value.first().videos
            val visible = setOf(before[0].id.value)
            main { model.exposeVisible(visible, 0); model.exposeVisible(visible, 749) }
            assertTrue(exposed.isEmpty())
            main { model.exposeVisible(visible, 750); model.exposeVisible(visible, 2000) }
            assertEquals(visible.toList(), exposed)
            val count = ranks
            main { model.loadRow("recommended") }
            Thread.sleep(150)
            assertEquals(count, ranks)
            main {
                model.contentFocused = true
                model.focus = BrowseFocus(null, before[2].id.value, column = 2, rowKey = "recommended")
                model.arrange(GenrePreferences(mapOf(Genre.MUSIC to 80.0)), true, interests = profile)
            }
            main { } // Enqueue after arrange; awaitRanking joins the actual worker.
            runBlocking { model.awaitRanking() }
            assertEquals(before.map { it.id }, model.rows.value.first().videos.map { it.id })
            val blocked = profile.feedback(before[0], RecommendationFeedback.LESS)
            main { model.arrange(GenrePreferences(), false, interests = blocked); model.exposeVisible(setOf(before[1].id.value), 3000); model.exposeVisible(setOf(before[1].id.value), 4000) }
            await { model.rows.value.first().videos.none { it.id == before[0].id } }
            assertEquals(1, exposed.size)
        } finally { main { model.leaveHome(); model.clear() } }
    }

    @Test fun pendingPersistenceCannotResurrectResetAndLegacyProfilesKeepExclusions() {
        val context = instrument.targetContext
        val details = context.getSharedPreferences("recommendation_interests", 0)
        val genres = context.getSharedPreferences("genre_preferences", 0)
        val oldDetail = details.getString("profile", null); val oldGenres = genres.all
        var store: Personalization? = null
        try {
            details.edit().putString("profile", """{"topics":{"fishing":30},"dismissed":["00000000001"],"pairs":{"bad pair extra":100}}""").commit()
            val activeStore = Personalization(context)
            store = activeStore
            assertEquals("fishing", activeStore.interests().discoveryQuery())
            assertFalse(activeStore.interests().permits(video(1)))
            main {
                activeStore.enabled = true
                repeat(100) { activeStore.feedback(video(it), RecommendationFeedback.MORE) }
                activeStore.reset()
            }
            runBlocking { activeStore.flush().join() }
            assertFalse(Personalization(context).interests().personalized)
            assertTrue(Personalization(context).interests().dismissed.isEmpty())
            main { activeStore.record(video(3), null, WatchOutcome(60_000, 120_000)) }
            runBlocking { activeStore.flush().join() }
            val saved = Personalization(context).interests()
            assertTrue(saved.topics.isNotEmpty()); assertTrue(saved.pairs.isNotEmpty())
            assertTrue(details.getString("profile", "")!!.length <= 32_768)
        } finally {
            store?.let { runBlocking { it.close().join() } }
            details.edit().clear().apply { if (oldDetail != null) putString("profile", oldDetail) }.commit()
            genres.edit().clear().apply { oldGenres.forEach { (key, value) -> when (value) {
                is Float -> putFloat(key, value); is Boolean -> putBoolean(key, value)
            } } }.commit()
        }
    }
}
