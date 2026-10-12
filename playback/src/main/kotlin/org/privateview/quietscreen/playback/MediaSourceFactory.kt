package org.privateview.quietscreen.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.extractor.NetworkPolicy
import org.privateview.quietscreen.extractor.NetworkRole

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaSourceFactory {
    private val data = OkHttpDataSource.Factory(NetworkPolicy.client(NetworkRole.MEDIA)).setUserAgent("QuietScreen/0.1")
    private val manifestData = BoundedDataSource.Factory(data, 8L * 1024 * 1024)
    private fun item(e: MediaEndpoint, mime: String): MediaItem {
        NetworkPolicy.validate(e.url, NetworkRole.MEDIA)
        return MediaItem.Builder().setUri(e.url).setMimeType(mime).build()
    }
    fun build(selection: PlaybackSelection): MediaSource = when (selection) {
        is PlaybackSelection.Manifest -> when (selection.delivery) {
            Delivery.HLS -> HlsMediaSource.Factory(HlsDataSourceFactory { type ->
                if (type == C.DATA_TYPE_MANIFEST) manifestData.createDataSource() else data.createDataSource()
            }).createMediaSource(item(selection.endpoint, MimeTypes.APPLICATION_M3U8))
            Delivery.DASH -> DashMediaSource.Factory(DefaultDashChunkSource.Factory(data), manifestData)
                .createMediaSource(item(selection.endpoint, MimeTypes.APPLICATION_MPD))
            else -> throw AppException(ErrorCode.UNSUPPORTED_FORMAT)
        }
        is PlaybackSelection.Muxed -> ProgressiveMediaSource.Factory(data).createMediaSource(item(selection.video.endpoint, selection.video.mimeType))
        is PlaybackSelection.Separate -> MergingMediaSource(
            ProgressiveMediaSource.Factory(data).createMediaSource(item(selection.video.endpoint, selection.video.mimeType)),
            ProgressiveMediaSource.Factory(data).createMediaSource(item(selection.audio.endpoint, selection.audio.mimeType)))
    }

    fun build(selection: PlaybackSelection, caption: CaptionTrack?, durationMs: Long): MediaSource {
        val content = build(selection)
        if (caption == null) return content
        NetworkPolicy.validate(caption.endpoint.url, NetworkRole.METADATA)
        if (!supportedCaption(caption)) throw AppException(ErrorCode.UNSUPPORTED_FORMAT)
        val subtitle = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption.endpoint.url))
            .setId("quietscreen-caption")
            .setLanguage(caption.language).setMimeType(caption.format).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        // Caption endpoints are metadata hosts, not googlevideo media hosts. Use
        // the existing bounded metadata client instead of widening the allowlist.
        val captionData = BoundedDataSource.Factory(OkHttpDataSource.Factory(NetworkPolicy.client(NetworkRole.METADATA)), 1024L * 1024)
        val text = SingleSampleMediaSource.Factory(captionData).setTreatLoadErrorsAsEndOfStream(true)
            .createMediaSource(subtitle, if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
        return MergingMediaSource(content, text)
    }
}

fun supportedCaption(track: CaptionTrack): Boolean = track.format in setOf(MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, MimeTypes.APPLICATION_SUBRIP)
