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

/** Remote Menu, or hold OK. Consume the matching release so feedback cannot start playback. */
@Composable
internal fun Modifier.recommendationMenu(open: () -> Unit): Modifier {
    var handled by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        val select = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
            code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            event.type == KeyEventType.KeyDown && select && event.nativeKeyEvent.repeatCount == 0 -> { handled = false; false }
            event.type == KeyEventType.KeyUp && handled && (select || code == android.view.KeyEvent.KEYCODE_MENU) -> { handled = false; true }
            event.type == KeyEventType.KeyDown && (code == android.view.KeyEvent.KEYCODE_MENU ||
                (select && event.nativeKeyEvent.isLongPress)) -> { handled = true; open(); true }
            else -> false
        }
    }
}

@Composable
internal fun RecommendationFeedbackDialog(video: VideoSummary, close: () -> Unit,
    select: (RecommendationFeedback) -> Unit) {
    val first = remember { FocusRequester() }
    val requesters = remember { List(4) { FocusRequester() } }
    var awaitingPress by remember { mutableStateOf(true) }
    var saved by remember(video.id) { mutableStateOf<RecommendationFeedback?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(Modifier.widthIn(max = 460.dp).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp)
            .onPreviewKeyEvent { event ->
                val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                // Repeat events from the opening hold can arrive in this new window.
                // Only a new, ordinary press may arm a dialog action; consume the
                // opening repeats and release even when the remote omits LONG_PRESS.
                if (!select || !awaitingPress) false
                else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 &&
                    !event.nativeKeyEvent.isLongPress) { awaitingPress = false; false }
                else true
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tune recommendations", color = Cloud, fontSize = 21.sp)
            Text(video.title.take(200), color = Muted, fontSize = 14.sp, maxLines = 2)
            UploadAgeText(video, fontSize = 14.sp)
            saved?.let { Text("Saved: ${it.label}. Press Back to return.", color = Muted, fontSize = 13.sp, maxLines = 2) }
            (RecommendationFeedback.entries.map { it.label to { select(it) } } + ("Cancel" to close)).forEachIndexed { index, (label, action) ->
                TvAction(label, Modifier.fillMaxWidth().focusRequester(requesters[index])
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (saved != null && event.type == KeyEventType.KeyDown &&
                            event.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) true
                        else if (event.type == KeyEventType.KeyDown) when (event.key) {
                            Key.DirectionUp, Key.DirectionLeft -> { requesters[Math.floorMod(index - 1, 4)].requestFocus(); true }
                            Key.DirectionDown, Key.DirectionRight -> { requesters[(index + 1) % 4].requestFocus(); true }
                            else -> false
                        } else false
                    }, enabled = index == 3 || saved == null, onClick = {
                        if (index == 3) close()
                        else if (saved == null) {
                            saved = RecommendationFeedback.entries[index]
                            action()
                        }
                    })
            }
        }
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; first.requestFocus() }
        LaunchedEffect(saved) { if (saved != null) { repeat(2) { withFrameNanos { } }; requesters[3].requestFocus() } }
    }
}
