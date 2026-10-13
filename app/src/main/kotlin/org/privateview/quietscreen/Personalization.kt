package org.privateview.quietscreen

import android.content.Context
import org.privateview.quietscreen.core.Genre
import org.privateview.quietscreen.core.GenrePreferences
import org.privateview.quietscreen.core.*
import org.json.JSONObject
import org.json.JSONArray

/** Private bounded aggregates, excluded from Android backup by the application policy. */
class Personalization(context: Context) {
    private val preferences = context.getSharedPreferences("genre_preferences", Context.MODE_PRIVATE)
    private val details = context.getSharedPreferences("recommendation_interests", Context.MODE_PRIVATE)
    private var interests = readInterests()
    fun interests() = interests
    var enabled: Boolean
        get() = runCatching { preferences.getBoolean("enabled", true) }.getOrDefault(true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
    fun profile() = GenrePreferences(Genre.entries.associateWith {
        runCatching { preferences.getFloat(it.name, 0f).toDouble() }.getOrDefault(0.0)
    })
    fun record(genre: Genre?, watchedMs: Long, durationMs: Long) {
        if (!enabled || genre == null) return
        val next = profile().watched(genre, watchedMs, durationMs)
        val edit = preferences.edit()
        next.scores.forEach { (key, value) -> edit.putFloat(key.name, value.toFloat()) }
        edit.apply()
    }
    fun record(video: VideoSummary, genre: Genre?, watchedMs: Long, durationMs: Long) {
        if (!enabled) return
        record(genre, watchedMs, durationMs)
        val next = interests.watched(video, watchedMs, durationMs)
        if (next !== interests) save(next)
    }
    fun feedback(video: VideoSummary, action: RecommendationFeedback) { save(interests.feedback(video, action)) }
    fun exposed(videos: List<VideoSummary>) {
        if (videos.isNotEmpty()) save(interests.exposed(videos))
    }
    private fun save(next: InterestProfile) {
        interests = next
        val json = JSONObject().put("topics", JSONObject(next.topics)).put("channels", JSONObject(next.channels))
            .put("dismissed", JSONArray(next.dismissed)).put("blocked", JSONArray(next.blocked)).put("shown", JSONArray(next.shown))
            .put("channelNames", JSONObject(next.channelNames)).put("blockedNames", JSONObject(next.blockedNames))
        details.edit().putString("profile", json.toString()).apply()
    }
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
        InterestProfile(scores("topics", 64), scores("channels", 32), list("dismissed", 80), list("blocked", 24), list("shown", 96),
            names("channelNames", 32), names("blockedNames", 24))
    }.getOrDefault(InterestProfile())
    fun reset() {
        val keepEnabled = enabled
        preferences.edit().clear().putBoolean("enabled", keepEnabled).apply()
        interests = InterestProfile(); details.edit().clear().apply()
    }
}
