package org.privateview.quietscreen

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.*
import org.privateview.quietscreen.ui.*

class UploadAgeContractTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val automation by lazy {instrument.uiAutomation.apply {
        val config=serviceInfo
        config.flags=config.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo=config
    }}
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo) {if(result.size>=2000 || !node.refresh())return;result+=node;for(i in 0 until node.childCount)node.getChild(i)?.let(::visit)}
        automation.windows.filter {it.isActive}.mapNotNull {it.root}.filter {it.packageName?.toString()==instrument.targetContext.packageName}.forEach(::visit)
        if(result.isEmpty())automation.rootInActiveWindow?.takeIf {it.packageName?.toString()==instrument.targetContext.packageName}?.let(::visit)
        return result
    }
    private fun label(node:AccessibilityNodeInfo,depth:Int=0):String = listOfNotNull(node.text,node.contentDescription).joinToString(" ") +
        if(depth<24)(0 until node.childCount).mapNotNull {node.getChild(it)}.joinToString(" ") {label(it,depth+1)} else ""
    private fun text()=nodes().joinToString(" ") {listOfNotNull(it.text,it.contentDescription).joinToString(" ")}
    private fun focused()=nodes().filter {it.refresh();it.isFocused}.joinToString(" ") {node->
        var owner:AccessibilityNodeInfo?=node
        while(owner!=null && !owner.isClickable)owner=owner.parent
        label(owner ?: node)
    }
    private fun render(host:MainActivity,content:@Composable ()->Unit) {
        host.setContent {MaterialTheme {UploadAgeScope {Box(Modifier.fillMaxSize().background(Ink)){content()}}}}
    }
    private fun await(condition:()->Boolean) {val until=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<until){if(condition())return;Thread.sleep(60)};assertTrue("Upload age UI timed out; focus=${focused()}; UI=${text()}",condition())}
    private fun key(code:Int) {val now=SystemClock.uptimeMillis();assertTrue(instrument.uiAutomation.injectInputEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0),true));assertTrue(instrument.uiAutomation.injectInputEvent(KeyEvent(now,now,KeyEvent.ACTION_UP,code,0),true));Thread.sleep(120)}
    private fun dated()=VideoSummary(VideoId("dQw4w9WgXcQ"),"A long fixture title that uses two lines so the date stays readable below the channel","Fixture channel",null,120,uploadedAtMs=System.currentTimeMillis()-180_000_000)
    private fun missing()=dated().copy(id=VideoId("jNQXAC9IVRw"),title="Unknown upload fixture",uploadedAtMs=null)
    private fun insideCard() {
        // Compose can merge the title, channel and age into the card's text node.
        fun card(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            var parent:AccessibilityNodeInfo?=node
            while(parent!=null && !parent.isClickable)parent=parent.parent
            return parent?.takeUnless {label(it).contains("Play video") || label(it).contains("Pause video")}
        }
        val (age,parent)=nodes().mapNotNull {node->
            if(node.isVisibleToUser && node.text?.toString()?.contains("Uploaded 2 days ago")==true)
                card(node)?.let {node to it} else null
        }.firstOrNull() ?: error("No visible video-card age; UI=${text()}")
        val ageBounds=Rect();val cardBounds=Rect();age.getBoundsInScreen(ageBounds);parent.getBoundsInScreen(cardBounds)
        assertTrue("Age clipped by its card: $ageBounds / $cardBounds",cardBounds.contains(ageBounds))
    }
    private fun ageBounds()=nodes().filter {it.isVisibleToUser && it.text?.toString()?.contains("Uploaded 2 days ago")==true}
            .map {Rect().also(it::getBoundsInScreen)}
    private fun focusBounds()=nodes().filter {it.isFocused && it.isClickable}.map {Rect().also(it::getBoundsInScreen)}
    private fun focusContainsAge()=ageBounds().any {age->focusBounds().any {it.contains(age)}}
    private fun insideFocusedCard() {
        val ages=ageBounds();val focus=focusBounds()
        assertTrue("The focused video card must contain its visible age: $ages / $focus",ages.any {age->focus.any {it.contains(age)}})
    }
    private fun capture(surface:String) {
        val bitmap=instrument.uiAutomation.takeScreenshot();assertNotNull(bitmap)
        java.io.File(instrument.targetContext.filesDir,"upload-age-fixture-$surface.png").outputStream().use {
            assertTrue(bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
        };bitmap!!.recycle()
    }
    @Test fun cardsPlayerAndFeedbackShowDatesWithoutClippingOrLosingFocus()=fixture {host->
        val known=dated();val unknown=missing()
        val catalog=object:VideoCatalog {
            override suspend fun search(query:String,cursor:String?)=CatalogPage(listOf(known,unknown),null)
            override fun cancelPending(){}
        }
        lateinit var search:SearchViewModel;lateinit var browse:BrowseViewModel
        instrument.runOnMainSync {search=SearchViewModel(catalog);search.setQuery("fixture");search.search();render(host){SearchScreen(search,{}, {})}}
        await {text().contains("Uploaded 2 days ago") && text().contains("Upload date unavailable")};insideCard();capture("search")
        instrument.runOnMainSync {search.cancel();browse=BrowseViewModel(catalog);browse.continueWatching(listOf(UnfinishedVideo(known,20_000,120_000,null)));browse.loadRow("recommended");render(host){BrowseScreen(browse,"home",{},{},{},{},play={_,_->})}}
        await {focused().contains("Home") && browse.rows.value.first().loaded}
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await {browse.focus?.rowKey=="recommended" && browse.focus?.videoId!=null}
        val cards=browse.rows.value.first().videos
        val target=cards.indexOfFirst {it.id==known.id};assertTrue("Known fixture must be in Home",target>=0)
        val current=cards.indexOfFirst {it.id.value==browse.focus?.videoId};assertTrue(current>=0)
        repeat(kotlin.math.abs(target-current)) {key(if(target>current)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)}
        await {browse.focus?.rowKey=="recommended" && focused().contains(known.title) && focused().contains("Uploaded 2 days ago")};insideCard();capture("home")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await {browse.focus?.rowKey=="continue" && browse.focus?.videoId==known.id.value && focused().contains("Resume")}
        // TV8 may expose only the resume badge as the focused card's merged text.
        // Verify the actual focus rectangle includes the date, rather than its text aggregation.
        insideFocusedCard();capture("continue")
        instrument.runOnMainSync {browse.cancel();render(host){
            PlayerOverlay(PlayerOverlayInfo(known,20_000,120_000,false,false,"Paused",hasPlan=true,canSeek=true),BrowseRow(null,listOf(known.copy(id=VideoId("9bZkp7q19f0")),unknown),loaded=true,recommended=true),
                {},{},{},{},{},{_,_->},{},{},{emptyList()},{emptyList()})
        }}
        await {focused().contains("Play video") && text().contains("Uploaded 2 days ago")}
        key(KeyEvent.KEYCODE_DPAD_DOWN);await {!focused().contains("Play video") && focusContainsAge()};insideFocusedCard();capture("player")
        val shelfFocus=focusBounds()
        key(KeyEvent.KEYCODE_MENU);await {text().contains("Tune recommendations") && text().contains(known.title) && text().contains("Uploaded 2 days ago") && focused().contains("More like this")};capture("feedback")
        key(KeyEvent.KEYCODE_BACK);await {!text().contains("Tune recommendations") && focusContainsAge()}
        assertEquals("Feedback must restore focus to the same dated related card",shelfFocus,focusBounds())
        println("UPLOAD_AGE_UI search=true home=true continue=true player=true relatedShelf=true feedback=true cardDateNotClipped=true remoteFocus=true")
    }
    @Test fun sharedClockAdvancesFreshSecondsIntoMinutes()=fixture {host->
        val fresh=dated().copy(uploadedAtMs=System.currentTimeMillis()-57_000)
        instrument.runOnMainSync {render(host){UploadAgeText(fresh)}}
        await {text().contains("seconds ago")}
        await {text().contains("Uploaded 1 minute ago")}
        println("UPLOAD_AGE_CLOCK freshSecondBoundary=true sharedForegroundClock=true")
    }
    @Test fun resumeDatesSurviveReloadAndLegacyRecordsRemainUsable() {
        val prefs=instrument.targetContext.getSharedPreferences("continue_watching",0);val original=prefs.getString("records",null)
        try {
            prefs.edit().remove("records").commit();val video=dated()
            ContinueWatching(instrument.targetContext).record(video,20_000,120_000,false,null)
            assertEquals(video.uploadedAtMs,ContinueWatching(instrument.targetContext).videos().single().video.uploadedAtMs)
            val json=JSONObject(prefs.getString("records","{}")!!);json.getJSONArray("videos").getJSONObject(0).remove("uploadedAt")
            prefs.edit().putString("records",json.toString()).commit()
            assertNull(ContinueWatching(instrument.targetContext).videos().single().video.uploadedAtMs)
            assertEquals(20_000L,ContinueWatching(instrument.targetContext).videos().single().positionMs)
            json.getJSONArray("videos").getJSONObject(0).put("uploadedAt",-1);prefs.edit().putString("records",json.toString()).commit()
            assertNull(ContinueWatching(instrument.targetContext).videos().single().video.uploadedAtMs)
            println("UPLOAD_AGE_STORAGE datePersists=true legacyResumePreserved=true invalidDateIgnored=true")
        } finally {val edit=prefs.edit();if(original==null)edit.remove("records")else edit.putString("records",original);edit.commit()}
    }
    private fun fixture(test:(MainActivity)->Unit) {
        val prefs=instrument.targetContext.getSharedPreferences("updates",0);val original=prefs.getBoolean("automatic",true)
        prefs.edit().putBoolean("automatic",false).commit()
        val host=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {test(host)}catch(failure:Throwable){capture("failure");throw failure}finally {instrument.runOnMainSync {host.finish()};instrument.waitForIdleSync();prefs.edit().putBoolean("automatic",original).commit()}
    }
}
