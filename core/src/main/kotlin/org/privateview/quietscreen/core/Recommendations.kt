package org.privateview.quietscreen.core

/** Fixed, public discovery queries. No watch identifiers or user queries enter this model. */
enum class Genre(val label: String, val query: String, val hint: String) {
    SCIENCE("Science & technology", "science explained space discoveries", "Ideas worth exploring"),
    NATURE("Nature & wildlife", "nature wildlife documentary", "A wider world"),
    MUSIC("Music", "live music performances", "Find your next favourite sound"),
    COOKING("Food & cooking", "cooking recipes from around the world", "Something new to try"),
    GAMING("Gaming", "video game gameplay reviews", "New worlds to discover"),
    TRAVEL("Travel & culture", "travel culture walking tour", "Places and perspectives"),
    ARTS("Arts & making", "art craft design tutorials", "Make room for creativity"),
    SPORT("Sport & movement", "sports highlights fitness", "Get into the action")
}

/** Eight finite scores; repeated long sessions cannot grow storage or remove discovery genres. */
class GenrePreferences(values: Map<Genre, Double> = emptyMap()) {
    val scores: Map<Genre, Double> = Genre.entries.associateWith {
        values[it]?.takeIf { value -> value.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
    }
    val personalized get() = scores.values.any { it > 0.0 }
    fun order(): List<Genre> = Genre.entries.sortedByDescending { scores.getValue(it) }
    /** Limit discovery requests while retaining both familiar interests and exploration. */
    fun recommendationSources(enabled: Boolean, rotation: Int = 0): List<Genre> {
        val rotated = Genre.entries.indices.map { Genre.entries[Math.floorMod(it + rotation, Genre.entries.size)] }
        if (!enabled || !personalized) return rotated.take(4)
        val preferred = order().filter { scores.getValue(it) > 0.0 }.take(2)
        return (preferred + rotated.filterNot { it in preferred }).take(4)
    }
    fun watched(genre: Genre, watchedMs: Long, durationMs: Long): GenrePreferences =
        watched(genre, WatchOutcome(watchedMs, durationMs))

    fun watched(genre: Genre, outcome: WatchOutcome, weight: Double = 1.0): GenrePreferences {
        val gain = outcome.gain * weight.coerceIn(0.0, 1.0) * 2.0
        if (gain <= 0) return this
        return GenrePreferences(scores.mapValues { (key, score) -> score * .97 + if (key == genre) gain else 0.0 })
    }

}

/** Conservative local evidence, derived only from the currently opened public video's metadata. */
object GenreClassifier {
    fun providerCategory(category: String): Genre? = when (category.take(64).trim()) {
        "Music" -> Genre.MUSIC
        "Gaming" -> Genre.GAMING
        "Science & Technology" -> Genre.SCIENCE
        "Sports" -> Genre.SPORT
        "Travel & Events" -> Genre.TRAVEL
        else -> null
    }
    private val separators = Regex("[^a-z0-9]+")
    private val phrases = mapOf(
        Genre.SCIENCE to listOf("science", "physics", "astronomy", "astrophysics", "chemistry", "space telescope", "quantum", "veritasium", "kurzgesagt", "smarter every day"),
        Genre.NATURE to listOf("wildlife", "nature documentary", "animal documentary", "birdwatching", "ocean documentary", "planet earth", "animal behaviour"),
        Genre.MUSIC to listOf("music", "official music video", "concert", "piano cover", "guitar cover", "song cover", "orchestra", "symphony", "vevo"),
        Genre.COOKING to listOf("cooking", "recipe", "recipes", "baking", "chef", "food preparation", "gordon ramsay"),
        Genre.GAMING to listOf("gaming", "gameplay", "video game", "video games", "minecraft", "fortnite", "roblox", "elden ring", "zelda"),
        Genre.TRAVEL to listOf("travel vlog", "travel guide", "walking tour", "city tour", "travel documentary", "backpacking trip"),
        Genre.ARTS to listOf("drawing", "painting tutorial", "watercolour", "watercolor", "pottery", "sculpture", "crochet", "knitting", "origami", "woodworking"),
        Genre.SPORT to listOf("sports", "sport highlights", "football", "soccer", "basketball", "tennis", "workout", "fitness", "nba", "nfl", "yoga")
    ).mapValues { (_, words) -> words.map { Regex("(?:^| )${Regex.escape(it)}(?: |$)") } }

