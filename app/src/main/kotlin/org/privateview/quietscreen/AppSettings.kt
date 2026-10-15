package org.privateview.quietscreen

import android.content.Context

/** Playback settings; separate Personalization stores bounded genre aggregates, never video history. */
class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
    var maxHeight: Int
        get() = preferences.getInt("max_height", 1080).takeIf { it in setOf(480, 720, 1080, 2160) } ?: 1080
        set(value) { require(value in setOf(480, 720, 1080, 2160)); preferences.edit().putInt("max_height", value).apply() }
    var captions: Boolean
        get() = preferences.getBoolean("captions", false)
        set(value) { preferences.edit().putBoolean("captions", value).apply() }
    var autoplay: Boolean
        get() = preferences.getBoolean("autoplay", true)
        set(value) { preferences.edit().putBoolean("autoplay", value).apply() }
    fun reset() { preferences.edit().clear().apply() }
}
