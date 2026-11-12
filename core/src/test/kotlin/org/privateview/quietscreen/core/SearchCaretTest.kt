package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test

class SearchCaretTest {
    @Test fun keyboardStartupPreservesDeliberateCaretWithoutOverridingTypingOrNavigation() {
        val anchor = SearchTypingAnchor()
        anchor.arm("alpha beta", 6)
        assertNull(anchor.correction("alpha beta", 6, 6))
        assertEquals(6, anchor.correction("alpha beta", 10, 10))
        assertNull(anchor.correction("alpha beta", 10, 10))
        anchor.arm("alpha beta", 6)
        assertNull(anchor.correction("alpha xbeta", 7, 7))
        assertNull(anchor.correction("alpha beta", 10, 10))
        anchor.arm("alpha beta", 6)
        assertNull(anchor.correction("alpha beta", 7, 7))
        assertNull(anchor.correction("alpha beta", 10, 10))
        anchor.arm("alpha beta", 6); anchor.clear()
        assertNull(anchor.correction("alpha beta", 10, 10))
    }
    @Test fun remoteMovementClampsAtEndsAndLeavesTextUntouched() {
        val text = "alpha beta"
        var cursor = text.length
        repeat(4) { cursor = SearchCaret.move(text, cursor, -1) }
        assertEquals(6, cursor)
        assertEquals("alpha xbeta", text.substring(0, cursor) + "x" + text.substring(cursor))
        assertEquals(0, SearchCaret.move(text, 0, -1))
        assertEquals(text.length, SearchCaret.move(text, Int.MAX_VALUE, 1))
        assertEquals(0, SearchCaret.move("", 100, -1))
    }
    @Test fun unicodeMovementAndLimitsDoNotSplitSurrogatePairs() {
        val text = "a\uD83D\uDE80日本"
        assertEquals(1, SearchCaret.move(text, 3, -1))
        assertEquals(3, SearchCaret.move(text, 1, 1))
        assertEquals(1, SearchCaret.clamp(text, 2))
        assertEquals("a", SearchCaret.limit(text, 2))
        assertEquals("a\uD83D\uDE80", SearchCaret.limit(text, 3))
        assertEquals("", SearchCaret.limit(text, -1))
    }
}
