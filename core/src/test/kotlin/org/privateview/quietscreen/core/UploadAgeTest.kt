package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class UploadAgeTest {
    private val now = 1_800_000_000_000L
    @Test fun boundariesAndPluralUnitsUseReadableRelativeLabels() {
        val cases = listOf(0L to "Just uploaded",999L to "Just uploaded",1_000L to "Uploaded 1 second ago",
            59_000L to "Uploaded 59 seconds ago",60_000L to "Uploaded 1 minute ago",
            120_000L to "Uploaded 2 minutes ago",3_600_000L to "Uploaded 1 hour ago",
            86_400_000L to "Uploaded 1 day ago",172_800_000L to "Uploaded 2 days ago",
            2_592_000_000L to "Uploaded 1 month ago",31_536_000_000L to "Uploaded 1 year ago",
            63_072_000_000L to "Uploaded 2 years ago")
        cases.forEach { (age, expected) -> assertEquals(expected,UploadAge.label(now-age,now)) }
    }
    @Test fun missingInvalidFutureAndLiveDatesDoNotInventAnUploadAge() {
        listOf<Long?>(null,0,-1,Long.MIN_VALUE,now+1,Long.MAX_VALUE).forEach {
            assertEquals("Upload date unavailable",UploadAge.label(it,now))
            assertFalse(UploadAge.recent(it,now))
        }
        assertEquals("Live video",UploadAge.label(null,now,live=true))
        assertEquals("Live video",UploadAge.label(now-1000,now,live=true))
        assertFalse(UploadAge.recent(now-60_000,now))
        assertTrue(UploadAge.recent(now-59_999,now))
        assertTrue(UploadAge.recent(now,now))
        assertTrue(UploadAge.label(1,Long.MAX_VALUE).startsWith("Uploaded "))
    }
}
