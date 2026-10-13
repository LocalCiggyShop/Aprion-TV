package org.privateview.quietscreen.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.privateview.quietscreen.core.UploadAge
import org.privateview.quietscreen.core.VideoSummary

private class AgeClock {
    var now by mutableLongStateOf(System.currentTimeMillis())
    var fastUntil by mutableLongStateOf(0)
}
private val LocalAgeClock = staticCompositionLocalOf { AgeClock() }

/** One foreground clock for all visible age labels; no per-card timer/network work. */
@Composable
internal fun UploadAgeScope(content: @Composable () -> Unit) {
    val clock = remember { AgeClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock.fastUntil, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                clock.now = System.currentTimeMillis()
                // Second precision is useful only during a fresh upload's first minute.
                delay(if (clock.now < clock.fastUntil) 1_000L else UploadAge.MINUTE_MS)
            }
        }
    }
    CompositionLocalProvider(LocalAgeClock provides clock, content = content)
}

@Composable
internal fun UploadAgeText(video: VideoSummary, modifier: Modifier = Modifier, fontSize: TextUnit = 11.sp, color: Color = Muted) {
    val clock = LocalAgeClock.current
    DisposableEffect(clock, video.uploadedAtMs, video.live) {
        val date = video.uploadedAtMs
        if (!video.live && UploadAge.recent(date, System.currentTimeMillis()))
            clock.fastUntil = maxOf(clock.fastUntil, date!! + UploadAge.MINUTE_MS)
        onDispose { }
    }
    Text(UploadAge.label(video.uploadedAtMs, clock.now, video.live), color = color, fontSize = fontSize,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
