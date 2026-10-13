package org.privateview.quietscreen.ui

internal enum class PlayerArea { VIDEO, TRANSPORT, OPTIONS, RECOMMENDATIONS, RECOVERY, MENU }
internal enum class PlayerDirection { LEFT, RIGHT, UP, DOWN }
internal enum class PlayerRemoteAction { SEEK_BACK, SEEK_FORWARD, SHOW_OPTIONS, SHOW_TRANSPORT, SHOW_RECOMMENDATIONS, HIDE_CONTROLS, NAVIGATE }

/** Only hidden playback seeks with arrows. Every visible control area owns navigation. */
internal fun playerDirection(area: PlayerArea, direction: PlayerDirection): PlayerRemoteAction = when (area) {
    PlayerArea.VIDEO -> when (direction) {
        PlayerDirection.LEFT -> PlayerRemoteAction.SEEK_BACK
        PlayerDirection.RIGHT -> PlayerRemoteAction.SEEK_FORWARD
        PlayerDirection.UP -> PlayerRemoteAction.SHOW_OPTIONS
        PlayerDirection.DOWN -> PlayerRemoteAction.SHOW_RECOMMENDATIONS
    }
    PlayerArea.TRANSPORT -> when (direction) {
        PlayerDirection.LEFT, PlayerDirection.RIGHT -> PlayerRemoteAction.NAVIGATE
        PlayerDirection.UP -> PlayerRemoteAction.SHOW_OPTIONS
        PlayerDirection.DOWN -> PlayerRemoteAction.SHOW_RECOMMENDATIONS
    }
    PlayerArea.OPTIONS, PlayerArea.RECOMMENDATIONS -> when (direction) {
        PlayerDirection.UP -> if (area == PlayerArea.RECOMMENDATIONS) PlayerRemoteAction.SHOW_TRANSPORT else PlayerRemoteAction.HIDE_CONTROLS
        PlayerDirection.DOWN -> if (area == PlayerArea.OPTIONS) PlayerRemoteAction.SHOW_TRANSPORT else PlayerRemoteAction.NAVIGATE
        else -> PlayerRemoteAction.NAVIGATE
    }
    PlayerArea.MENU -> PlayerRemoteAction.NAVIGATE
    PlayerArea.RECOVERY -> when (direction) {
        PlayerDirection.UP -> PlayerRemoteAction.SHOW_TRANSPORT
        PlayerDirection.DOWN -> PlayerRemoteAction.SHOW_RECOMMENDATIONS
        else -> PlayerRemoteAction.NAVIGATE
    }
}

internal fun playerAutoHide(playing: Boolean, area: PlayerArea, failed: Boolean): Boolean =
    playing && !failed && (area == PlayerArea.VIDEO || area == PlayerArea.TRANSPORT)
