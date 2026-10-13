package org.privateview.quietscreen

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.SearchViewModel
import org.privateview.quietscreen.ui.*
import org.privateview.quietscreen.updates.UpdateStatus

class UiFollowupsContractTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { if (all.size >= 1500) return; all += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrument.uiAutomation.rootInActiveWindow?.let(::visit)
        return all
    }
    private fun text() = nodes().joinToString(" ") { listOfNotNull(it.text, it.contentDescription).joinToString(" ") }
    private fun label(node: AccessibilityNodeInfo, depth: Int = 0): String =
        listOfNotNull(node.text,node.contentDescription).joinToString(" ") + if(depth<24)
            (0 until node.childCount).mapNotNull {node.getChild(it)}.joinToString(" ") {label(it,depth+1)} else ""
    private fun focusedText() = nodes().filter {it.refresh();it.isFocused}.joinToString(" ") {label(it)}
    private fun focused(label: String) = focusedText().contains(label)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime()+6000
        while (SystemClock.elapsedRealtime()<until) { if(condition()) return; Thread.sleep(60) }
        assertTrue("UI condition timed out; focused=${focusedText()}; UI=${text()}",condition())
    }
    private fun key(code: Int) {
        val time=SystemClock.uptimeMillis()
        assertTrue(instrument.uiAutomation.injectInputEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0),true))
        assertTrue(instrument.uiAutomation.injectInputEvent(KeyEvent(time,time,KeyEvent.ACTION_UP,code,0),true))
        Thread.sleep(120)
    }

    @Test fun readyModalFocusInstallLaterAndBackAreAccessible() = fixture { host ->
        var installs = 0; var dismissals = 0
        fun show() = instrument.runOnMainSync { host.setContent { MaterialTheme {
            UpdateInstallDialog(UpdateStatus(message="Update 0.3.7-beta downloaded and verified.",ready=true),
                close={dismissals++},install={installs++})
        } } }
        show(); await { focused("Install update") && text().contains("Update ready to install") }
        key(KeyEvent.KEYCODE_DPAD_DOWN); await { focused("Later") }; key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1,dismissals); assertEquals(0,installs)
        key(KeyEvent.KEYCODE_BACK); await { dismissals == 2 }
        key(KeyEvent.KEYCODE_DPAD_UP); await { focused("Install update") }; key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { installs == 1 }
        println("UI_FOLLOWUPS verifiedReadyModal=true remoteFocus=true installExplicit=true laterAndBack=true")
    }

    @Test fun sortButtonChangesProviderOrderAndStalePagesCannotReturn() = fixture { host ->
        val calls = java.util.concurrent.CopyOnWriteArrayList<Triple<String,String?,SearchSort>>()
        val catalog = object : VideoCatalog {
            override suspend fun search(query:String,cursor:String?) = error("Sort overload required")
            override suspend fun search(query:String,cursor:String?,sort:SearchSort):CatalogPage {
                calls += Triple(query,cursor,sort)
                if(cursor!=null) withContext(NonCancellable) {delay(400)} else delay(20)
                return CatalogPage(listOf(VideoSummary(VideoId(if(sort==SearchSort.NEWEST) "jNQXAC9IVRw" else "dQw4w9WgXcQ"),sort.label,"Fixture",null,19)),"page-${sort.name}")
            }
            override fun cancelPending() {}
        }
        lateinit var model:SearchViewModel
        instrument.runOnMainSync { model=SearchViewModel(catalog);model.setQuery("retained query");model.search()
            host.setContent { MaterialTheme { SearchScreen(model,home={},play={}) } } }
        await { !model.state.value.busy && text().contains("Sort: Newest first") }
        await { focused("retained query") }
        instrument.runOnMainSync { (host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(host.window.decorView.windowToken,0) }
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); await { focused("Sort:") }
        key(KeyEvent.KEYCODE_DPAD_CENTER); await { text().contains("Sort search results") && focused("Newest first") }
        instrument.runOnMainSync { model.search(true) }
        key(KeyEvent.KEYCODE_DPAD_DOWN); await { focused("Relevance") }; key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { model.state.value.sort==SearchSort.RELEVANCE && !model.state.value.busy && focused("Sort:") }
        Thread.sleep(450)
        assertEquals(listOf("Relevance"),model.state.value.videos.map { it.title })
        assertEquals("page-RELEVANCE",model.state.value.cursor)
        assertEquals("retained query",model.state.value.query)
        assertEquals(listOf(SearchSort.NEWEST,SearchSort.NEWEST,SearchSort.RELEVANCE),calls.map {it.third})
        assertNull(calls.last().second)
        instrument.runOnMainSync { model.clear() }
        assertEquals(SearchSort.NEWEST,model.state.value.sort)
        println("UI_FOLLOWUPS newestDefault=true adjacentSortRemote=true providerSort=true stalePaginationIgnored=true queryRetained=true")
    }
    private fun fixture(test:(MainActivity)->Unit) {
        val prefs=instrument.targetContext.getSharedPreferences("updates",0);val original=prefs.getBoolean("automatic",true)
        prefs.edit().putBoolean("automatic",false).commit()
        val host=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try { test(host) } finally {instrument.runOnMainSync {host.finish()};instrument.waitForIdleSync();prefs.edit().putBoolean("automatic",original).commit()}
    }
}
