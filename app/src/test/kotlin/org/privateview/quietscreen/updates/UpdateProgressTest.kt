package org.privateview.quietscreen.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateProgressTest {
    @Test fun boundedBytesUseDecimalMbAndOnlyActiveTransfersShowPage() {
        val status = UpdateStatus(busy = true, phase = UpdatePhase.DOWNLOADING, downloadedBytes = 1_250_000, totalBytes = 2_500_000)
        assertEquals("1.25 / 2.50 MB", status.sizeLabel)
        assertEquals(0.5f, status.fraction, 0f)
        assertTrue(status.showProgress); assertTrue(status.cancellable)
        assertEquals(1f, status.copy(downloadedBytes = Long.MAX_VALUE).fraction, 0f)
        assertEquals(0f, status.copy(totalBytes = 0).fraction, 0f)
        assertFalse(status.copy(busy = false).showProgress)
        assertFalse(status.copy(phase = UpdatePhase.CHECKING).showProgress)
        assertFalse(status.copy(phase = UpdatePhase.INSTALLING).cancellable)
        assertTrue(status.copy(phase = UpdatePhase.VERIFYING).cancellable)
    }
}
