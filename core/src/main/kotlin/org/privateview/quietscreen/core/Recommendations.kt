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
    fun recommendationSources(enabled: Boolean): List<Genre> {
        if (!enabled || !personalized) return Genre.entries.take(4)
        val preferred = order().filter { scores.getValue(it) > 0.0 }.take(2)
        return (preferred + Genre.entries.filterNot { it in preferred }).take(4)
    }
    fun watched(genre: Genre, watchedMs: Long, durationMs: Long): GenrePreferences {
        val watched = watchedMs.coerceIn(0, 3_600_000)
        val completion = if (durationMs > 0) (watched.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        // Repeated early exits are a modest negative signal, never a permanent exclusion.
        if (watched in 10_000 until 30_000 && durationMs >= 120_000 && completion < .1) {
            return GenrePreferences(scores.mapValues { (key, score) -> if (key == genre) score * .88 else score })
        }
        if (watched < 30_000 && !(watched >= 10_000 && completion >= .8)) return this
        // Diminishing returns prevent one very long video from overwhelming repeated
        // enjoyable sessions. Completion rewards short videos as well as long ones.
        val gain = kotlin.math.sqrt((watched / 60_000.0).coerceAtMost(10.0)) * 2.0 + completion * 4.0
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

data class RecommendedVideo(val video: VideoSummary, val genre: Genre)

/** Bounded deterministic mixing; eight genre totals suffice, with no per-video learning history. */
object RecommendedVideos {
    fun select(profile: GenrePreferences, enabled: Boolean, candidates: Map<Genre, List<VideoSummary>>,
               excluded: Set<String>, limit: Int = 24): List<RecommendedVideo> {
        val maximum = limit.coerceIn(0, 24)
        if (maximum == 0) return emptyList()
        val used = mutableSetOf<String>()
        val queues = Genre.entries.associateWith { genre ->
            candidates[genre].orEmpty().take(60).filterNot { it.live || it.id.value in excluded }
        }
        val indices = Genre.entries.associateWith { 0 }.toMutableMap()
        val learned = if (enabled) profile.order().filter { profile.scores.getValue(it) > 0 }.take(2) else emptyList()
        val debt = Genre.entries.associateWith { 0.0 }.toMutableMap()
        var exploreIndex = 0
        val result = mutableListOf<RecommendedVideo>()
        fun available(genre: Genre): Boolean {
            val videos = queues.getValue(genre)
            while (indices.getValue(genre) < videos.size &&
                (videos[indices.getValue(genre)].id.value in used || videos[indices.getValue(genre)].id.value in excluded))
                indices[genre] = indices.getValue(genre) + 1
            return indices.getValue(genre) < videos.size
        }
        while (result.size < maximum) {
            val availableGenres = Genre.entries.filter { available(it) }
            if (availableGenres.isEmpty()) break
            val exploration = availableGenres.filterNot { it in learned }
            val preferred = availableGenres.filter { it in learned }
            val genre = if (learned.isNotEmpty() && result.size % 4 == 3 && exploration.isNotEmpty()) {
                exploration[exploreIndex++ % exploration.size]
            } else {
                val pool = preferred.ifEmpty { availableGenres }
                val weights = pool.associateWith { if (it in learned) 1.0 + profile.scores.getValue(it) else 1.0 }
                pool.forEach { debt[it] = debt.getValue(it) + weights.getValue(it) }
                val chosen = pool.maxBy { debt.getValue(it) }
                debt[chosen] = debt.getValue(chosen) - weights.values.sum()
                chosen
            }
            val video = queues.getValue(genre)[indices.getValue(genre)]
            indices[genre] = indices.getValue(genre) + 1
            used += video.id.value
            result += RecommendedVideo(video, genre)
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