    fun classify(video: VideoSummary): Genre? {
        val text = (video.title.take(512) + " " + video.channel.take(160))
            .lowercase(java.util.Locale.ROOT).replace(separators, " ").trim()
        val matches = phrases.mapValues { (_, words) -> words.count { it.containsMatchIn(text) } }
        val strongest = matches.values.maxOrNull() ?: return null
        return matches.filterValues { it == strongest && it > 0 }.keys.singleOrNull()
    }
}

data class RecommendedVideo(val video: VideoSummary, val genre: Genre?)

/** Immutable bounded features, shared by the Home and Player passes of one ranking revision. */
class RecommendationPool(candidates: Map<Genre, List<VideoSummary>>, extras: List<VideoSummary> = emptyList(),
    related: List<VideoSummary> = emptyList(), check: () -> Unit = {}) {
    data class Item(val video: VideoSummary, val genre: Genre?, val topics: Set<String>, val channel: String,
        val name: String, val related: Boolean, val index: Int)
    val items: List<Item>
    init {
        val unique = linkedMapOf<String, Pair<VideoSummary, Genre?>>()
        Genre.entries.forEach { genre -> candidates[genre].orEmpty().take(60).forEach { video ->
            check(); unique.putIfAbsent(video.id.value, video to genre)
        } }
        extras.take(30).forEach { unique.putIfAbsent(it.id.value, it to null) }
        val relatedIds = related.take(32).map { it.id.value }.toSet()
        related.take(32).forEach { unique.putIfAbsent(it.id.value, it to null) }
        items = unique.values.mapIndexed { index, (video, source) ->
            check()
            Item(video, GenreClassifier.classify(video) ?: source, InterestMetadata.topics(video.title),
                InterestMetadata.channel(video).ifBlank { "video:" + video.id.value }, InterestMetadata.channelName(video),
                video.id.value in relatedIds, index)
        }
    }
}

/** Comparable bounded scoring, followed by a small greedy diversity pass. */
object RecommendedVideos {
    fun select(profile: GenrePreferences, enabled: Boolean, candidates: Map<Genre, List<VideoSummary>>,
        excluded: Set<String>, limit: Int = 24, interests: InterestProfile = InterestProfile(),
        extras: List<VideoSummary> = emptyList(), context: VideoSummary? = null, rotation: Int = 0): List<RecommendedVideo> =
        select(profile, enabled, RecommendationPool(candidates, if (context == null) extras else emptyList(),
            if (context != null) extras else emptyList()), excluded, limit.coerceIn(0, 24), interests, context, rotation)

