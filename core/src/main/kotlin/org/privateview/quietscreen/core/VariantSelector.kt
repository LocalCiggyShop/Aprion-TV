package org.privateview.quietscreen.core

sealed interface PlaybackSelection {
    data class Manifest(val endpoint: MediaEndpoint, val delivery: Delivery) : PlaybackSelection
    data class Muxed(val video: VideoVariant) : PlaybackSelection
    data class Separate(val video: VideoVariant, val audio: AudioVariant) : PlaybackSelection
}

object VariantSelector {
    /** Opt-in workaround for the measured Windows TV-emulator path, never a TV default.
     * Return null when a supported complete AVC/AAC pair is unavailable so ordinary
     * manifest selection remains available. Keep the user's resolution/capability limits.
     */
    fun emulatorAvcSelection(plan: PlaybackPlan, maxHeight: Int, supported: (VideoVariant) -> Boolean): PlaybackSelection.Separate? {
        require(maxHeight in 144..4320)
        val video = plan.videos.filter {
            it.delivery == Delivery.PROGRESSIVE && it.videoOnly && it.height in 1..maxHeight &&
                it.codec?.substringBefore('.')?.lowercase() in setOf("avc1", "avc3") && supported(it)
        }.sortedWith(compareByDescending<VideoVariant> { it.height }.thenByDescending { it.fps }.thenBy { it.bitrate }).firstOrNull() ?: return null
        val audio = plan.audio.filter {
            it.delivery == Delivery.PROGRESSIVE && it.codec?.lowercase()?.startsWith("mp4a.40.") == true &&
                it.original
        }
            .sortedWith(compareByDescending<AudioVariant> { it.original }.thenByDescending { it.bitrate }).firstOrNull() ?: return null
        return PlaybackSelection.Separate(video, audio)
    }

    fun select(plan: PlaybackPlan, maxHeight: Int = 1080, preferSeparate: Boolean = false, supported: (VideoVariant) -> Boolean = { true }): PlaybackSelection {
        require(maxHeight in 144..4320)
        if (!preferSeparate) {
            plan.hls?.let { return PlaybackSelection.Manifest(it, Delivery.HLS) }
            plan.dash?.let { return PlaybackSelection.Manifest(it, Delivery.DASH) }
        }
        val video = plan.videos.filter { it.delivery == Delivery.PROGRESSIVE && it.height in 1..maxHeight && supported(it) }
            .sortedWith(compareByDescending<VideoVariant> { it.height }.thenByDescending { it.fps }.thenBy { it.bitrate })
        for (v in video) {
            if (!v.videoOnly && !preferSeparate) return PlaybackSelection.Muxed(v)
            if (v.videoOnly) {
                val a = plan.audio.filter { it.delivery == Delivery.PROGRESSIVE }
                    .sortedWith(compareByDescending<AudioVariant> { it.original }.thenByDescending { it.bitrate }).firstOrNull()
                if (a != null) return PlaybackSelection.Separate(v, a)
            }
        }
        if (preferSeparate) throw AppException(ErrorCode.UNSUPPORTED_FORMAT)
        throw AppException(ErrorCode.UNSUPPORTED_FORMAT)
    }
}

/** One refresh for expired/forbidden URLs and two transient retries per playback selection. */
class RecoveryBudget {
    private var refreshes = 0
    private var retries = 0
    fun allowRefresh(): Boolean = if (refreshes == 0) { refreshes++; true } else false
    fun nextRetryDelayMs(): Long? = when (retries++) { 0 -> 1000L; 1 -> 3000L; else -> null }
}
