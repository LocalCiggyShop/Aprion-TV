package org.privateview.quietscreen.playback

import android.content.Context
import androidx.media3.common.*
import java.util.Locale
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.privateview.quietscreen.core.*

data class PlaybackStatus(val text: String = "Ready", val error: ErrorCode? = null, val requestedHeight: Int = 1080, val renderedHeight: Int = 0, val renderedCodec: String = "", val source: String = "", val refreshCount: Int = 0, val fixedQuality: Boolean = false, val trackRevision: Int = 0, val captionError: Boolean = false)
data class TrackChoice(val key: String, val label: String, val selected: Boolean)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerController(private val context: Context, private val resolver: StreamResolver, private val scope: CoroutineScope, enableEmulatorProfile: Boolean = false) {
    private val emulatorProfile = emulatorPlaybackProfileEnabled(
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
        android.os.Build.HARDWARE, enableEmulatorProfile)
    private val tracks = DefaultTrackSelector(context)
    val player: ExoPlayer = ExoPlayer.Builder(context).setTrackSelector(tracks)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 40000, 1500, 3000).setTargetBufferBytes(48 * 1024 * 1024).build()).build()
        .apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true) }
    private val sources = MediaSourceFactory()
    private val mutable = MutableStateFlow(PlaybackStatus())
    val status = mutable.asStateFlow()
    var plan: PlaybackPlan? = null; private set
    private var job: Job? = null
    private var budget = RecoveryBudget()
    private var generation = 0
    private var released = false
    private var height = 1080
    private var separate = false
    private var diagnosticsJob: Job? = null
    private var fixedQuality = false
    private var defaultCaptionEnabled = false
    private var selectedCaptionLanguage: String? = null
    private var selectedCaptionFormat: String? = null
    private var captionOff = true
    private var nativeCaption = false
    private var manifestFallbackUsed = false

    init {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val diagnostics = PlaybackDiagnostics()
            player.addAnalyticsListener(diagnostics)
            diagnosticsJob = scope.launch {
                while (isActive && !released) {
                    delay(15000)
                    if (!released) diagnostics.sample(player)
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && plan != null && player.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }) {
                    if (!tryManifestFallback()) fail(ErrorCode.UNSUPPORTED_FORMAT)
                    return
                }
                updatePlaybackText()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlaybackText() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updatePlaybackText() }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) { updatePlaybackText() }
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.any { g -> (0 until g.length).any { g.getTrackFormat(it).drmInitData != null } }) {
                    player.stop(); mutable.value = mutable.value.copy(error = ErrorCode.DRM, text = ErrorCode.DRM.userMessage()); return
                }
                val f = player.videoFormat
                mutable.value = mutable.value.copy(renderedHeight = f?.height?.coerceAtLeast(0) ?: 0, renderedCodec = f?.codecs.orEmpty(), trackRevision = mutable.value.trackRevision + 1)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                mutable.value = mutable.value.copy(renderedHeight = videoSize.height, renderedCodec = player.videoFormat?.codecs.orEmpty())
            }
            override fun onPlayerError(error: PlaybackException) { recover(error) }
        })
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                if (!wasCanceled && mediaLoadData.trackType == C.TRACK_TYPE_TEXT && !released)
                    mutable.value = mutable.value.copy(captionError = true)
            }
        })
    }

    private fun updatePlaybackText() {
        if (mutable.value.error != null || player.playbackState == Player.STATE_IDLE) return
        val text = when {
            player.playbackState == Player.STATE_ENDED -> "Ended"
            !player.playWhenReady -> "Paused"
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "Waiting for audio focus/output"
            player.playbackState == Player.STATE_BUFFERING -> "Buffering"
            player.isPlaying -> "Playing"
            else -> "Starting playback"
        }
        mutable.value = mutable.value.copy(text = text)
    }

    fun start(id: VideoId, maxHeight: Int = 1080, requireSeparate: Boolean = false) {
        if (released) return
        budget = RecoveryBudget(); height = maxHeight; separate = requireSeparate
        fixedQuality = false; manifestFallbackUsed = false
        selectedCaptionLanguage = null; selectedCaptionFormat = null; captionOff = !defaultCaptionEnabled; nativeCaption = false
        generation++; job?.cancel(); resolver.cancelPending(); plan = null
        mutable.value = PlaybackStatus(text = "Resolving video", requestedHeight = height)
        val current = generation
        job = scope.launch { resolveAndPrepare(id, current, 0, true) }
    }
    private suspend fun resolveAndPrepare(id: VideoId, current: Int, position: Long, play: Boolean) {
        try {
            val resolved = resolver.resolve(id)
            if (released || current != generation) return
            plan = resolved; prepare(resolved, position, play)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail((e as? AppException)?.code ?: ErrorCode.UPSTREAM_CHANGED) }
    }
    private fun prepare(p: PlaybackPlan, position: Long, play: Boolean) {
        val selectionPlan = if (fixedQuality || manifestFallbackUsed) p.copy(hls = null, dash = null) else p
        val selection = (if (emulatorProfile) VariantSelector.emulatorAvcSelection(selectionPlan, height, ::supported) else null)
            ?: VariantSelector.select(selectionPlan, height, separate, ::supported)
        if (emulatorProfile) android.util.Log.i("QuietScreenAudio", "selection emulatorAvc=${selection is PlaybackSelection.Separate && decoderMimeType(selection.video) == MimeTypes.VIDEO_H264} requestedHeight=$height")
        val available = p.captions.filter(::supportedCaption)
        val caption = if (captionOff || nativeCaption) null else {
            available.firstOrNull { it.language == selectedCaptionLanguage && it.format == selectedCaptionFormat }
                ?: if (selectedCaptionLanguage == null && defaultCaptionEnabled) available.firstOrNull { it.language.startsWith("en") } ?: available.firstOrNull() else null
        }
        if (caption != null) { selectedCaptionLanguage = caption.language; selectedCaptionFormat = caption.format }
        tracks.parameters = tracks.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, height).setForceHighestSupportedBitrate(true)
            .setExceedVideoConstraintsIfNecessary(false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, captionOff).build()
        mutable.value = mutable.value.copy(text = "Loading stream", error = null, captionError = false, source = when (selection) { is PlaybackSelection.Manifest -> selection.delivery.name; is PlaybackSelection.Separate -> "Separate video + audio"; else -> "Muxed" }, requestedHeight = height, fixedQuality = fixedQuality || manifestFallbackUsed)
        player.setMediaSource(sources.build(selection, caption, p.durationMs)); player.prepare(); player.seekTo(position.coerceAtLeast(0)); player.playWhenReady = play
    }
    private fun supported(v: VideoVariant): Boolean = runCatching {
        val sampleMime = decoderMimeType(v) ?: return@runCatching false
        val format = Format.Builder().setSampleMimeType(sampleMime).setCodecs(v.codec).setHeight(v.height).setWidth(v.height * 16 / 9).setFrameRate(v.fps.toFloat().takeIf { it > 0 } ?: 30f).build()
        val emulator = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.FINGERPRINT.startsWith("generic")
        MediaCodecUtil.getDecoderInfos(sampleMime, false, false).any { (it.hardwareAccelerated || emulator) && it.isFormatSupported(context, format) }
    }.getOrDefault(false)

    private fun recover(error: PlaybackException) {
        val captionFailure = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType?.let(MimeTypes::getTrackType) == C.TRACK_TYPE_TEXT ||
            generateSequence<Throwable>(error) { it.cause }.take(16).any { it is androidx.media3.exoplayer.source.MergingMediaSource.IllegalMergeException }
        if (!captionOff && captionFailure && plan != null) {
            captionOff = true; selectedCaptionLanguage = null; selectedCaptionFormat = null; nativeCaption = false
            try { prepare(plan!!, player.currentPosition, player.playWhenReady); mutable.value = mutable.value.copy(captionError = true); return }
            catch (_: Exception) { /* Continue through the existing finite video recovery policy. */ }
        }
        if (error.errorCode in 4000..4999 && tryManifestFallback()) return
        var cause: Throwable? = error
        var http = 0
        while (cause != null) { if (cause is HttpDataSource.InvalidResponseCodeException) http = cause.responseCode; cause = cause.cause }
        val id = plan?.id ?: run { fail(ErrorCode.UNSUPPORTED_FORMAT); return }
        val current = generation; val position = player.currentPosition; val play = player.playWhenReady
        if (http == 403 && budget.allowRefresh()) {
            mutable.value = mutable.value.copy(text = "Refreshing expired stream", refreshCount = mutable.value.refreshCount + 1)
            job?.cancel(); job = scope.launch { resolveAndPrepare(id, current, position, play) }; return
        }
        val wait = if (http >= 500 || error.errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) budget.nextRetryDelayMs() else null
        if (wait != null) {
            mutable.value = mutable.value.copy(text = "Retrying playback")
            job?.cancel(); job = scope.launch { delay(wait); if (!released && current == generation) resolveAndPrepare(id, current, position, play) }; return
        }
        fail(when { http == 429 -> ErrorCode.RATE_LIMITED; http == 403 -> ErrorCode.SIGN_IN_REQUIRED; error.errorCode in 4000..4999 -> ErrorCode.DECODER; else -> ErrorCode.UNSUPPORTED_FORMAT })
    }
    private fun fail(code: ErrorCode) { if (!released) { player.stop(); mutable.value = mutable.value.copy(error = code, text = code.userMessage()) } }
    fun seekBy(deltaMs: Long) { player.seekTo((player.currentPosition + deltaMs).coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
    fun changeQuality(maxHeight: Int, requireSeparate: Boolean = false) {
        if (released) return
        val p = plan ?: return
        height = maxHeight; separate = requireSeparate; fixedQuality = false; manifestFallbackUsed = false
        try { prepare(p, player.currentPosition, player.playWhenReady) } catch (e: Exception) { fail((e as? AppException)?.code ?: ErrorCode.UNSUPPORTED_FORMAT) }
    }

    fun qualityHeights(): List<Int> = plan?.videos.orEmpty().filter { supported(it) && it.height in 144..4320 }
        .map { it.height }.distinct().sortedDescending()

    /** null chooses the ordinary native-manifest policy. Explicit choices use a
     * supported progressive source and retain the real position/play state. */
    fun selectQuality(maxHeight: Int?, defaultHeight: Int) {
        if (released) return
        val p = plan ?: return
        if (maxHeight != null && maxHeight !in qualityHeights()) return
        height = maxHeight ?: defaultHeight; separate = false; fixedQuality = maxHeight != null; manifestFallbackUsed = false
        try { prepare(p, player.currentPosition, player.playWhenReady) }
        catch (e: Exception) { fail((e as? AppException)?.code ?: ErrorCode.UNSUPPORTED_FORMAT) }
    }

    private fun tryManifestFallback(): Boolean {
        val p = plan ?: return false
        if (released || manifestFallbackUsed || mutable.value.source !in setOf("HLS", "DASH") ||
            p.videos.none { it.delivery == Delivery.PROGRESSIVE && it.height in 1..height && supported(it) }) return false
        manifestFallbackUsed = true
        return try { prepare(p, player.currentPosition, player.playWhenReady); true }
        catch (_: Exception) { false }
    }

    fun setDefaultCaptions(enabled: Boolean) { defaultCaptionEnabled = enabled; captionOff = !enabled }

    fun captionChoices(): List<TrackChoice> {
        val external = plan?.captions.orEmpty().filter(::supportedCaption).mapIndexed { index, caption ->
            TrackChoice("caption-$index", languageLabel(caption.language), !captionOff && !nativeCaption && caption.language == selectedCaptionLanguage && caption.format == selectedCaptionFormat)
        }
        val native = nativeChoices(C.TRACK_TYPE_TEXT).filterNot { choice ->
            val groupIndex = choice.key.substringBefore('-').toInt()
            player.currentTracks.groups[groupIndex].mediaTrackGroup.getFormat(choice.key.substringAfter('-').toInt()).id == "quietscreen-caption"
        }
        return listOf(TrackChoice("off", "Off", captionOff)) + external + native
    }

    fun selectCaption(key: String) {
        if (released) return
        mutable.value = mutable.value.copy(captionError = false)
        if (key == "off") {
            captionOff = true; selectedCaptionLanguage = null; selectedCaptionFormat = null; nativeCaption = false
            tracks.parameters = tracks.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else if (key.startsWith("caption-")) {
            val caption = plan?.captions.orEmpty().filter(::supportedCaption).getOrNull(key.removePrefix("caption-").toIntOrNull() ?: -1) ?: return
            captionOff = false; nativeCaption = false; selectedCaptionLanguage = caption.language; selectedCaptionFormat = caption.format
            tracks.parameters = tracks.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            plan?.let { prepare(it, player.currentPosition, player.playWhenReady) }
        } else {
            captionOff = false; nativeCaption = true; selectedCaptionLanguage = null; selectedCaptionFormat = null
            selectNativeTrack(key, C.TRACK_TYPE_TEXT)
        }
    }

    fun audioChoices(): List<TrackChoice> = nativeChoices(C.TRACK_TYPE_AUDIO)
    fun selectAudio(key: String) { if (!released) selectNativeTrack(key, C.TRACK_TYPE_AUDIO) }

    private fun nativeChoices(type: Int): List<TrackChoice> = player.currentTracks.groups.flatMapIndexed { index, group ->
        if (group.type != type) emptyList() else (0 until group.length).filter { group.isTrackSupported(it) }.map { track ->
            val f = group.getTrackFormat(track)
            val language = if (f.language.isNullOrBlank()) (if (type == C.TRACK_TYPE_TEXT) "Captions" else "Audio track") else languageLabel(f.language)
            TrackChoice("$index-$track", f.label?.take(100) ?: language + (if (f.channelCount > 0) " · ${f.channelCount} channels" else ""), group.isTrackSelected(track))
        }
    }

    private fun selectNativeTrack(key: String, type: Int) {
        val parts = key.split('-'); if (parts.size != 2) return
        val group = player.currentTracks.groups.getOrNull(parts[0].toIntOrNull() ?: -1) ?: return
        val index = parts[1].toIntOrNull() ?: return
        if (group.type != type || index !in 0 until group.length || !group.isTrackSupported(index)) return
        tracks.parameters = tracks.buildUponParameters().setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }

    private fun languageLabel(language: String?): String = language?.takeIf { it.isNotBlank() }?.let {
        Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH).take(100).ifBlank { it.take(100) }
    } ?: "Original audio"
    fun release() { if (!released) { released = true; generation++; job?.cancel(); diagnosticsJob?.cancel(); resolver.cancelPending(); player.release() } }
}

internal fun decoderMimeType(variant: VideoVariant): String? = MimeTypes.getMediaMimeType(variant.codec)
internal fun emulatorPlaybackProfileEnabled(debuggable: Boolean, hardware: String, enabled: Boolean): Boolean =
    enabled && debuggable && hardware in setOf("ranchu", "goldfish")
