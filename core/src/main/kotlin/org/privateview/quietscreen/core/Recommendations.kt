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
