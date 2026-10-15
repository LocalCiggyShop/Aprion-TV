package org.privateview.quietscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.privateview.quietscreen.PreviewLoader
import org.privateview.quietscreen.core.PreviewStoryboard
import org.privateview.quietscreen.core.SeekSession
import org.privateview.quietscreen.core.VideoChapter

@Composable
internal fun SeekOverlay(session: SeekSession, preview: PreviewStoryboard?, chapters: List<VideoChapter>) {
    val chapterIndex = chapters.indexOfLast { it.startMs <= session.pendingMs }
    val loader = remember(preview) { PreviewLoader() }
    var bitmap by remember(preview, session.pendingMs) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(preview, session.pendingMs) { mutableStateOf(preview != null) }
    LaunchedEffect(preview, session.pendingMs) {
        if (preview != null) { delay(120); bitmap = loader.load(preview, session.pendingMs) }
        loading = false
    }
    DisposableEffect(loader) { onDispose { loader.clear() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .82f)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(320.dp, 180.dp).border(2.dp, Accent, RoundedCornerShape(12.dp)).background(Ink), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), "Preview at ${timeLabel(session.pendingMs)}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Text(if (loading) "Loading preview…" else "Preview unavailable", color = Muted, fontSize = 16.sp)
            }
            Text(timeLabel(session.pendingMs), color = Cloud, fontSize = 28.sp,
                modifier = Modifier.semantics { contentDescription = "Seek position ${session.pendingMs / 1000} seconds" })
            Text(if (session.chapterMode) "Chapter navigation" else "Time seeking", color = Accent, fontSize = 16.sp)
            Text(if (chapterIndex >= 0) "${chapterIndex + 1}/${chapters.size} · ${chapters[chapterIndex].title}"
                else if (chapters.isEmpty()) "No chapters available for this video" else "Before the first chapter",
                color = Cloud, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val y = size.height / 2
                drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), strokeWidth = 5.dp.toPx())
                val target = size.width * (session.pendingMs.toDouble() / session.durationMs).toFloat()
                val original = size.width * (session.originalMs.toDouble() / session.durationMs).toFloat()
                drawLine(Accent, Offset(0f, y), Offset(target, y), strokeWidth = 5.dp.toPx())
                chapters.forEach { chapter ->
                    val x = size.width * (chapter.startMs.toDouble() / session.durationMs).toFloat()
                    drawLine(Color.White.copy(alpha = .6f), Offset(x, y - 4.dp.toPx()), Offset(x, y + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                }
                drawLine(Color.White, Offset(original, 0f), Offset(original, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(Accent, 7.dp.toPx(), Offset(target, y))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Started at ${timeLabel(session.originalMs)}", color = Muted, fontSize = 13.sp)
                Text(timeLabel(session.durationMs), color = Muted, fontSize = 13.sp)
            }
            Text(when {
                session.chapterMode -> "←/→ Previous / next chapter · ↓ Time seeking"
                chapters.isNotEmpty() -> "←/→ Adjust · Hold to move faster · ↑ Chapters"
                else -> "←/→ Adjust · Hold to move faster"
            } + "\nOK Confirm · Back Cancel", color = Cloud, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}
