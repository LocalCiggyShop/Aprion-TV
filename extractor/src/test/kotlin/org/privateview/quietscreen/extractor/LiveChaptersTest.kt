package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.*
import java.io.File
import java.util.Base64

class LiveChaptersTest {
    @Test fun existingVideoResponseProvidesUsableChapterStarts() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        try {
            val plan = withTimeout(45_000) { catalog.resolve(VideoId("KI7fMGRg0Wk")) }
            assertTrue("Pinned video must have multiple chapters", plan.chapters.size >= 3)
            assertEquals(plan.chapters, normalizeVideoChapters(plan.chapters, plan.durationMs))
            File("../.tools/chapter-provider.txt").writeText("id=${plan.id.value}\n" +
                "titleBase64=${Base64.getEncoder().encodeToString(plan.title.toByteArray(Charsets.UTF_8))}\n" +
                "starts=${plan.chapters.joinToString(",") { (it.startMs / 1000).toString() }}\n" +
                "chapterCount=${plan.chapters.size}\nmediaUrlsLogged=false\n")
        } finally { catalog.cancelPending() }
    }
}
