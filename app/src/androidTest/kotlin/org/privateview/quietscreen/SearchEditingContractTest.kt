package org.privateview.quietscreen

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.SearchViewModel
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SearchEditingContractTest {
    @Test fun cursorAndExitRetentionDoNotStartSuggestionOrVideoRequests() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val suggestions = AtomicInteger()
        val searches = AtomicInteger()
        val catalog = object : VideoCatalog {
            override suspend fun search(query: String, cursor: String?): CatalogPage {
                searches.incrementAndGet(); return CatalogPage(emptyList(), null)
            }
            override fun cancelPending() {}
        }
        val provider = object : SearchSuggestions {
            override suspend fun suggest(query: String): List<String> {
                suggestions.incrementAndGet(); return listOf("alpha example")
            }
            override fun cancelPending() {}
        }
        lateinit var model: SearchViewModel
        instrument.runOnMainSync { model = SearchViewModel(catalog, provider); model.setQuery("alpha") }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
            while (model.state.value.suggestions.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("Initial suggestion provider was not exercised", model.state.value.suggestions.isEmpty())
            assertEquals(1, suggestions.get())
            instrument.runOnMainSync {
                model.queryCursor = 2
                model.retainEditor("alpha beta", 6)
            }
            assertEquals("alpha beta", model.state.value.query)
            assertEquals(6, model.queryCursor)
            assertTrue(model.state.value.suggestions.isEmpty())
            Thread.sleep(500) // Exceeds the ordinary suggestion debounce; retention must remain request-free.
            assertEquals(1, suggestions.get()); assertEquals(0, searches.get())
            instrument.runOnMainSync { model.clear() }
            assertEquals("", model.state.value.query)
            assertEquals(Int.MAX_VALUE, model.queryCursor)
        } finally {
            instrument.runOnMainSync { model.clear(); model.viewModelScope.cancel() }
        }
    }
}
