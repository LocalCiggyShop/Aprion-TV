package org.privateview.quietscreen

import android.content.Context
import org.privateview.quietscreen.core.Genre
import org.privateview.quietscreen.core.GenrePreferences

/** Private bounded aggregates, excluded from Android backup by the application policy. */
class Personalization(context: Context) {
    private val preferences = context.getSharedPreferences("genre_preferences", Context.MODE_PRIVATE)
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
    fun reset() {
        val keepEnabled = enabled
        preferences.edit().clear().putBoolean("enabled", keepEnabled).apply()
    }
}
