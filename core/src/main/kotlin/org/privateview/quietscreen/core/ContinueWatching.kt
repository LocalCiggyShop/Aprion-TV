package org.privateview.quietscreen.core

data class UnfinishedVideo(val video: VideoSummary, val positionMs: Long, val durationMs: Long, val genre: Genre?)

/** Bounded resume records and completion IDs; never provider URLs or playback credentials. */
data class ContinueWatchingState(val videos: List<UnfinishedVideo> = emptyList(), val finished: List<String> = emptyList()) {
    fun record(video: VideoSummary, positionMs: Long, durationMs: Long, ended: Boolean, genre: Genre?): ContinueWatchingState {
        if (video.live || durationMs <= 0 || durationMs > MAX_DURATION_MS) return this
        val id = video.id.value
        if (ended) return copy(videos = videos.filterNot { it.video.id == video.id },
            finished = (listOf(id) + finished).distinct().take(MAX_FINISHED))
        if (id in finished || positionMs < 5_000 || positionMs >= durationMs) return this
        return copy(videos = (listOf(UnfinishedVideo(video, positionMs, durationMs, genre)) +
            videos.filterNot { it.video.id == video.id }).take(MAX_VIDEOS))
    }
    companion object {
        const val MAX_VIDEOS = 24
        const val MAX_FINISHED = 100
        const val MAX_DURATION_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