    fun select(profile: GenrePreferences, enabled: Boolean, pool: RecommendationPool, excluded: Set<String>,
        limit: Int = 24, interests: InterestProfile = InterestProfile(), context: VideoSummary? = null,
        rotation: Int = 0, nowMs: Long = System.currentTimeMillis(), check: () -> Unit = {}): List<RecommendedVideo> {
        val maximum = limit.coerceIn(0, 60)
        val contextTopics = context?.let { InterestMetadata.topics(it.title) }.orEmpty()
        val contextChannel = context?.let(InterestMetadata::channel)
        val contextName = context?.let(InterestMetadata::channelName)
        val seen = interests.shown.toSet()
        data class Scored(val item: RecommendationPool.Item, val score: Double, val discovery: Boolean, val tie: Int)
        val scored = pool.items.filter { item ->
            check(); !item.video.live && item.video.id != context?.id && item.video.id.value !in excluded && interests.permits(item.video)
        }.map { item ->
            check()
            val topic = if (enabled && item.topics.isNotEmpty())
                item.topics.sumOf { kotlin.math.sqrt((interests.topics[it] ?: 0.0) / 100.0) } / item.topics.size else 0.0
            val channel = if (enabled) kotlin.math.sqrt(interests.channelAffinity(item.video) / 100.0) else 0.0
            val genre = if (enabled) kotlin.math.sqrt((profile.scores[item.genre] ?: 0.0) / 100.0) else 0.0
            val age = item.video.uploadedAtMs?.takeIf { it in 1..nowMs }?.let { (nowMs - it) / 86_400_000.0 }
            val fresh = age?.let { 1.0 / (1.0 + it / 30.0) } ?: 0.0
            val affinity = .45 * topic + .30 * channel + .20 * genre
            val sameChannel = contextChannel?.isNotBlank() == true && (item.channel == contextChannel ||
                (item.name.isNotBlank() && item.name == contextName &&
                    (InterestMetadata.stableChannel(context) == null || InterestMetadata.stableChannel(item.video) == null)))
            val overlap = if (contextTopics.isEmpty()) 0.0 else item.topics.count { it in contextTopics }.toDouble() / contextTopics.size
            val contextual = .45 * overlap + (if (sameChannel) .25 else 0.0) + (if (item.related) .20 else 0.0)
            val score = (if (context == null) affinity else contextual + affinity * .10) + .05 * fresh -
                (if (enabled && item.video.id.value in seen) .10 else 0.0)
            Scored(item, score, channel == 0.0 && (topic > 0.0 || genre == 0.0), Math.floorMod(item.index - rotation, pool.items.size.coerceAtLeast(1)))
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.tie }).toMutableList()
        val channels = mutableMapOf<String, Int>()
        val genres = mutableMapOf<Genre?, Int>()
        val cold = !enabled || (!profile.personalized && !interests.personalized)
        val result = mutableListOf<RecommendedVideo>()
        var previous: String? = null
        while (result.size < maximum && scored.isNotEmpty()) {
            check()
            val cap = if (result.size < 8) 2 else 3
            val explore = !cold && context == null && result.size % 4 == 3
            // Relax source/diversity preferences in a sparse pool, never eligibility rules.
            var options = scored.filter { (channels[it.item.channel] ?: 0) < cap && it.item.channel != previous }
            if (options.isEmpty()) options = scored.filter { (channels[it.item.channel] ?: 0) < cap }
            if (options.isEmpty()) options = scored
            if (explore) options.filter { it.discovery }.takeIf { it.isNotEmpty() }?.let { options = it }
            val chosen = if (cold && context == null) options.maxBy { it.score + .20 / (1 + (genres[it.item.genre] ?: 0)) }
                else if (context != null) options.first()
                else options.maxBy { it.score - .03 * (genres[it.item.genre] ?: 0) / (1 + result.size) }
            scored.remove(chosen)
            val item = chosen.item
            result += RecommendedVideo(item.video, item.genre)
            channels[item.channel] = (channels[item.channel] ?: 0) + 1
            genres[item.genre] = (genres[item.genre] ?: 0) + 1
            previous = item.channel
        }
        return result
    }
}

/** Monotonic playback sampling: pauses, seeks and long observation gaps earn no watch time. */
class WatchTime {
    var watchedMs: Long = 0; private set
    private var lastTime: Long? = null
    private var lastPosition = 0L
    private var wasPlaying = false
    fun discontinuity() { lastTime = null; wasPlaying = false }
    fun sample(nowMs: Long, positionMs: Long, playing: Boolean) {
        val elapsed = lastTime?.let { nowMs - it } ?: 0
        val moved = positionMs - lastPosition
        if (wasPlaying && elapsed in 1..2_000 && moved in 1..(elapsed * 2 + 250)) {
            watchedMs = (watchedMs + minOf(elapsed, moved)).coerceAtMost(3_600_000)
        }
        lastTime = nowMs; lastPosition = positionMs; wasPlaying = playing
    }
}
