package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class SearchSortTest {
    @Test fun newestOrdersLoadedPagesWithStableUnknownDatesAndRelevanceIsUntouched() {
        fun video(id:String,date:Long?) = VideoSummary(VideoId(id),id,"Fixture",null,19,uploadedAtMs=date)
        val first=listOf(video("dQw4w9WgXcQ",200),video("jNQXAC9IVRw",null))
        val next=listOf(video("9bZkp7q19f0",500),video("aqz-KE-bpKQ",100),video("iG9CE55wbtY",0))
        val pool=first+next
        assertEquals(listOf("9bZkp7q19f0","dQw4w9WgXcQ","aqz-KE-bpKQ","jNQXAC9IVRw","iG9CE55wbtY"),
            SearchSort.NEWEST.order(pool).map {it.id.value})
        assertSame(pool,SearchSort.RELEVANCE.order(pool))
    }
}
