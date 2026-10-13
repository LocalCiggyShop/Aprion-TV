package org.privateview.quietscreen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.privateview.quietscreen.core.*

class ContinueWatching(context: Context) {
    private val prefs = context.getSharedPreferences("continue_watching", Context.MODE_PRIVATE)
    private var state = read()
    fun videos() = state.videos
    fun finished() = state.finished
    fun position(id: VideoId) = state.videos.firstOrNull { it.video.id == id }?.positionMs ?: 0L
    fun genre(id: VideoId) = state.videos.firstOrNull { it.video.id == id }?.genre
    fun clear() { state = ContinueWatchingState(); prefs.edit().clear().apply() }
    fun record(video: VideoSummary, position: Long, duration: Long, ended: Boolean, genre: Genre?) {
        // Do not store URLs: thumbnails are reconstructed from validated public IDs.
        val safe = video.copy(title = video.title.take(200), channel = video.channel.take(120), thumbnail = null,
            durationSeconds = duration.takeIf { it > 0 }?.div(1000) ?: video.durationSeconds)
        val next = state.record(safe, position, duration, ended, genre)
        if (next == state) return
        state = next
        val videos = JSONArray()
        next.videos.forEach { entry -> videos.put(JSONObject().put("id", entry.video.id.value)
            .put("title", entry.video.title).put("channel", entry.video.channel).put("channelId", entry.video.channelId ?: "").put("position", entry.positionMs)
            .put("duration", entry.durationMs).put("genre", entry.genre?.name ?: "")) }
        prefs.edit().putString("records", JSONObject().put("videos", videos).put("finished", JSONArray(next.finished)).toString()).apply()
    }
    private fun read(): ContinueWatchingState = runCatching {
        val raw = prefs.getString("records", null) ?: return@runCatching ContinueWatchingState()
        require(raw.length <= 64 * 1024)
        val root = JSONObject(raw)
        val done = root.optJSONArray("finished") ?: JSONArray()
        val finished = (0 until minOf(done.length(), ContinueWatchingState.MAX_FINISHED)).mapNotNull {
            runCatching { VideoId(done.getString(it)).value }.getOrNull()
        }.distinct()
        val list = root.optJSONArray("videos") ?: JSONArray()
        val videos = (0 until minOf(list.length(), ContinueWatchingState.MAX_VIDEOS)).mapNotNull { index -> runCatching {
            val item = list.getJSONObject(index)
            val id = VideoId(item.getString("id"))
            val duration = item.getLong("duration"); val position = item.getLong("position")
            require(duration in 1..ContinueWatchingState.MAX_DURATION_MS && position in 1_000 until duration && id.value !in finished)
            UnfinishedVideo(VideoSummary(id, item.getString("title").take(200), item.getString("channel").take(120), null,
                duration / 1000, channelId = item.optString("channelId").takeIf { it.matches(Regex("UC[A-Za-z0-9_-]{22}")) }),
                position, duration, Genre.entries.firstOrNull { it.name == item.optString("genre") })
        }.getOrNull() }.distinctBy { it.video.id }
        ContinueWatchingState(videos, finished)
    }.getOrDefault(ContinueWatchingState())
}
