package org.privateview.quietscreen.ui

import org.junit.Assert.*
import org.junit.Test

class PlayerRemoteTest {
    @Test fun hiddenVideoSeeksWhileEveryVisibleControlAreaNavigates() {
        assertEquals(PlayerRemoteAction.SEEK_BACK, playerDirection(PlayerArea.VIDEO, PlayerDirection.LEFT))
        assertEquals(PlayerRemoteAction.SEEK_FORWARD, playerDirection(PlayerArea.VIDEO, PlayerDirection.RIGHT))
        for (area in listOf(PlayerArea.TRANSPORT, PlayerArea.OPTIONS, PlayerArea.RECOMMENDATIONS, PlayerArea.RECOVERY, PlayerArea.MENU)) {
            assertEquals(PlayerRemoteAction.NAVIGATE, playerDirection(area, PlayerDirection.LEFT))
            assertEquals(PlayerRemoteAction.NAVIGATE, playerDirection(area, PlayerDirection.RIGHT))
        }
    }
    @Test fun verticalNavigationReachesOptionsAndRecommendationsAndReturnsToPlayback() {
        assertEquals(PlayerRemoteAction.SHOW_OPTIONS, playerDirection(PlayerArea.VIDEO, PlayerDirection.UP))
        assertEquals(PlayerRemoteAction.SHOW_RECOMMENDATIONS, playerDirection(PlayerArea.TRANSPORT, PlayerDirection.DOWN))
        assertEquals(PlayerRemoteAction.SHOW_TRANSPORT, playerDirection(PlayerArea.OPTIONS, PlayerDirection.DOWN))
        assertEquals(PlayerRemoteAction.HIDE_CONTROLS, playerDirection(PlayerArea.OPTIONS, PlayerDirection.UP))
        assertEquals(PlayerRemoteAction.SHOW_TRANSPORT, playerDirection(PlayerArea.RECOMMENDATIONS, PlayerDirection.UP))
        assertEquals(PlayerRemoteAction.NAVIGATE, playerDirection(PlayerArea.MENU, PlayerDirection.DOWN))
        assertEquals(PlayerRemoteAction.SHOW_TRANSPORT, playerDirection(PlayerArea.RECOVERY, PlayerDirection.UP))
        assertEquals(PlayerRemoteAction.SHOW_RECOMMENDATIONS, playerDirection(PlayerArea.RECOVERY, PlayerDirection.DOWN))
    }
    @Test fun readingMenusOrBrowsingSuggestionsNeverAutoHidesOrResumesPausedPlayback() {
        assertTrue(playerAutoHide(true, PlayerArea.TRANSPORT, false))
        assertFalse(playerAutoHide(false, PlayerArea.TRANSPORT, false))
        assertFalse(playerAutoHide(true, PlayerArea.TRANSPORT, true))
        for (area in listOf(PlayerArea.OPTIONS, PlayerArea.RECOMMENDATIONS, PlayerArea.MENU))
            assertFalse(playerAutoHide(true, area, false))
    }
}
