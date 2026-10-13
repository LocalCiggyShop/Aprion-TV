package org.privateview.quietscreen

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.*
import org.junit.Test
import org.privateview.quietscreen.ui.UpdateScreen
import org.privateview.quietscreen.updates.*

class UpdateScreenContractTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { if (all.size >= 1500) return; all += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrument.uiAutomation.rootInActiveWindow?.let(::visit)
        return all
    }
    private fun text() = nodes().joinToString(" ") { listOfNotNull(it.text, it.contentDescription).joinToString(" ") }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(60) }
        assertTrue("Update UI condition timed out", condition())
    }
    @Test fun bytesPercentCancelFocusAndBackAreRemoteAccessible() = fixture { host ->
        var cancelled = 0
        instrument.runOnMainSync { host.setContent { MaterialTheme {
            UpdateScreen(UpdateStatus(message = "Downloading update…", busy = true, phase = UpdatePhase.DOWNLOADING,
                downloadedBytes = 1_250_000, totalBytes = 2_500_000)) { cancelled++ }
        } } }
        await { text().contains("1.25 / 2.50 MB") && text().contains("50%") && nodes().any { it.isFocused } }
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await { cancelled == 1 }
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { cancelled == 2 }
    }
    @Test fun committedInstallationShowsSizeAndNeverCancelsOnBack() = fixture { host ->
        var cancelled = 0
        instrument.runOnMainSync { host.setContent { MaterialTheme {
            UpdateScreen(UpdateStatus(message = "Android is installing the update…", busy = true, phase = UpdatePhase.INSTALLING,
                downloadedBytes = 2_500_000, totalBytes = 2_500_000)) { cancelled++ }
        } } }
        await { text().contains("APK size: 2.50 MB") && text().contains("Android is installing") }
        assertFalse(text().contains("Cancel update"))
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); Thread.sleep(200)
        assertEquals(0, cancelled)
        assertFalse(host.isFinishing)
    }
    private fun fixture(test: (MainActivity) -> Unit) {
        val prefs = instrument.targetContext.getSharedPreferences("updates", 0)
        val automatic = prefs.getBoolean("automatic", true)
        prefs.edit().putBoolean("automatic", false).commit()
        val host = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try { test(host) } finally {
            instrument.runOnMainSync { host.finish() }; instrument.waitForIdleSync()
            prefs.edit().putBoolean("automatic", automatic).commit()
        }
    }
}
