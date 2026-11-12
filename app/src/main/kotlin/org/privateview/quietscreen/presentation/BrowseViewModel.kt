package org.privateview.quietscreen.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privateview.quietscreen.core.*

data class BrowseRow(val genre: Genre?, val videos: List<VideoSummary> = emptyList(), val cursor: String? = null,
    val loaded: Boolean = false, val busy: Boolean = false, val error: ErrorCode? = null,
    val resumes: Map<String, Long> = emptyMap(), val recommended: Boolean = false,
    val origins: Map<String, Genre> = emptyMap(), val learned: Boolean = false, val contextual: Boolean = false) {
    val key get() = if (recommended) "recommended" else genre?.name ?: "continue"
    val label get() = if (recommended) "Recommended" else genre?.label ?: "Continue watching"
    val hint get() = if (contextual) "Related to this video" else if (recommended) { if (learned) "More of what you enjoy · Room to explore" else "A little of everything" }
        else genre?.hint ?: "Pick up where you left off"
    val canLoad get() = recommended || genre != null
}
data class BrowseFocus(val genre: Genre?, val videoId: String? = null, val action: Boolean = false, val column: Int = 0,
    val rowKey: String = genre?.name ?: "continue")
data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

class BrowseViewModel(private val catalog: VideoCatalog,
    private val exposure: (List<VideoSummary>) -> Unit = {}, private val ranked: () -> Unit = {}) : ViewModel() {
    private val mutable = MutableStateFlow(listOf(BrowseRow(null, recommended = true)) + Genre.entries.map { BrowseRow(it) })
    val rows = mutable.asStateFlow()
    private val playerMutable = MutableStateFlow(BrowseRow(null, recommended = true))
    val playerRow = playerMutable.asStateFlow()
    private val queue = Mutex()
    private val jobs = mutableMapOf<Genre, Job>()
    private var recommendedJob: Job? = null
    private var profile = GenrePreferences()
    private var interests = InterestProfile()
    private var topicCache: Pair<String, List<VideoSummary>>? = null
    private var topicLoaded = false
    private var topicAttempted: String? = null
    private var contextVideo: VideoSummary? = null
    private var related = emptyList<VideoSummary>()
    private var rankingJob: Job? = null
    private val rankingMutex = Mutex()
    private val revision = AtomicLong()
    private var rankingPending = false
    private var homeActive = false
    private var allowReorder = true
    private var loadRevision = 0L
    private var preparedPool: RecommendationPool? = null
    private var preparedInputs: Triple<Map<Genre, List<VideoSummary>>, List<VideoSummary>, List<VideoSummary>>? = null
    private var topicRotation = 0
    private data class SourceKey(val genres: List<Genre>, val topic: String?)
    private var attemptedKey: SourceKey? = null
    private var loadKey: SourceKey? = null
    private val impressions = RecommendationImpressions()
    private val exposedIds = mutableSetOf<String>()
    private var rotation = ((System.currentTimeMillis() / 86_400_000) % Genre.entries.size).toInt()
    private var learning = true
    private var finished = emptySet<String>()
    private var generation = 0
    var focus: BrowseFocus? = null
    var contentFocused = false
    var vertical = ScrollPosition()
    val horizontal = mutableMapOf<String, ScrollPosition>()
    var personalized = false; private set
    fun arrange(profile: GenrePreferences, enabled: Boolean, completed: List<String> = emptyList(), interests: InterestProfile = InterestProfile()) {
        val oldKey = sourceKey()
        val nextFinished = completed.take(100).toSet()
        val changed = this.profile.scores != profile.scores || learning != enabled || finished != nextFinished ||
            this.interests !== interests
        this.profile = profile; this.interests = interests; learning = enabled; finished = nextFinished
        personalized = enabled && (profile.personalized || interests.personalized)
        if (changed) rebuildRecommended()
        if (oldKey != sourceKey()) {
            recommendedJob?.cancel(); recommendedJob = null; loadKey = null
            mutable.value = mutable.value.map { if (it.recommended) it.copy(loaded = false, busy = false) else it }
            if (homeActive) loadRow("recommended")
        }
    }
    private fun sourceKey(): SourceKey {
        val topic = interests.discoveryQuery(topicRotation).takeIf { learning }
        return SourceKey(profile.recommendationSources(learning, rotation).take(if (topic != null) 3 else 4), topic)
    }
    fun enterHome() {
        homeActive = true; allowReorder = true; impressions.clear(); exposedIds.clear()
        rebuildRecommended(); loadRow("recommended")
    }
    fun leaveHome() { homeActive = false; impressions.update(emptySet(), 0) }
    fun exposeVisible(ids: Set<String>, nowMs: Long) {
        if (!learning || !homeActive) return
        recordExposures(impressions.update(ids, nowMs))
    }
    fun selected(video: VideoSummary) {
        if (learning && homeActive && mutable.value.first().videos.any { it.id == video.id } && impressions.selected(video.id.value))
            recordExposures(setOf(video.id.value))
    }
    private fun recordExposures(ids: Set<String>) {
        val fresh = ids.filter { exposedIds.size < 96 && exposedIds.add(it) }.toSet()
        if (fresh.isNotEmpty()) {
            val videos = mutable.value.first().videos.filter { it.id.value in fresh }
            interests = interests.exposed(videos) // Next visit uses these hints; no self-triggered rerank.
            exposure(videos)
        }
    }
    suspend fun awaitRanking() { rankingJob?.join() }
    fun context(video: VideoSummary, related: List<VideoSummary> = emptyList()) {
        val bounded = related.take(32)
        if (contextVideo == video && this.related == bounded) return
        contextVideo = video; this.related = bounded; rebuildRecommended()
    }
    fun clearContext(id: VideoId) {
        if (contextVideo?.id == id) {
            contextVideo = null; related = emptyList(); playerMutable.value = BrowseRow(null, recommended = true)
            rebuildRecommended()
        }
    }
    private class ObsoleteRanking : RuntimeException()
    private fun rebuildRecommended() {
        revision.incrementAndGet(); rankingPending = true
        if (rankingJob?.isActive == true) return
        rankingJob = viewModelScope.launch {
            while (rankingPending) {
                rankingPending = false
                val version = revision.get()
                val catalogs = mutable.value.filter { it.genre != null }.associate { it.genre!! to it.videos }
                val snapshotProfile = profile; val snapshotInterests = interests; val snapshotLearning = learning
                val snapshotFinished = finished; val snapshotRotation = rotation
                val snapshotContext = contextVideo; val snapshotRelated = related
                val extras = topicCache?.takeIf { it.first == sourceKey().topic }?.second.orEmpty()
                val selections = try {
                    withContext(Dispatchers.Default) {
                        rankingMutex.withLock {
                            val job = currentCoroutineContext()
                            val check = { job.ensureActive(); if (version != revision.get()) throw ObsoleteRanking() }
                            val inputs = Triple(catalogs, extras, snapshotRelated)
                            val pool = if (preparedInputs == inputs) preparedPool!! else {
                                RecommendationPool(catalogs, extras, snapshotRelated, check).also {
                                    preparedPool = it; preparedInputs = inputs
                                }
                            }
                            RecommendedVideos.select(snapshotProfile, snapshotLearning, pool, snapshotFinished,
                                interests = snapshotInterests, rotation = snapshotRotation, check = check) to
                                snapshotContext?.let { RecommendedVideos.select(snapshotProfile, snapshotLearning, pool,
                                    snapshotFinished, limit = 12, interests = snapshotInterests, context = it,
                                    rotation = snapshotRotation, check = check) }
                        }
                    }
                } catch (_: ObsoleteRanking) { continue }
                ensureActive()
                if (version != revision.get()) continue
                ranked()
                val selected = selections.first
                var cards = selected.map { it.video }
                val previous = mutable.value.first()
                val eligible = { video: VideoSummary -> snapshotInterests.permits(video) && video.id.value !in snapshotFinished && !video.live }
                if (homeActive && contentFocused && !allowReorder) {
                    // Retain the visible visit order; remove ineligible entries immediately and append new candidates.
                    cards = (previous.videos.filter(eligible) + cards).distinctBy { it.id }.take(24)
                } else {
                    val anchor = focus?.takeIf { it.rowKey == "recommended" && !it.action }
                    val pinned = previous.videos.firstOrNull { it.id.value == anchor?.videoId && eligible(it) }
                    if (pinned != null && anchor != null) {
                        val revised = cards.filterNot { it.id == pinned.id }.toMutableList()
                        revised.add(anchor.column.coerceIn(0, revised.size), pinned); cards = revised.take(24)
                    }
                }
                allowReorder = false
                val origins = previous.origins + selected.mapNotNull { item -> item.genre?.let { item.video.id.value to it } }.toMap()
                val key = sourceKey()
                val ready = attemptedKey == key
                val error = key.genres.firstNotNullOfOrNull { genre -> mutable.value.first { it.genre == genre }.error }
                mutable.value = mutable.value.map { if (it.recommended) it.copy(videos = cards,
                    origins = origins.filterKeys { id -> cards.any { it.id.value == id } }, loaded = ready,
                    learned = personalized, error = error) else it }
                selections.second?.takeIf { contextVideo?.id == snapshotContext?.id }?.let { player ->
                    playerMutable.value = BrowseRow(null, player.map { it.video }, loaded = ready || snapshotRelated.isNotEmpty(),
                        busy = previous.busy && snapshotRelated.isEmpty(), error = error.takeIf { player.isEmpty() },
                        recommended = true, origins = player.mapNotNull { item -> item.genre?.let { item.video.id.value to it } }.toMap(),
                        learned = personalized, contextual = true)
                }
            }
        }
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
        val before = mutable.value.first { it.genre == genre }
        mutable.value = mutable.value.map { if (it.genre == genre) transform(it) else it }
        if (before.videos != mutable.value.first { it.genre == genre }.videos) rebuildRecommended()
    }
    fun loadRow(key: String, more: Boolean = false, retry: Boolean = false) {
        if (key != "recommended") { load(Genre.entries.firstOrNull { it.name == key }, more, retry); return }
        if (retry && recommendedJob?.isActive != true && mutable.value.first().error == null) {
            rotation = (rotation + 1) % Genre.entries.size; topicRotation++
            allowReorder = true; contentFocused = false // Explicit refresh is a stable publication boundary.
        }
        val sources = sourceKey()
        if (recommendedJob?.isActive == true && loadKey == sources) return
        if (attemptedKey == sources && !retry) return
        recommendedJob?.cancel()
        loadKey = sources
        val current = generation
        val loadVersion = ++loadRevision
        mutable.value = mutable.value.map { if (it.recommended) it.copy(busy = true, error = null) else it }
        recommendedJob = viewModelScope.launch {
            try {
                for (genre in sources.genres) {
                    ensureActive()
                    jobs[genre]?.takeIf { it.isActive }?.join()
                    val row = mutable.value.first { it.genre == genre }
                    if (!row.loaded || (retry && row.error != null)) { load(genre, retry = retry); jobs[genre]?.join() }
                }
                val query = sources.topic
                if (query != null && (topicCache?.first != query || retry) &&
                    (topicAttempted != query || retry)) {
                    try {
                        val page = queue.withLock { ensureActive(); catalog.search(query, null) }
                        ensureActive()
                        if (current != generation || sources != sourceKey()) return@launch
                        topicCache = query to page.videos.take(30)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep eligible cached recommendations; retry is explicit. */ }
                    topicLoaded = true; topicAttempted = query
                }
                ensureActive()
                if (current != generation || sources != sourceKey()) return@launch
                attemptedKey = sources
                rebuildRecommended(); rankingJob?.join()
            } finally {
                if (current == generation && loadVersion == loadRevision && loadKey == sources) {
                    mutable.value = mutable.value.map { if (it.recommended) it.copy(busy = false) else it }
                    playerMutable.value = playerMutable.value.copy(busy = false)
                }
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
        generation++; revision.incrementAndGet(); rankingJob?.cancel(); rankingPending = false; recommendedJob?.cancel(); jobs.values.forEach { it.cancel() }; jobs.clear(); catalog.cancelPending()
        mutable.value = mutable.value.map { it.copy(busy = false) }
        playerMutable.value = playerMutable.value.copy(busy = false)
    }
    fun clear() {
        cancel(); rankingJob?.cancel(); focus = null; contentFocused = false; vertical = ScrollPosition(); horizontal.clear()
        mutable.value = listOf(BrowseRow(null, recommended = true)) + Genre.entries.map { BrowseRow(it) }
        attemptedKey = null; loadKey = null; preparedPool = null; preparedInputs = null; impressions.clear(); exposedIds.clear()
        topicCache = null; topicLoaded = false; topicAttempted = null; contextVideo = null; related = emptyList(); playerMutable.value = BrowseRow(null, recommended = true)
    }
    override fun onCleared() { cancel() }
}
