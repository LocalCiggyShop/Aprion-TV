package org.privateview.quietscreen.playback

import org.junit.Assert.*
import org.junit.Test

class EmulatorPlaybackProfileTest {
    @Test fun workaroundIsConfinedToDebugEmulatorAndCanBeDisabledForComparisons() {
        assertTrue(emulatorPlaybackProfileEnabled(true, "ranchu", true))
        assertTrue(emulatorPlaybackProfileEnabled(true, "goldfish", true))
        assertFalse(emulatorPlaybackProfileEnabled(false, "ranchu", true))
        assertFalse(emulatorPlaybackProfileEnabled(true, "amlogic", true))
        assertFalse(emulatorPlaybackProfileEnabled(true, "generic", true))
        assertFalse(emulatorPlaybackProfileEnabled(true, "ranchu", false))
    }
}
