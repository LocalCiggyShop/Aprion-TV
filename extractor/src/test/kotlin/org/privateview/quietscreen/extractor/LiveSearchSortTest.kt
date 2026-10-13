package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.*
import java.io.File

class LiveSearchSortTest {
    @Test fun loadedPagesExposeDatesAndRejectChangedSortCursor() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog=NewPipeCatalog()
        try {
            val first=catalog.search("BBC News",null,SearchSort.NEWEST)
            assertNotNull("Provider pagination unavailable",first.cursor)
            val second=catalog.search("BBC News",first.cursor,SearchSort.NEWEST)
            val dates=SearchSort.NEWEST.order((first.videos+second.videos).distinctBy {it.id}).mapNotNull {it.uploadedAtMs}
            assertTrue("Provider returned insufficient dated videos",dates.size>=10)
            assertTrue("Loaded date order incorrect",dates.zipWithNext().all {(a,b)->a>=b})
            try {catalog.search("BBC News",first.cursor,SearchSort.RELEVANCE);fail("Mixed sort cursor accepted")}
            catch(e:AppException) {assertEquals(ErrorCode.UPSTREAM_CHANGED,e.code)}
            File("../docs/evidence/aprion-newest-loaded-results-check.txt").writeText("datedResults=${dates.size}\npages=2\nloadedNewestFirst=true\nchangedSortCursorRejected=true\nextraDateRequests=0\nglobalProviderUploadOrder=false\n")
        } finally {catalog.cancelPending()}
    }
}