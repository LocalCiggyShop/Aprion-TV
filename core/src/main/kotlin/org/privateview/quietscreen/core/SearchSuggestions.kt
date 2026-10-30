package org.privateview.quietscreen.core

import kotlinx.coroutines.*

interface SearchSuggestions {
    suspend fun suggest(query: String): List<String>
    fun cancelPending()
}

fun boundedSuggestions(query: String, values: List<String>): List<String> = values.asSequence().take(50)
    .map { it.trim().take(200) }.filter { it.isNotBlank() && it.none(Char::isISOControl) && !it.equals(query.trim(), true) }
    .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(6).toList()

/** Main-thread owner. One debounced request and eight bounded memory-only entries. */
class SearchSuggestionSession(private val scope: CoroutineScope, private val provider: SearchSuggestions,
    private val debounceMs: Long = 250, private val timeoutMs: Long = 3500,
    private val changed: (List<String>) -> Unit) {
    private var job: Job? = null
    private var generation = 0
    private val cache = LinkedHashMap<String, List<String>>()
    fun update(query: String) {
        dismiss()
        val q = query.trim().take(200)
        if (q.isEmpty()) return
        cache[q]?.let { changed(it); return }
        val current = generation
        job = scope.launch {
            delay(debounceMs)
            try {
                val values = withTimeout(timeoutMs) { provider.suggest(q) }
                ensureActive()
                if (current != generation) return@launch
                val choices = boundedSuggestions(q, values)
                cache[q] = choices
                while (cache.size > 8) cache.remove(cache.keys.first())
                changed(choices)
            } catch (_: TimeoutCancellationException) { if (current == generation) provider.cancelPending() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional suggestions never replace search results/errors. */ }
        }
    }
    fun dismiss() { generation++; job?.cancel(); job = null; provider.cancelPending(); changed(emptyList()) }
    fun clear() { dismiss(); cache.clear() }
}
