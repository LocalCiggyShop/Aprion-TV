package androidx.media3.exoplayer.source;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleExtractor;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * Narrow adapter to the lazy sidecar entry point in checksum-pinned Media3 1.11.1.
 * That entry point is package-private, so this project-owned adapter must share
 * its package. Keep the rest of playback behind the app's ordinary factories.
 * Uses Media3's existing extraction-time subtitle pipeline, including paused
 * rendering, without loading unselected languages or altering video sources.
 * Reference: DefaultMediaSourceFactory's sidecar construction in Media3 1.11.1.
 */
@UnstableApi
public final class AprionCaptionSource {
    private AprionCaptionSource() {}

    public static MediaSource create(DataSource.Factory data, String endpoint,
            String mime, String language, String id) {
        SubtitleParser.Factory parsers = new DefaultSubtitleParserFactory();
        Format input = new Format.Builder().setId(id).setLanguage(language)
                .setSampleMimeType(mime).build();
        Format output = input.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(mime).setCueReplacementBehavior(parsers.getCueReplacementBehavior(input)).build();
        ExtractorsFactory extractors = () -> new Extractor[] {
                new SubtitleExtractor(parsers.create(input), null)
        };
        return new ProgressiveMediaSource.Factory(data, extractors)
                .enableLazyLoadingWithSingleTrack(SubtitleExtractor.TRACK_ID, output)
                .setLoadOnlySelectedTracks(true)
                .createMediaSource(MediaItem.fromUri(endpoint));
    }
}
