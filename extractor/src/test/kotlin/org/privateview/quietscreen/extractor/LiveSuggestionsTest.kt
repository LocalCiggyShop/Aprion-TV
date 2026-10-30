package org.privateview.quietscreen.extractor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.privateview.quietscreen.core.boundedSuggestions

class LiveSuggestionsTest {
    @Test fun existingExtractorOffersBoundedSingleLetterAndWordCompletions() = runBlocking {
        assumeTrue(System.getProperty("quietscreen.live") == "true")
        val catalog = NewPipeCatalog()
        try {
            for (query in listOf("m", "mu", "music")) {
                val choices = withTimeout(6000) { catalog.suggest(query) }
                assertTrue("No live suggestions supplied", choices.isNotEmpty())
                assertEquals(choices, boundedSuggestions(query, choices))
                println("SUGGESTION_PROVIDER queryLength=${query.length} choices=${choices.size}")
            }
        } finally { catalog.cancelPending() }
    }
}
