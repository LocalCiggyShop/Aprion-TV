package org.privateview.quietscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import org.privateview.quietscreen.core.*

/** Shared across the card and dialog so a release during the window transition is retained. */
internal class RecommendationMenuPress(val keyCode: Int) {
    var released by mutableStateOf(false)
}

/** Remote Menu, or hold OK. Consume the matching release so feedback cannot start playback. */
@Composable
internal fun Modifier.recommendationMenu(open: (RecommendationMenuPress) -> Unit): Modifier {
    var openingPress by remember { mutableStateOf<RecommendationMenuPress?>(null) }
    return onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        val select = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
            code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (event.type == KeyEventType.KeyDown && openingPress?.released == true) openingPress = null
        val press = openingPress
        when {
            event.type == KeyEventType.KeyUp && press != null && code == press.keyCode -> {
                press.released = true; openingPress = null; true
            }
            event.type == KeyEventType.KeyDown && press != null && code == press.keyCode -> true
            event.type == KeyEventType.KeyDown && (code == android.view.KeyEvent.KEYCODE_MENU ||
                (select && event.nativeKeyEvent.isLongPress)) -> {
                val next = RecommendationMenuPress(code); openingPress = next; open(next); true
            }
            else -> false
        }
    }
}

@Composable
internal fun RecommendationFeedbackDialog(video: VideoSummary, close: () -> Unit,
    openingPress: RecommendationMenuPress? = null,
    openChannel: (() -> Unit)? = null,
    select: (RecommendationFeedback) -> Unit) {
    val first = remember { FocusRequester() }
    val feedbackCount = RecommendationFeedback.entries.size
    val channelIndex = if (openChannel != null) feedbackCount else -1
    val cancelIndex = feedbackCount + if (openChannel != null) 1 else 0
    val requesters = remember(cancelIndex) { List(cancelIndex + 1) { FocusRequester() } }
    var newSelectDown by remember { mutableStateOf(false) }
    var saved by remember(video.id) { mutableStateOf<RecommendationFeedback?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(Modifier.widthIn(max = 460.dp).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp)
            .onPreviewKeyEvent { event ->
                val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                // Some remotes reset repeatCount to zero while OK is still held.
                // Wait for its actual release; a zero-count Down is not proof of a new click.
                if (openingPress != null && !openingPress.released) {
                    if (event.nativeKeyEvent.keyCode == openingPress.keyCode) {
                        if (event.type == KeyEventType.KeyUp) openingPress.released = true
                        true
                    } else select
                } else if (!select) false
                else when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!newSelectDown && event.nativeKeyEvent.repeatCount == 0 && !event.nativeKeyEvent.isLongPress) {
                            newSelectDown = true; false
                        } else true
                    }
                    KeyEventType.KeyUp -> {
                        val accepted = newSelectDown; newSelectDown = false; !accepted
                    }
                    else -> true
                }
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (openChannel != null) "Video options" else "Tune recommendations", color = Cloud, fontSize = 21.sp)
            Text(video.title.take(200), color = Muted, fontSize = 14.sp, maxLines = 2)
            UploadAgeText(video, fontSize = 14.sp)
            ViewCount(video, full = true)
            saved?.let { Text("Saved: ${it.label}. Press Back to return.", color = Muted, fontSize = 13.sp, maxLines = 2) }
            (RecommendationFeedback.entries.map { it.label to { select(it) } } +
                (if (openChannel != null) listOf("View channel" to openChannel) else emptyList()) + ("Cancel" to close)).forEachIndexed { index, (label, action) ->
                TvAction(label, Modifier.fillMaxWidth().focusRequester(requesters[index])
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (saved != null && event.type == KeyEventType.KeyDown &&
                            event.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) true
                        else if (event.type == KeyEventType.KeyDown) when (event.key) {
                            Key.DirectionUp, Key.DirectionLeft -> { requesters[Math.floorMod(index - 1, requesters.size)].requestFocus(); true }
                            Key.DirectionDown, Key.DirectionRight -> { requesters[(index + 1) % requesters.size].requestFocus(); true }
                            else -> false
                        } else false
                    }, enabled = index == cancelIndex || saved == null, onClick = {
                        if (openingPress?.released == false) return@TvAction
                        if (index == cancelIndex) close()
                        else if (index == channelIndex) action()
                        else if (saved == null) {
                            saved = RecommendationFeedback.entries[index]
                            action()
                        }
                    })
            }
        }
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; first.requestFocus() }
        LaunchedEffect(saved) { if (saved != null) { repeat(2) { withFrameNanos { } }; requesters[cancelIndex].requestFocus() } }
    }
}
