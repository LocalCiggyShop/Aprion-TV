package org.privateview.quietscreen.playback

import org.junit.Assert.*
import org.junit.Test
import org.privateview.quietscreen.core.CaptionTrack
import org.privateview.quietscreen.core.MediaEndpoint

class CaptionFormatTest {
    @Test fun onlyRecognizedSubtitleFormatsAreOffered() {
        fun track(mime: String) = CaptionTrack("en", mime, MediaEndpoint("https://www.youtube.com/api/timedtext"))
        listOf("text/vtt", "application/ttml+xml", "application/x-subrip").forEach { assertTrue(supportedCaption(track(it))) }
        listOf("", "application/json", "text/html", "application/javascript").forEach { assertFalse(supportedCaption(track(it))) }
    }
}
