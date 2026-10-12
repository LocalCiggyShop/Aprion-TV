package org.privateview.quietscreen.core

@JvmInline
value class VideoId(val value: String) {
    init { require(Regex("[A-Za-z0-9_-]{11}").matches(value)) { "Invalid public video ID" } }
}

data class VideoSummary(val id: VideoId, val title: String, val channel: String, val thumbnail: String?, val durationSeconds: Long?, val live: Boolean = false)
data class CatalogPage(val videos: List<VideoSummary>, val cursor: String?, val suggestion: String? = null, val corrected: Boolean = false)
interface VideoCatalog {
    suspend fun search(query: String, cursor: String? = null): CatalogPage
    fun cancelPending()
}
interface StreamResolver { suspend fun resolve(id: VideoId): PlaybackPlan; fun cancelPending() }

enum class Delivery { PROGRESSIVE, HLS, DASH }
// Signed URLs are sensitive; prevent accidental data-class logging of them.
class MediaEndpoint(val url: String) { override fun toString() = "MediaEndpoint(redacted)" }
data class VideoVariant(val key: String, val endpoint: MediaEndpoint, val mimeType: String, val codec: String?, val height: Int, val fps: Int, val bitrate: Int, val videoOnly: Boolean, val delivery: Delivery)
data class AudioVariant(val key: String, val endpoint: MediaEndpoint, val mimeType: String, val codec: String?, val bitrate: Int, val original: Boolean, val language: String?, val delivery: Delivery)
data class CaptionTrack(val language: String, val format: String, val endpoint: MediaEndpoint)
data class PlaybackPlan(val id: VideoId, val title: String, val durationMs: Long, val fetchedAtMs: Long, val videos: List<VideoVariant>, val audio: List<AudioVariant>, val hls: MediaEndpoint?, val dash: MediaEndpoint?, val captions: List<CaptionTrack>, val live: Boolean = false)

enum class ErrorCode { INVALID_QUERY, OFFLINE, TIMEOUT, RATE_LIMITED, UPSTREAM_CHANGED, SIGN_IN_REQUIRED, RESTRICTED, UNAVAILABLE, DRM, UNSUPPORTED_FORMAT, DECODER, SECURITY_POLICY }
class AppException(val code: ErrorCode, cause: Throwable? = null) : Exception(code.name, cause)
fun ErrorCode.userMessage(): String = when (this) {
    ErrorCode.INVALID_QUERY -> "Enter a search of 1–200 characters."
    ErrorCode.OFFLINE -> "The network could not be reached. Check your connection and try again."
    ErrorCode.TIMEOUT -> "The request timed out. Please try again."
    ErrorCode.RATE_LIMITED -> "YouTube is limiting requests. Please try again later."
    ErrorCode.UPSTREAM_CHANGED -> "YouTube returned an unsupported response. The extractor may need an update."
    ErrorCode.SIGN_IN_REQUIRED -> "This video requires sign-in or YouTube blocked anonymous access."
    ErrorCode.RESTRICTED -> "This video is restricted and cannot be played in this prototype."
    ErrorCode.UNAVAILABLE -> "This video is unavailable."
    ErrorCode.DRM -> "This video requires protected playback, which this prototype does not support."
    ErrorCode.UNSUPPORTED_FORMAT -> "No compatible unencrypted stream was found."
    ErrorCode.DECODER -> "The device could not decode this stream."
    ErrorCode.SECURITY_POLICY -> "A request or script was stopped by the application's security policy."
}
