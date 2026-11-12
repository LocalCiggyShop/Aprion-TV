package org.privateview.quietscreen

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privateview.quietscreen.core.*
import org.json.JSONObject
import org.json.JSONArray

/** Private bounded aggregates; one coalescing writer serializes away from the UI thread. */
class Personalization(context: Context) {
    companion object {
        @Volatile private var instance: Personalization? = null
        fun get(context: Context): Personalization = instance ?: synchronized(this) {
            instance ?: Personalization(context.applicationContext).also { instance = it }
        }
    }
    private val preferences = context.getSharedPreferences("genre_preferences", Context.MODE_PRIVATE)
    private val details = context.getSharedPreferences("recommendation_interests", Context.MODE_PRIVATE)
    private var interests = readInterests()
    private var genres = GenrePreferences(Genre.entries.associateWith {
        runCatching { preferences.getFloat(it.name, 0f).toDouble() }.getOrDefault(0.0)
    })
    private val credits = SessionCredits()
    private val lock = Any()
    private var revision = 0L
    private var persistedRevision = -1L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init { scope.launch { for (request in requests) persist() } }
    fun interests() = interests
    var enabled: Boolean
        get() = runCatching { preferences.getBoolean("enabled", true) }.getOrDefault(true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
    fun profile() = genres
    fun record(genre: Genre?, watchedMs: Long, durationMs: Long) { synchronized(lock) {
        if (!enabled || genre == null) return
        val next = genres.watched(genre, watchedMs, durationMs)
        if (next !== genres) { genres = next; changed() }
    } }
    fun record(video: VideoSummary, genre: Genre?, watchedMs: Long, durationMs: Long) =
        record(video, genre, WatchOutcome(watchedMs, durationMs))
    fun record(video: VideoSummary, genre: Genre?, outcome: WatchOutcome) { synchronized(lock) {
        if (!enabled) return
        val weight = credits.weight(video.id, outcome.gain)
        if (weight <= 0) return
        if (genre != null) genres = genres.watched(genre, outcome, weight)
        interests = interests.watched(video, outcome, weight)
        changed()
    } }
    fun feedback(video: VideoSummary, action: RecommendationFeedback) { synchronized(lock) {
        interests = interests.feedback(video, action); changed()
    } }
    fun exposed(videos: List<VideoSummary>) { synchronized(lock) {
        if (enabled && videos.isNotEmpty()) { interests = interests.exposed(videos); changed() }
    } }
    private fun changed() { synchronized(lock) { revision++ }; requests.trySend(Unit) }
    private suspend fun persist() = writer.withLock {
        val snapshot = synchronized(lock) {
            if (persistedRevision == revision) return@withLock
            Triple(revision, interests, genres)
        }
        val next = snapshot.second
        val json = JSONObject().put("version", 2).put("topics", JSONObject(next.topics)).put("channels", JSONObject(next.channels))
            .put("dismissed", JSONArray(next.dismissed)).put("blocked", JSONArray(next.blocked)).put("shown", JSONArray(next.shown))
            .put("channelNames", JSONObject(next.channelNames)).put("blockedNames", JSONObject(next.blockedNames))
            .put("pairs", JSONObject(next.pairs)).toString()
        check(json.length <= 32_768) { "Bounded recommendation profile exceeded its storage limit" }
        synchronized(lock) {
            if (snapshot.first == revision) {
                details.edit().putString("profile", json).apply()
                val edit = preferences.edit()
                snapshot.third.scores.forEach { (key, value) -> edit.putFloat(key.name, value.toFloat()) }
                edit.apply()
                persistedRevision = revision
            }
        }
    }
    /** Request an off-thread flush; join only from tests, never block an Activity callback. */
    fun flush(): Job = scope.launch { persist() }
    fun close(): Job = scope.launch { persist(); scope.cancel() }
    private fun readInterests(): InterestProfile = runCatching {
        val raw = details.getString("profile", null) ?: return@runCatching InterestProfile()
        require(raw.length <= 32_768)
        val json = JSONObject(raw)
        fun scores(key: String, limit: Int): Map<String, Double> {
            val values = json.optJSONObject(key) ?: return emptyMap()
            return values.keys().asSequence().take(limit).associateWith { values.optDouble(it, 0.0) }
        }
        fun list(key: String, limit: Int): List<String> {
            val values = json.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(values.length(), limit)).map { values.optString(it).take(121) }
        }
        fun names(key: String, limit: Int): Map<String, String> {
            val values = json.optJSONObject(key) ?: return emptyMap()
            return values.keys().asSequence().take(limit).associateWith { values.optString(it).take(121) }
        }
        InterestProfile(scores("topics", 64), scores("channels", 32), list("dismissed", 80), list("blocked", 24), if (json.optInt("version", 1) >= 2) list("shown", 96) else emptyList(),
            names("channelNames", 32), names("blockedNames", 24), scores("pairs", 16))
    }.getOrDefault(InterestProfile())
    fun reset() {
        synchronized(lock) {
            revision++; persistedRevision = revision
            val keepEnabled = enabled
            genres = GenrePreferences(); interests = InterestProfile(); credits.clear()
            preferences.edit().clear().putBoolean("enabled", keepEnabled).apply()
            details.edit().clear().apply()
        }
    }
}
