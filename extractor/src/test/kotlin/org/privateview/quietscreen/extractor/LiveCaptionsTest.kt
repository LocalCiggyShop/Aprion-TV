package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.VideoId
import java.io.File
import java.util.Base64

class LiveCaptionsTest {
    @Test fun providerSuppliesNonemptyTimedCaptions() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        val client = NetworkPolicy.client(NetworkRole.METADATA)
        try {
            val plan = withTimeout(45_000) { catalog.resolve(VideoId("iG9CE55wbtY")) }
            File("../.tools/caption-provider.txt").writeText("id=${plan.id.value}\n" +
                "titleBase64=${Base64.getEncoder().encodeToString(plan.title.toByteArray(Charsets.UTF_8))}\n" +
                "languages=${plan.captions.joinToString(",") { it.language }}\n")
            assertTrue("Pinned video has no captions", plan.captions.isNotEmpty())
            val caption = plan.captions.firstOrNull { it.language == "en" } ?: plan.captions.first()
            client.newCall(Request.Builder().url(caption.endpoint.url).build()).execute().use { response ->
                assertEquals("Caption HTTP failure", 200, response.code)
                val bytes = response.body.byteStream().use { it.readNBytes(1024 * 1024 + 1) }
                println("CAPTION_PROVIDER tracks=${plan.captions.size} language=${caption.language} format=${caption.format} bytes=${bytes.size}")
                assertTrue("Caption body must be nonempty and bounded", bytes.size in 1..1024 * 1024)
                val text = bytes.toString(Charsets.UTF_8)
                assertTrue("Expected timed caption content", text.contains("<tt") && text.contains("<p"))
            }
            val without = withTimeout(45_000) { catalog.resolve(VideoId("jNQXAC9IVRw")) }
            println("CAPTION_ABSENCE id=${without.id.value} tracks=${without.captions.size}")
        } finally { catalog.cancelPending(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
