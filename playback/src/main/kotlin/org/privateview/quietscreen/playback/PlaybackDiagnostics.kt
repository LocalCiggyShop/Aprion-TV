package org.privateview.quietscreen.playback

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Debug APK diagnostics only. Never logs media items, signed URLs or exception messages. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackDiagnostics : AnalyticsListener {
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        Log.i(TAG, "playbackState=$state")
    }
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        Log.w(TAG, "audioUnderrun bytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")
    }
    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        Log.e(TAG, "audioSinkError type=${audioSinkError.javaClass.simpleName}")
    }
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        Log.e(TAG, "playerError code=${error.errorCodeName} type=${error.javaClass.simpleName}")
    }
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.i(TAG, "audioDecoder name=$decoderName initMs=$initializationDurationMs")
    }
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.i(TAG, "videoDecoder name=$decoderName initMs=$initializationDurationMs")
    }
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.w(TAG, "droppedVideoFrames count=$droppedFrames elapsedMs=$elapsedMs")
    }
    fun sample(player: ExoPlayer) {
        val audio = player.audioFormat
        val video = player.videoFormat
        val runtime = Runtime.getRuntime()
        Log.i(TAG, "sample position=${player.currentPosition} bufferedMs=${player.totalBufferedDuration} state=${player.playbackState} playing=${player.isPlaying} suppression=${player.playbackSuppressionReason} audioRate=${audio?.sampleRate ?: 0} audioChannels=${audio?.channelCount ?: 0} videoMime=${video?.sampleMimeType} height=${video?.height ?: 0} fps=${video?.frameRate ?: 0f} heapMiB=${(runtime.totalMemory()-runtime.freeMemory())/1048576}")
    }
    companion object { private const val TAG = "QuietScreenAudio" }
}
