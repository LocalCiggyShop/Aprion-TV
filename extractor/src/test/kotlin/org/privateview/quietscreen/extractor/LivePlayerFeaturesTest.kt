package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.*
import java.io.File

class LivePlayerFeaturesTest {
    @Test fun existingResponsesExposeCountsAndValidatedOptionalPreviews() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        try {
            val result = catalog.search("dQw4w9WgXcQ").videos.first { it.id.value == "dQw4w9WgXcQ" }
            assertTrue(result.viewCount != null && result.viewCount!! > 0)
            val plan = catalog.resolve(result.id)
            assertTrue(plan.viewCount != null && plan.viewCount!! > 0)
            plan.preview?.let { story ->
                story.sheets.forEach { NetworkPolicy.validate(it.url, NetworkRole.THUMBNAIL) }
                assertTrue(story.frame(plan.durationMs).sheet < story.sheets.size)
            }
            File("../docs/evidence/player-features-provider.txt").writeText("searchCount=true\nplayerCount=true\npreviewDescriptorAvailable=${plan.preview != null}\npreviewSheets=${plan.preview?.sheets?.size ?: 0}\nsignedUrlsLogged=false\n")
        } finally { catalog.cancelPending() }
    }
}
