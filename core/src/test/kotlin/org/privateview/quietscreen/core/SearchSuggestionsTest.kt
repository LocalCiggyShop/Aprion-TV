package org.privateview.quietscreen.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    @Test fun responsesArePlainBoundedUniqueCompletions() {
        val choices = boundedSuggestions("music", listOf(" music ", "", "  Music videos  ", "music VIDEOS", "bad\ntext") +
            (1..20).map { "choice $it" })
        assertEquals(6, choices.size)
        assertEquals("Music videos", choices.first())
        assertTrue(choices.none { it.contains('\n') })
        assertTrue(boundedSuggestions("", listOf("x".repeat(500))).single().length == 200)
    }
    @Test fun typingDebouncesCachesAndClearsWithoutBackgroundRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = mutableListOf<String>()
        var visible = emptyList<String>()
        val received = CompletableDeferred<Unit>()
        val provider = object : SearchSuggestions {
            override suspend fun suggest(query: String): List<String> { requests += query; return listOf("$query videos") }
            override fun cancelPending() {}
        }
        val session = SearchSuggestionSession(scope, provider, debounceMs = 20) { visible = it; if (it.isNotEmpty()) received.complete(Unit) }
        try {
            session.update("m"); session.update("mu"); session.update("music")
            withTimeout(1500) { received.await() }
            assertEquals(listOf("music"), requests)
            session.update("music"); assertEquals(listOf("music videos"), visible); assertEquals(1, requests.size)
            session.update(" "); assertTrue(visible.isEmpty())
            session.update("cancelled"); session.dismiss(); delay(40); assertEquals(1, requests.size)
            session.clear(); session.update("music"); delay(50); assertEquals(2, requests.size)
        } finally { session.clear(); scope.cancel() }
    }
    @Test fun lateCancelledResponsesCannotReplaceNewSuggestions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val newest = CompletableDeferred<Unit>()
        var visible = emptyList<String>()
        val provider = object : SearchSuggestions {
            override suspend fun suggest(query: String): List<String> {
                if (query == "old") withContext(NonCancellable) { started.complete(Unit); release.await() }
                return listOf("$query videos")
            }
            override fun cancelPending() {}
        }
        val session = SearchSuggestionSession(scope, provider, debounceMs = 0) { visible = it; if (it == listOf("new videos")) newest.complete(Unit) }
        try {
            session.update("old"); withTimeout(1500) { started.await() }; session.update("new")
            withTimeout(1500) { newest.await() }; release.complete(Unit)
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals(listOf("new videos"), visible)
        } finally { release.complete(Unit); session.clear(); scope.cancel() }
    }
    @Test fun timeoutAndProviderFailuresLeaveSearchAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cancellations = 0; var visible = emptyList<String>()
        val provider = object : SearchSuggestions {
            override suspend fun suggest(query: String): List<String> {
                if (query == "slow") delay(1000) else throw IllegalStateException("fixture")
                return emptyList()
            }
            override fun cancelPending() { cancellations++ }
        }
        val session = SearchSuggestionSession(scope, provider, debounceMs = 0, timeoutMs = 20) { visible = it }
        try {
            session.update("slow"); scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertTrue(cancellations >= 2); assertTrue(visible.isEmpty())
            session.update("failed"); scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }; assertTrue(visible.isEmpty())
        } finally { session.clear(); scope.cancel() }
    }
}
