package org.privateview.quietscreen

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.*
import org.privateview.quietscreen.ui.*

@RunWith(AndroidJUnit4::class)
class BrowseContractTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun video(index: Int) = VideoSummary(VideoId("%011d".format(index)), "Fixture video $index", "Local fixture", null, 120)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(50) }
        assertTrue("Condition timed out; remote focus: ${focused().take(600)}", condition())
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = instrument.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { if (result.size >= 2000) return; result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == instrument.targetContext.packageName }.forEach(::visit)
        return result
    }
    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text, node.contentDescription).joinToString(" ") +
        (0 until node.childCount).mapNotNull { node.getChild(it) }.joinToString(" ") { label(it) }
    private fun focused() = nodes().filter { it.isFocused }.joinToString(" ") { label(it) }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(200) }

    @Test fun recommendedUsesPrivateSearchLearningImmediatelyReusesBoundedCatalogAndResets() {
        var requests = 0
        var active = 0
        var maximumActive = 0
        val catalog = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?): CatalogPage {
                requests++; active++; maximumActive = maxOf(maximumActive, active)
                try {
                    delay(20)
                    val genre = Genre.entries.first { it.query == query }
                    return CatalogPage((0..39).map { video(genre.ordinal * 100 + it) }, null)
                } finally { active-- }
            }
            override fun cancelPending() {}
        }
        lateinit var model: BrowseViewModel
        onMain { model = BrowseViewModel(catalog); model.loadRow("recommended") }
        try {
            await { model.rows.value.first().loaded && !model.rows.value.first().busy }
            assertEquals("Recommended", model.rows.value.first().label)
            assertEquals(4, requests)
            assertEquals(1, maximumActive)
            assertEquals(4, model.rows.value.first().origins.values.toSet().size)
            onMain { Genre.entries.forEach { model.load(it) } }
            await { model.rows.value.filter { it.genre != null }.all { it.loaded && !it.busy } }
            assertEquals(8, requests)
            val profile = GenrePreferences().watched(requireNotNull(GenreClassifier.classify(
                video(900).copy(title = "Minecraft gameplay"))), 120_000, 120_000)
            onMain { model.arrange(profile, true); model.loadRow("recommended") }
            val row = model.rows.value.first()
            assertTrue(row.learned)
            assertEquals(18, row.videos.count { row.origins[it.id.value] == Genre.GAMING })
            assertEquals(8, requests)
            val pinned = row.videos[2]
            onMain {
                model.focus = BrowseFocus(null, pinned.id.value, column = 2, rowKey = "recommended")
                model.arrange(GenrePreferences(mapOf(Genre.MUSIC to 100.0)), true)
            }
            assertEquals(pinned.id, model.rows.value.first().videos[2].id)
            onMain { model.focus = null; model.arrange(profile, false) }
            assertFalse(model.rows.value.first().learned)
            assertEquals(Genre.entries.toSet(), model.rows.value.first().origins.values.toSet())
            val store = ContinueWatching(instrument.targetContext)
            val prefs = instrument.targetContext.getSharedPreferences("continue_watching", 0)
            val original = prefs.getString("records", null)
            try {
                store.clear()
                store.record(video(900), 1_500, 0, false, null)
                assertEquals(1_500, ContinueWatching(instrument.targetContext).position(video(900).id))
                onMain { model.continueWatching(store.videos()) }
                assertEquals("Continue watching", model.rows.value[1].label)
                store.record(video(900), 0, 0, true, null)
                assertTrue(ContinueWatching(instrument.targetContext).videos().isEmpty())
                onMain { model.continueWatching(store.videos()); model.arrange(GenrePreferences(), true, store.finished()) }
                assertEquals(9, model.rows.value.size)
            } finally {
                prefs.edit().clear().apply { if (original != null) putString("records", original) }.commit()
            }
        } finally { onMain { model.cancel() } }
    }

    @Test fun continueWatchingPrivatePersistenceSecondRowAndCompletionRemoval() {
        val prefs = instrument.targetContext.getSharedPreferences("continue_watching", 0)
        val original = prefs.getString("records", null)
        val updatePrefs = instrument.targetContext.getSharedPreferences("updates", 0)
        val automatic = updatePrefs.getBoolean("automatic", true)
        lateinit var model: BrowseViewModel
        var activity: MainActivity? = null
        try {
            val store = ContinueWatching(instrument.targetContext)
            store.clear()
            store.record(video(1), 35_000, 120_000, false, Genre.MUSIC)
            val reopened = ContinueWatching(instrument.targetContext)
            assertEquals(35_000, reopened.position(video(1).id))
            assertEquals(Genre.MUSIC, reopened.genre(video(1).id))
            assertFalse(prefs.getString("records", "")!!.contains("https:"))
            updatePrefs.edit().putBoolean("automatic", false).commit()
            activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val host = requireNotNull(activity)
            onMain {
                model = BrowseViewModel(object : VideoCatalog {
                    override suspend fun search(query: String, cursor: String?) = CatalogPage(listOf(video(0)), null)
                    override fun cancelPending() {}
                })
                model.continueWatching(reopened.videos())
                host.setContent { MaterialTheme { BrowseScreen(model, "home", {}, {}, {}, {}, play = { _, _ -> }) } }
            }
            assertEquals("Continue watching", model.rows.value[1].label)
            await { focused().contains("Home") && model.rows.value.first().loaded }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { focused().contains("Fixture video 0") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await { focused().contains("Fixture video 1") && focused().contains("Resume 0:35") }
            onMain { model.arrange(GenrePreferences(mapOf(Genre.MUSIC to 20.0)), true) }
            assertEquals("Continue watching", model.rows.value[1].label)
            reopened.record(video(1), 120_000, 120_000, true, Genre.MUSIC)
            reopened.record(video(1), 35_000, 120_000, false, Genre.MUSIC)
            assertTrue(ContinueWatching(instrument.targetContext).videos().isEmpty())
            onMain { model.continueWatching(reopened.videos()) }
            assertEquals(9, model.rows.value.size)
            assertTrue(model.rows.value.none { it.key == "continue" })
            assertEquals("recommended", model.focus?.rowKey)
            prefs.edit().putString("records", "broken json").commit()
            assertTrue(ContinueWatching(instrument.targetContext).videos().isEmpty())
            reopened.clear()
            assertTrue(prefs.all.isEmpty())
        } finally {
            onMain { runCatching { model.cancel() }; activity?.finish() }
            instrument.waitForIdleSync()
            prefs.edit().clear().apply { if (original != null) putString("records", original) }.commit()
            updatePrefs.edit().putBoolean("automatic", automatic).commit()
        }
    }

    @Test fun paginationErrorsCancellationAndPrivateReset() {
        var failMore = true
        val fake = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?): CatalogPage {
                if (query == Genre.NATURE.query) withContext(NonCancellable) { delay(350) }
                if (query == Genre.MUSIC.query && cursor != null) throw AppException(ErrorCode.UPSTREAM_CHANGED)
                if (cursor != null && failMore) throw AppException(ErrorCode.OFFLINE)
                return CatalogPage((if (cursor == null) 0..49 else 40..89).map(::video), "next")
            }
            override fun cancelPending() {}
        }
        lateinit var model: BrowseViewModel
        onMain { model = BrowseViewModel(fake); model.load(Genre.SCIENCE) }
        await { model.rows.value.first { it.genre == Genre.SCIENCE }.loaded }
        onMain { model.load(Genre.SCIENCE, more = true) }
        await { model.rows.value.first { it.genre == Genre.SCIENCE }.error != null }
        assertEquals(50, model.rows.value.first { it.genre == Genre.SCIENCE }.videos.size)
        assertEquals("next", model.rows.value.first { it.genre == Genre.SCIENCE }.cursor)
        failMore = false
        onMain { model.load(Genre.SCIENCE, more = true) }
        await { model.rows.value.first { it.genre == Genre.SCIENCE }.videos.size == 60 }
        assertNull(model.rows.value.first { it.genre == Genre.SCIENCE }.cursor)
        assertEquals(60, model.rows.value.first { it.genre == Genre.SCIENCE }.videos.distinctBy { it.id }.size)
        onMain { model.load(Genre.MUSIC) }
        await { model.rows.value.first { it.genre == Genre.MUSIC }.videos.size == 50 }
        onMain { model.load(Genre.MUSIC, more = true) }
        await { model.rows.value.first { it.genre == Genre.MUSIC }.error != null }
        assertEquals(50, model.rows.value.first { it.genre == Genre.MUSIC }.videos.size)
        assertNull(model.rows.value.first { it.genre == Genre.MUSIC }.cursor)
        onMain { model.load(Genre.MUSIC, retry = true) }
        await { model.rows.value.first { it.genre == Genre.MUSIC }.let { !it.busy && it.error == null && it.cursor != null } }
        assertEquals(50, model.rows.value.first { it.genre == Genre.MUSIC }.videos.size)
        onMain { model.load(Genre.NATURE) }
        Thread.sleep(80)
        onMain { model.clear() }
        Thread.sleep(450)
        assertTrue(model.rows.value.all { it.videos.isEmpty() && !it.busy })
        val profile = Personalization(instrument.targetContext)
        val prefs = instrument.targetContext.getSharedPreferences("genre_preferences", 0)
        val saved = prefs.all
        try {
            profile.reset(); profile.enabled = true
            profile.record(Genre.MUSIC, 100_000, 120_000)
            assertEquals(Genre.MUSIC, profile.profile().order().first())
            assertTrue(prefs.all.keys.all { it == "enabled" || Genre.entries.any { genre -> genre.name == it } })
            val before = profile.profile().scores
            profile.enabled = false; profile.record(Genre.SCIENCE, 900_000, 900_000)
            assertEquals(before, profile.profile().scores)
            onMain { model.arrange(profile.profile(), false) }
            assertEquals(Genre.entries, model.rows.value.mapNotNull { it.genre })
            profile.reset()
            assertFalse(profile.profile().personalized)
            assertFalse(profile.enabled)
        } finally {
            val edit = prefs.edit().clear()
            saved.forEach { (key, value) -> when (value) { is Float -> edit.putFloat(key, value); is Boolean -> edit.putBoolean(key, value) } }
            edit.commit(); onMain { model.cancel() }
        }
    }

    @Test fun remoteRowsPaginationAndRestoreAfterPlayerDisposal() {
        val updatePrefs = instrument.targetContext.getSharedPreferences("updates", 0)
        val automatic = updatePrefs.getBoolean("automatic", true)
        updatePrefs.edit().putBoolean("automatic", false).commit()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var model: BrowseViewModel
        var opened by mutableStateOf(false)
        val fake = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?): CatalogPage {
                if (query == Genre.SPORT.query) delay(500)
                return CatalogPage((if (cursor == null) 0..5 else 5..9).map(::video), if (cursor == null) "page2" else null)
            }
            override fun cancelPending() {}
        }
        try {
            onMain {
                model = BrowseViewModel(fake)
                activity.setContent {
                    MaterialTheme {
                        if (opened) FixturePlayer { opened = false }
                        else BrowseScreen(model, "home", {}, {}, {}, {}, play = { _, _ -> opened = true })
                    }
                }
            }
            await { model.rows.value.first { it.genre == Genre.SCIENCE }.videos.size == 6 && focused().contains("Home") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { focused().contains("Fixture video 0") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await { model.focus?.genre == Genre.SCIENCE }
            for (index in 1..5) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await { focused().contains("Fixture video $index") }
            }
            assertTrue("Remote focus: ${focused()}", focused().contains("Fixture video 5"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { opened }
            await { focused().contains("Return from player") }
            onMain { opened = false }
            await { focused().contains("Fixture video 5") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { focused().contains("More videos") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { model.rows.value.first { it.genre == Genre.SCIENCE }.videos.size == 10 }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await { focused().contains("Fixture video 9") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await { model.focus?.genre == Genre.NATURE }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await { model.focus?.genre == Genre.SCIENCE }
            for (genre in Genre.entries.drop(1)) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await { model.focus?.genre == genre }
            }
            await { focused().contains("Fixture video 5") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await { opened }
            await { focused().contains("Return from player") }
            onMain { opened = false }
            await { model.focus?.genre == Genre.SPORT && focused().contains("Fixture video 5") }
            key(KeyEvent.KEYCODE_BACK)
            await { focused().contains("Home") }
        } catch (failure: Throwable) {
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                instrument.targetContext.openFileOutput("browse-fixture-failure.png", 0).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            throw AssertionError("${failure.message}; browse anchor: ${model.focus}", failure)
        } finally {
            onMain { model.cancel(); activity.finish() }
            updatePrefs.edit().putBoolean("automatic", automatic).commit()
        }
    }

    @Composable private fun FixturePlayer(back: () -> Unit) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
        TvAction("Return from player", Modifier.fillMaxSize().focusRequester(first), onClick = back)
    }
}
