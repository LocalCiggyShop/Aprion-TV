package org.privateview.quietscreen.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.privateview.quietscreen.core.*

data class SearchState(val query: String = "", val videos: List<VideoSummary> = emptyList(), val cursor: String? = null, val message: String = "Find a video by title, channel or video ID.", val busy: Boolean = false, val error: ErrorCode? = null, val sort: SearchSort = SearchSort.NEWEST)

class SearchViewModel(private val catalog: VideoCatalog) : ViewModel() {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var lastQuery = ""
    var focusedVideo: String? = null
    var listIndex = 0
    var listOffset = 0
    fun setQuery(value: String) { mutable.value = mutable.value.copy(query = value.take(200)) }
    fun sort(value: SearchSort) {
        if (value == mutable.value.sort) return
        cancel()
        focusedVideo = null; listIndex = 0; listOffset = 0
        mutable.value = mutable.value.copy(sort = value, videos = emptyList(), cursor = null, error = null)
        if (mutable.value.query.isNotBlank()) search()
    }
    fun search(more: Boolean = false) {
        if (more && (mutable.value.cursor == null || mutable.value.busy)) return
        val q = if (more) lastQuery else mutable.value.query.trim()
        if (q.isEmpty()) { mutable.value = mutable.value.copy(message = ErrorCode.INVALID_QUERY.userMessage(), error = ErrorCode.INVALID_QUERY); return }
        val cursor = if (more) mutable.value.cursor else null
        val previous = if (more) mutable.value.videos else emptyList()
        val sort = mutable.value.sort
        generation++; val current = generation
        catalog.cancelPending(); job?.cancel()
        if (!more) { focusedVideo = null; listIndex = 0; listOffset = 0 }
        mutable.value = mutable.value.copy(videos = previous, cursor = cursor, busy = true, error = null, message = "Searching…")
        job = viewModelScope.launch {
            try {
                val page = catalog.search(q, cursor, sort)
                ensureActive()
                if (generation != current) return@launch
                val results = sort.order((previous + page.videos).distinctBy { it.id }.take(150))
                lastQuery = q
                mutable.value = mutable.value.copy(videos = results, cursor = page.cursor.takeIf { results.size < 150 }, busy = false,
                    message = if (results.isEmpty()) "No videos found." else "${results.size} results" + (page.suggestion?.takeIf { it.isNotBlank() }?.let { " · Suggested: $it" } ?: ""))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) {
                val error = (e as? AppException)?.code ?: ErrorCode.UPSTREAM_CHANGED
                mutable.value = mutable.value.copy(busy = false, error = error, message = error.userMessage())
            } }
        }
    }
    fun cancel() { generation++; job?.cancel(); catalog.cancelPending(); mutable.value = mutable.value.copy(busy = false, message = if (mutable.value.busy) "Search cancelled." else mutable.value.message) }
    fun clear() { cancel(); lastQuery = ""; focusedVideo = null; listIndex = 0; listOffset = 0; mutable.value = SearchState() }
    override fun onCleared() { cancel() }
}
