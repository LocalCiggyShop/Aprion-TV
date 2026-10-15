package org.privateview.quietscreen.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.privateview.quietscreen.core.VideoSummary
import org.privateview.quietscreen.core.viewCountLabel

@Composable
internal fun ViewCount(video: VideoSummary, modifier: Modifier = Modifier, full: Boolean = false, color: Color = Muted) {
    val readable = remember(video.viewCount) { viewCountLabel(video.viewCount, false) }
    val label = remember(video.viewCount, full) { if (full) readable else viewCountLabel(video.viewCount) }
    Text(label, color = color, fontSize = 11.sp, maxLines = 1,
        modifier = modifier.semantics { contentDescription = readable })
}
