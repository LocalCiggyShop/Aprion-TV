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
    val resumes: Map<String, Long> = emptyMap(), val recommended: Boolean = false,
    val origins: Map<String, Genre> = emptyMap(), val learned: Boolean = false) {
    val key get() = if (recommended) "recommended" else genre?.name ?: "continue"
    val label get() = if (recommended) "Recommended" else genre?.label ?: "Continue watching"
    val hint get() = if (recommended) { if (learned) "More of what you enjoy · Room to explore" else "A little of everything" }
        else genre?.hint ?: "Pick up where you left off"
    val canLoad get() = recommended || genre != null
}
data class BrowseFocus(val genre: Genre?, val videoId: String? = null, val action: Boolean = false, val column: Int = 0,
    val rowKey: String = genre?.name ?: "continue")
data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

class BrowseViewModel(private val catalog: VideoCatalog) : ViewModel() {
    private val mutable = MutableStateFlow(listOf(BrowseRow(null, recommended = true)) + Genre.entries.map { BrowseRow(it) })
    val rows = mutable.asStateFlow()
    private val queue = Mutex()
    private val jobs = mutableMapOf<Genre, Job>()
    private var recommendedJob: Job? = null
    private var profile = GenrePreferences()
    private var learning = true
    private var finished = emptySet<String>()
    private var generation = 0
    var focus: BrowseFocus? = null
    var contentFocused = false
    var vertical = ScrollPosition()
    val horizontal = mutableMapOf<String, ScrollPosition>()
    var personalized = false; private set
    fun arrange(profile: GenrePreferences, enabled: Boolean, completed: List<String> = emptyList()) {
        this.profile = profile; learning = enabled; finished = completed.take(100).toSet()
        personalized = enabled && profile.personalized
        rebuildRecommended()
    }
    private fun rebuildRecommended() {
        val sources = mutable.value.filter { it.genre != null }.associate { it.genre!! to it.videos }
        val selected = RecommendedVideos.select(profile, learning, sources, finished)
        var cards = selected.map { it.video }
        val anchor = focus?.takeIf { it.rowKey == "recommended" && !it.action }
        // Keep the exact return target at its prior column while the other cards adapt.
        val pinned = mutable.value.first().videos.firstOrNull { it.id.value == anchor?.videoId }
        if (pinned != null && anchor != null) {
            val revised = cards.filterNot { it.id == pinned.id }.toMutableList()
            revised.add(anchor.column.coerceIn(0, revised.size), pinned)
            cards = revised.take(24)
        }
        val origins = selected.associate { it.video.id.value to it.genre }.toMutableMap()
        if (pinned != null) mutable.value.first().origins[pinned.id.value]?.let { origins[pinned.id.value] = it }
        val ready = profile.recommendationSources(learning).all { genre -> mutable.value.first { it.genre == genre }.loaded }
        val error = profile.recommendationSources(learning).firstNotNullOfOrNull { genre -> mutable.value.first { it.genre == genre }.error }
        mutable.value = mutable.value.map { if (it.recommended) it.copy(videos = cards, origins = origins,
            loaded = ready, learned = personalized, error = error) else it }
    }
    fun continueWatching(entries: List<UnfinishedVideo>) {
        val genres = mutable.value.filter { it.key != "continue" }.toMutableList()
        if (entries.isNotEmpty()) genres.add(1, BrowseRow(null, entries.map {
            it.video.copy(thumbnail = "https://i.ytimg.com/vi/${it.video.id.value}/hqdefault.jpg")
        }, loaded = true, resumes = entries.associate { it.video.id.value to it.positionMs }))
        mutable.value = genres
        val anchor = focus
        if (anchor != null && anchor.rowKey == "continue" && entries.none { it.video.id.value == anchor.videoId }) {
            focus = entries.firstOrNull()?.let { BrowseFocus(null, it.video.id.value) } ?: BrowseFocus(null, rowKey = "recommended")
            horizontal.remove("continue")
        }
    }
    private fun update(genre: Genre, transform: (BrowseRow) -> BrowseRow) {
        mutable.value = mutable.value.map { if (it.genre == genre) transform(it) else it }
        rebuildRecommended()
    }
    fun loadRow(key: String, more: Boolean = false, retry: Boolean = false) {
        if (key != "recommended") { load(Genre.entries.firstOrNull { it.name == key }, more, retry); return }
        if (recommendedJob?.isActive == true || (mutable.value.first().loaded && !retry)) return
        val refresh = retry && mutable.value.first().error == null
        val current = generation
        mutable.value = mutable.value.map { if (it.recommended) it.copy(busy = true, error = null) else it }
        recommendedJob = viewModelScope.launch {
            try {
                for (genre in profile.recommendationSources(learning)) {
                    ensureActive()
                    val existingJob = jobs[genre]
                    if (existingJob?.isActive == true) existingJob.join()
                    val row = mutable.value.first { it.genre == genre }
                    if (!row.loaded || refresh || (retry && row.error != null)) {
                        load(genre, retry = retry)
                        jobs[genre]?.join()
                    }
                }
                ensureActive()
                if (current != generation) return@launch
                val errors = profile.recommendationSources(learning).mapNotNull { genre -> mutable.value.first { it.genre == genre }.error }
                mutable.value = mutable.value.map { if (it.recommended) it.copy(busy = false, loaded = true,
                    error = errors.firstOrNull()) else it }
            } finally {
                if (current == generation) mutable.value = mutable.value.map { if (it.recommended) it.copy(busy = false) else it }
            }
        }
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
        generation++; recommendedJob?.cancel(); jobs.values.forEach { it.cancel() }; jobs.clear(); catalog.cancelPending()
        mutable.value = mutable.value.map { it.copy(busy = false) }
    }
    fun clear() {
        cancel(); focus = null; contentFocused = false; vertical = ScrollPosition(); horizontal.clear()
        mutable.value = listOf(BrowseRow(null, recommended = true)) + Genre.entries.map { BrowseRow(it) }
    }
    override fun onCleared() { cancel() }
}
