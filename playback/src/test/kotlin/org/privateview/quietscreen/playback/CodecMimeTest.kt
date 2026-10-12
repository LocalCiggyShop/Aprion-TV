package org.privateview.quietscreen.playback

import org.junit.Assert.*
import org.junit.Test
import org.privateview.quietscreen.core.*

class CodecMimeTest {
    private fun variant(container: String, codec: String?) = VideoVariant("fixture", MediaEndpoint("https://r1.googlevideo.com/video"), container, codec, 1080, 30, 1000000, true, Delivery.PROGRESSIVE)
    @Test fun decoderUsesAvcSampleMimeInsteadOfMp4Container() { assertEquals("video/avc", decoderMimeType(variant("video/mp4", "avc1.640028"))) }
    @Test fun decoderUsesVp9AndAv1SampleTypesAndRejectsUnknownCodec() {
        assertEquals("video/x-vnd.on2.vp9", decoderMimeType(variant("video/webm", "vp09.00.41.08")))
        assertEquals("video/av01", decoderMimeType(variant("video/mp4", "av01.0.08M.08")))
        assertNull(decoderMimeType(variant("video/mp4", null)))
    }
}
