package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.*
import java.io.File

/** Explicit opt-in. JVM networking evidence only; never called a playback/ad test. */
class LiveCatalogTest {
    @Test fun anonymousSearchSmoke() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        val queries = listOf("Me at the zoo", "Rick Astley Never Gonna Give You Up", "space documentary", "Australian wildlife", "宇宙 科学", "café music", "Kotlin coroutines?", "a".repeat(200), "quietscreenzzxnoresults847392", "")
        val report = mutableListOf("kind\tcase\toutcome\tdetail", "scope\tJVM\tNETWORK_ONLY\tNo decoder or advertisement observation")
        var failures = 0
        queries.forEachIndexed { i, q ->
            try {
                val p = catalog.search(q)
                if (q.isEmpty()) { failures++; report += "search\t${i + 1}\tFAIL\tEmpty input accepted" }
                else {
                    report += "search\t${i + 1}\tPASS\t${p.videos.size} items"
                    if (i == 0 && p.cursor != null) {
                        val next = catalog.search(q, p.cursor)
                        report += "pagination\t1\tPASS\t${next.videos.size} next-page items"
                    }
                }
            } catch (e: AppException) {
                if (q.isEmpty() && e.code == ErrorCode.INVALID_QUERY) report += "search\t${i + 1}\tPASS\tEmpty input rejected"
                else { failures++; report += "search\t${i + 1}\tFAIL\t${e.code}" }
            }
        }
        File("../docs/evidence/jvm-search.tsv").writeText(report.joinToString("\n") + "\n")
        assertEquals("See jvm-search.tsv; this is not emulator qualification", 0, failures)
    }
    @Test fun anonymousStreamDiscoverySmoke() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        val ids = listOf("jNQXAC9IVRw", "dQw4w9WgXcQ", "9bZkp7q19f0", "aqz-KE-bpKQ", "iG9CE55wbtY", "kJQP7kiw5Fk")
        val report = mutableListOf("video_id\toutcome\tduration_ms\theights\taudio_tracks\thls\tdash", "scope\tNETWORK_ONLY\t0\t\t0\tfalse\tfalse")
        var failures = 0
        ids.forEach { raw ->
            try {
                val p = catalog.resolve(VideoId(raw))
                report += "$raw\tDISCOVERED\t${p.durationMs}\t${p.videos.map { it.height }.distinct().sorted().joinToString(",")}\t${p.audio.size}\t${p.hls != null}\t${p.dash != null}"
            } catch (e: AppException) { failures++; report += "$raw\t${e.code}\t0\t\t0\tfalse\tfalse" }
        }
        File("../docs/evidence/jvm-stream-discovery.tsv").writeText(report.joinToString("\n") + "\n")
        assertEquals("Stream discovery alone does not prove playback or no ads", 0, failures)
    }
}
