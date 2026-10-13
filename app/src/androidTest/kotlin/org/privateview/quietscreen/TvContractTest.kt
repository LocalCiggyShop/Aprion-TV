package org.privateview.quietscreen

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.extractor.NetworkAudit
import org.privateview.quietscreen.playback.BoundedDataSource
import org.privateview.quietscreen.presentation.SearchViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvContractTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrument.uiAutomation
    private val ownPackage get() = instrument.targetContext.packageName

    private fun roots(): List<AccessibilityNodeInfo> {
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        return automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == ownPackage }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (result.size >= 2000) return
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        roots().forEach(::visit)
        return result
    }
    private fun texts() = nodes().flatMap { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) }.joinToString("\n")
    private fun focusedText(): String {
        fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ") +
            (0 until node.childCount).mapNotNull { node.getChild(it) }.joinToString(" ") { label(it) }
        return nodes().filter { it.isFocused }.joinToString(" ") { label(it) }
    }
    private fun waitText(text: String, seconds: Int = 20, revealPlayer: Boolean = false): String {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = texts()
            if (last.contains(text)) return last
            if (revealPlayer && !last.contains("Playback ")) key(KeyEvent.KEYCODE_DPAD_UP)
            Thread.sleep(500)
        }
        fail("Expected UI text '$text'; last state: $last")
        return last
    }
    private fun key(code: Int) {
        val time = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0), true))
        assertTrue(automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0), true))
        Thread.sleep(250)
    }
    private fun launch(): MainActivity {
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitText("Search videos")
        return activity
    }

    @Test fun homeSettingsAndBackUseRemoteAndSettingsStayIdle() {
        NetworkAudit.begin()
        val activity = launch()
        val settings = AppSettings(instrument.targetContext)
        val originalHeight = settings.maxHeight
        val originalCaptions = settings.captions
        try {
            waitText("Science & technology")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Maximum video resolution")
            Thread.sleep(1000)
            val stopped = NetworkAudit.snapshot()
            Thread.sleep(1000)
            assertEquals("Catalog requests stop in Settings", stopped, NetworkAudit.snapshot())
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("Remote selects saved 720p cap", 720, settings.maxHeight)
            key(KeyEvent.KEYCODE_BACK); waitText("Search videos")
            // Home restores Settings in the vertical navigation rail.
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("About Aprion TV")
            key(KeyEvent.KEYCODE_BACK); waitText("Search videos")
            println("TV_CONTRACT remoteHomeSettingsAbout=true savedSettings=true settingsIdle=true")
        } finally {
            settings.maxHeight = originalHeight; settings.captions = originalCaptions
            instrument.runOnMainSync { activity.finish() }
            NetworkAudit.end()
        }
    }

    @Test fun searchCancellationCannotReplaceNewerResults() {
        val calls = AtomicInteger()
        val catalog = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?): CatalogPage {
                calls.incrementAndGet()
                if (query == "slow") withContext(NonCancellable) { delay(350) } else delay(30)
                return CatalogPage(listOf(VideoSummary(VideoId(if (query == "slow") "jNQXAC9IVRw" else "dQw4w9WgXcQ"), query, "Fixture", null, 19)), null)
            }
            override fun cancelPending() {}
        }
        lateinit var model: SearchViewModel
        instrument.runOnMainSync {
            model = SearchViewModel(catalog)
            model.search()
            assertEquals(ErrorCode.INVALID_QUERY, model.state.value.error)
        }
        assertEquals("Empty query stays local", 0, calls.get())
        instrument.runOnMainSync { model.setQuery("slow"); model.search() }
        Thread.sleep(50)
        instrument.runOnMainSync { model.setQuery("new"); model.search() }
        Thread.sleep(600)
        instrument.runOnMainSync {
            assertEquals("new", model.state.value.videos.single().title)
            assertFalse(model.state.value.busy)
            model.clear()
            assertTrue(model.state.value.videos.isEmpty())
            assertTrue(model.state.value.query.isEmpty())
        }
        println("TV_CONTRACT emptyQueryLocal=true supersededSearchIgnored=true clearSession=true")
    }

    @Test fun boundedResponsesRejectOversizeAndAllowExactLimit() {
        class Fixture(private val size: Int, private val declared: Long) : DataSource {
            var index = 0
            var closed = false
            override fun addTransferListener(listener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long = declared
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, size - index)
                if (count <= 0) return C.RESULT_END_OF_INPUT
                java.util.Arrays.fill(buffer, offset, offset + count, 32.toByte()); index += count
                return count
            }
            override fun getUri(): android.net.Uri? = null
            override fun close() { closed = true }
        }
        val spec = DataSpec(android.net.Uri.parse("https://www.youtube.com/api/timedtext"))
        val declared = Fixture(100, 100)
        try { BoundedDataSource(declared, 16).open(spec); fail("Oversized declared response accepted") } catch (_: IOException) {}
        assertTrue(declared.closed)
        val exact = BoundedDataSource(Fixture(16, C.LENGTH_UNSET.toLong()), 16)
        exact.open(spec)
        val buffer = ByteArray(64)
        assertEquals(16, exact.read(buffer, 0, buffer.size))
        assertEquals(C.RESULT_END_OF_INPUT, exact.read(buffer, 0, buffer.size)); exact.close()
        val streamed = BoundedDataSource(Fixture(100, C.LENGTH_UNSET.toLong()), 16)
        streamed.open(spec)
        try { streamed.read(buffer, 0, buffer.size); fail("Unknown-length response exceeded budget") } catch (_: IOException) {}
        finally { streamed.close() }
        println("TV_CONTRACT declaredOversizeRejected=true unknownLengthBounded=true exactLimitAllowed=true")
    }

    @Test fun extractorJavascriptRemainsDeniedAfterShrinking() {
        val helper = Class.forName("org.schabi.newpipe.extractor.utils.JavaScript")
        fun denied(block: () -> Unit) {
            try { block(); fail("JavaScript evaluation was enabled") }
            catch (e: java.lang.reflect.InvocationTargetException) {
                assertTrue(e.targetException is SecurityException)
                assertEquals("QUIETSCREEN_JS_DISABLED", e.targetException.message)
            }
        }
        denied { helper.getMethod("compileOrThrow", String::class.java).invoke(null, "while(true){}") }
        denied { helper.getMethod("run", String::class.java, String::class.java, Array<String>::class.java)
            .invoke(null, "function f(){while(true){}}", "f", arrayOf("ignored")) }
        println("TV_SECURITY compileDenied=true runDenied=true noScriptExecuted=true")
    }

    @Test fun realSearchPlayerControlsRestoreResultsAndStopOnHome() {
        NetworkAudit.begin()
        val activity = launch()
        try {
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Title, channel or video ID")
            val edit = nodes().firstOrNull { it.isEditable } ?: error("Search input not accessible")
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "jNQXAC9IVRw")
            }))
            if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Me at the zoo", 60)
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Playing", 90, revealPlayer = true)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Paused", revealPlayer = true)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(3) { if (!focusedText().contains("Quality")) key(KeyEvent.KEYCODE_DPAD_LEFT) }
            assertTrue("Quality must be reachable using remote arrows: ${focusedText()}", focusedText().contains("Quality"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Auto (up to")
            key(KeyEvent.KEYCODE_BACK)
            waitText("Quality")
            key(KeyEvent.KEYCODE_BACK)
            val restored = waitText("Me at the zoo")
            assertTrue(restored.contains("jNQXAC9IVRw"))
            assertTrue("Back must restore the selected result focus", focusedText().contains("Me at the zoo"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("Playing", 90, revealPlayer = true)
            key(KeyEvent.KEYCODE_HOME)
            Thread.sleep(2000)
            val stopped = NetworkAudit.snapshot()
            Thread.sleep(2500)
            assertEquals("No new requests while app is stopped", stopped, NetworkAudit.snapshot())
            instrument.targetContext.startActivity(Intent(instrument.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText("Me at the zoo")
            assertFalse("Reentry must not auto-play", texts().contains("Playback Playing"))
            println("TV_CONTRACT liveSearch=true pause=true qualityMenu=true backRestoresResults=true homeStopsRequests=true reentryNoAutoPlay=true")
            println("TV_NETWORK_HOSTS ${NetworkAudit.snapshot()}")
        } finally { instrument.runOnMainSync { activity.finish() }; NetworkAudit.end() }
    }
}
