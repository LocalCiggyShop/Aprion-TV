package org.privateview.quietscreen.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privateview.quietscreen.core.*

data class BrowseRow(val genre: Genre?, val videos: List<VideoSummary> = emptyList(), val cursor: String? = null,
    val loaded: Boolean = false, val busy: Boolean = false, val error: ErrorCode? = null,
    val resumes: Map<String, Long> = emptyMap()) {
    val key get() = genre?.name ?: "continue"
    val label get() = genre?.label ?: "Continue watching"
    val hint get() = genre?.hint ?: "Pick up where you left off"
}
data class BrowseFocus(val genre: Genre?, val videoId: String? = null, val action: Boolean = false, val column: Int = 0)
data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

class BrowseViewModel(private val catalog: VideoCatalog) : ViewModel() {
    private val mutable = MutableStateFlow(Genre.entries.map { BrowseRow(it) })
    val rows = mutable.asStateFlow()
    private val queue = Mutex()
    private val jobs = mutableMapOf<Genre, Job>()
    private var generation = 0
    var focus: BrowseFocus? = null
    var contentFocused = false
    var vertical = ScrollPosition()
    val horizontal = mutableMapOf<Genre?, ScrollPosition>()
    var personalized = false; private set
    fun arrange(profile: GenrePreferences, enabled: Boolean) {
        personalized = enabled && profile.personalized
        val byGenre = mutable.value.associateBy { it.genre }
        val ordered = (if (enabled) profile.order() else Genre.entries).map { byGenre.getValue(it) }.toMutableList()
        byGenre[null]?.let { ordered.add(1, it) }
        mutable.value = ordered
    }
    fun continueWatching(entries: List<UnfinishedVideo>) {
        val genres = mutable.value.filter { it.genre != null }.toMutableList()
        if (entries.isNotEmpty()) genres.add(1, BrowseRow(null, entries.map {
            it.video.copy(thumbnail = "https://i.ytimg.com/vi/${it.video.id.value}/hqdefault.jpg")
        }, loaded = true, resumes = entries.associate { it.video.id.value to it.positionMs }))
        mutable.value = genres
        val anchor = focus
        if (anchor != null && anchor.genre == null && entries.none { it.video.id.value == anchor.videoId }) {
            focus = entries.firstOrNull()?.let { BrowseFocus(null, it.video.id.value) } ?: BrowseFocus(genres.first().genre)
            horizontal.remove(null)
        }
    }
    private fun update(genre: Genre, transform: (BrowseRow) -> BrowseRow) {
        mutable.value = mutable.value.map { if (it.genre == genre) transform(it) else it }
    }
    fun load(genre: Genre?, more: Boolean = false, retry: Boolean = false) {
        if (genre == null) return
        val row = mutable.value.first { it.genre == genre }
        if (row.busy || (more && row.cursor == null) || (!more && row.loaded && !retry)) return
        val token = if (more) row.cursor else null
        val current = generation
        update(genre) { it.copy(busy = true, error = null) }
        jobs[genre] = viewModelScope.launch {
            try {
                // One foreground catalog request at a time, including lazy row loads.
                val page = queue.withLock { ensureActive(); catalog.search(genre.query, token) }
                ensureActive()
                if (generation != current) return@launch
                update(genre) { existing ->
                    val previous = if (more) existing.videos else emptyList()
                    val videos = (previous + page.videos).distinctBy { video -> video.id }.take(60)
                    existing.copy(videos = videos, cursor = page.cursor.takeIf { videos.size < 60 && existing.cursor != page.cursor },
                        loaded = true, busy = false, error = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == generation) {
                    val error = (e as? AppException)?.code ?: ErrorCode.UPSTREAM_CHANGED
                    update(genre) { it.copy(busy = false, loaded = true, error = error,
                        // Expired/evicted opaque cursors and changed provider pages can be
                        // recovered by a fresh row request, with existing cards kept visible.
                        cursor = it.cursor.takeUnless { more && error == ErrorCode.UPSTREAM_CHANGED }) }
                }
            }
        }
    }
    fun cancel() {
        generation++; jobs.values.forEach { it.cancel() }; jobs.clear(); catalog.cancelPending()
        mutable.value = mutable.value.map { it.copy(busy = false) }
    }
    fun clear() {
        cancel(); focus = null; contentFocused = false; vertical = ScrollPosition(); horizontal.clear()
        mutable.value = mutable.value.filter { it.genre != null }.map { BrowseRow(it.genre) }
    }
    override fun onCleared() { cancel() }
}
