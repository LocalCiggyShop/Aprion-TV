package org.privateview.quietscreen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.BrowseRow
import org.privateview.quietscreen.ui.*

@RunWith(AndroidJUnit4::class)
class PlayerOverlayContractTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun video(number: Int) = VideoSummary(VideoId("%011d".format(number)), "Recommendation $number", "Fixture channel", null, 120)
    private fun nodes(): List<AccessibilityNodeInfo> {
        val ui = instrument.uiAutomation
        val service = ui.serviceInfo
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = service
        if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { if (result.size >= 2000) return; result += node; for (index in 0 until node.childCount) node.getChild(index)?.let(::visit) }
        ui.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == instrument.targetContext.packageName }.forEach(::visit)
        return result
    }
    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text, node.contentDescription).joinToString(" ") +
        (0 until node.childCount).mapNotNull { node.getChild(it) }.joinToString(" ") { label(it) }
    private fun focused() = nodes().filter { it.isFocused }.joinToString(" ") { label(it) }
    private fun text() = nodes().map(::label).joinToString(" ")
    private fun await(seconds: Int = 6, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(60) }
        assertTrue("Condition timed out; focus=${focused().take(400)}", condition())
    }
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); Thread.sleep(150) }

    @Test fun seekPauseMenusRecommendationSelectionAndRetryPreserveTheirContexts() {
        withFixture { host ->
            var current by mutableStateOf(video(40))
            var playing by mutableStateOf(true)
            var position by mutableLongStateOf(30_000)
            var height by mutableIntStateOf(1080)
            var qualityChanges = 0
            var backCalls = 0
            var refreshes = 0
            var nextGenre: Genre? = null
            var playbackError by mutableStateOf<ErrorCode?>(null)
            var playbackRetries = 0
            var row by mutableStateOf(BrowseRow(null, (40..60).map(::video), loaded = true, recommended = true,
                origins = (40..60).associate { video(it).id.value to Genre.GAMING }))
            onMain {
                host.setContent { MaterialTheme { androidx.compose.runtime.key(current.id.value) {
                    PlayerOverlay(PlayerOverlayInfo(current, position, 120_000, playing, false, playbackError?.userMessage() ?: if (playing) "Playing" else "Paused",
                        error = playbackError, hasPlan = true, canSeek = playbackError == null, requestedHeight = height), row,
                        loadRecommendations = { if (it) refreshes++ }, cancelRecommendations = {},
                        togglePlayback = { playing = !playing }, setPlaying = { playing = it }, seek = { position += it },
                        playNext = { next, genre -> nextGenre = genre; current = next; position = 0; playing = true },
                        back = { backCalls++ }, retry = { playbackRetries++; playbackError = null },
                        qualityChoices = { listOf("Auto" to {}, "480p" to { height = 480; qualityChanges++ }) }, audioChoices = { listOf("Original audio" to {}) })
                } } }
            }
            await { focused().contains("Pause video") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().trim() == "Captions" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().trim() == "Settings" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().contains("Pause video") }
            assertEquals(30_000, position)
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { !playing && focused().contains("Play video") }
            key(KeyEvent.KEYCODE_BACK); await { !text().contains("Recommended") }
            assertEquals(0, backCalls)
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { position == 40_000L && !text().contains("Recommended") }
            key(KeyEvent.KEYCODE_DPAD_LEFT); await { position == 30_000L }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { focused().contains("Play video") }
            assertFalse(playing)
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().trim() == "Captions" }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { text().contains("Captions aren't available in this version yet.") }
            assertEquals(30_000, position)
            key(KeyEvent.KEYCODE_BACK); await { !text().contains("Recommended") && !text().contains("Captions aren't") }
            assertEquals(0, backCalls)
            key(KeyEvent.KEYCODE_DPAD_UP); await { focused().trim() == "Captions" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().trim() == "Settings" }
            assertEquals(30_000, position)
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { focused().contains("Video quality") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().trim() == "Audio track" }
            key(KeyEvent.KEYCODE_DPAD_LEFT); await { focused().contains("Video quality") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { focused().trim() == "Auto" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().contains("480p") }
            assertEquals(0, qualityChanges)
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { qualityChanges == 1 && focused().trim() == "Settings" }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await { focused().contains("Play video") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await { focused().contains("Recommendation 41") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().contains("Recommendation 42") }
            assertEquals(30_000, position)
            key(KeyEvent.KEYCODE_DPAD_LEFT); await { focused().contains("Recommendation 41") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { current.id == video(41).id && focused().contains("Pause video") }
            assertEquals(Genre.GAMING, nextGenre)
            key(KeyEvent.KEYCODE_BACK); await { !text().contains("Recommended") }
            assertEquals(0, backCalls)
            key(KeyEvent.KEYCODE_BACK); await { backCalls == 1 }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { focused().contains("Pause video") }
            onMain { playing = false; row = row.copy(videos = emptyList(), error = ErrorCode.OFFLINE) }
            await { text().contains("network could not be reached") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await { focused().contains("Try again") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { refreshes == 1 }
            assertTrue(focused().contains("Try again"))
            key(KeyEvent.KEYCODE_DPAD_UP); await { focused().contains("Play video") }
            onMain { playbackError = ErrorCode.DECODER }
            await { text().contains("Try playback again") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await { focused().contains("Try playback again") }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await { playbackRetries == 1 && focused().contains("Play video") }
        }
    }

    @Test fun hiddenOverlayArrowsSeekOnFirstPressAndPausedOverlayStaysVisible() {
        withFixture { host ->
            var position by mutableLongStateOf(30_000)
            var playing by mutableStateOf(true)
            onMain {
                host.setContent { MaterialTheme {
                    PlayerOverlay(PlayerOverlayInfo(video(70), position, 120_000, playing, false, if (playing) "Playing" else "Paused", hasPlan = true, canSeek = true),
                        BrowseRow(null, listOf(video(71)), loaded = true, recommended = true), {}, {}, { playing = !playing }, { playing = it },
                        { position += it }, { _, _ -> }, {}, {}, { emptyList() }, { emptyList() })
                } }
            }
            await { focused().contains("Pause video") }
            await(11) { !text().contains("Recommended") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { position == 40_000L && !text().contains("Recommended") }
            key(KeyEvent.KEYCODE_MEDIA_PAUSE); await { !playing && focused().contains("Play video") }
            key(KeyEvent.KEYCODE_DPAD_LEFT); await { focused().trim() == "Settings" }
            assertEquals(40_000, position)
            key(KeyEvent.KEYCODE_BACK); await { !text().contains("Recommended") }
            key(KeyEvent.KEYCODE_DPAD_LEFT); await { position == 30_000L }
            key(KeyEvent.KEYCODE_DPAD_UP); await { focused().trim() == "Settings" }
            assertFalse(playing)
        }
    }

    @Test fun bufferingSpinnerTracksLoadingAndNeverOverridesMenuOrErrorFocus() {
        withFixture { host ->
            var buffering by mutableStateOf(true)
            var error by mutableStateOf<ErrorCode?>(null)
            var ended by mutableStateOf(false)
            onMain { host.setContent { MaterialTheme {
                PlayerOverlay(PlayerOverlayInfo(video(80), 0, 120_000, false, ended, "Buffering",
                    error = error, buffering = buffering), BrowseRow(null, loaded = true, recommended = true),
                    {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, { emptyList() }, { emptyList() })
            } } }
            await { text().contains("Buffering video") && focused().contains("Play video") }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/quietscreen-player-ux-buffering.png")).use { command ->
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val read = command.read(buffer)
                    if (read < 0) break
                    total += read
                    assertTrue("Screenshot command output exceeded bound", total <= 8192)
                }
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused().trim() == "Captions" }
            assertTrue(text().contains("Buffering video"))
            onMain { buffering = false }; await { !text().contains("Buffering video") }
            onMain { buffering = true; error = ErrorCode.OFFLINE }
            await { text().contains("Try playback again") && !text().contains("Buffering video") }
            key(KeyEvent.KEYCODE_BACK); await { !text().contains("Try playback again") }
            onMain { error = null; buffering = false; ended = true }
            await { focused().contains("Replay video") && text().contains("Recommended") }
            key(KeyEvent.KEYCODE_BACK); await { !text().contains("Recommended") }
        }
    }

    private fun withFixture(test: (MainActivity) -> Unit) {
        val preferences = instrument.targetContext.getSharedPreferences("updates", 0)
        val automatic = preferences.getBoolean("automatic", true)
        preferences.edit().putBoolean("automatic", false).commit()
        val host = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try { test(host) } finally {
            onMain { host.finish() }; instrument.waitForIdleSync()
            preferences.edit().putBoolean("automatic", automatic).commit()
        }
    }
}
