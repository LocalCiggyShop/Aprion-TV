package org.privateview.quietscreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import org.privateview.quietscreen.core.VideoSummary
import org.privateview.quietscreen.presentation.ChannelViewModel

@Composable
fun ChannelScreen(model: ChannelViewModel, home: () -> Unit, play: (VideoSummary) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState(model.listIndex, model.listOffset)
    val back = remember { FocusRequester() }
    val cards = remember { mutableMapOf<String, FocusRequester>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.page) {
        val index = state.videos.indexOfFirst { it.id.value == model.focusedVideo }.takeIf { it >= 0 }
        if (index != null) {
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
            repeat(3) { withFrameNanos { } }
            cards[state.videos[index].id.value]?.requestFocus() ?: back.requestFocus()
        } else { grid.scrollToItem(0); repeat(2) { withFrameNanos { } }; back.requestFocus() }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { model.cancel() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { model.listIndex = grid.firstVisibleItemIndex; model.listOffset = grid.firstVisibleItemScrollOffset; model.cancel() }
    }
    fun leave() { model.cancel(); home() }
    BackHandler(onBack = ::leave)
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(state.name, color = Cloud, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("Channel videos · Regular uploads · Newest first", color = Muted, fontSize = 13.sp)
            }
            TvAction("Back to Home", Modifier.focusRequester(back), onClick = ::leave)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Latest videos", enabled = !state.busy, onClick = model::latest)
            if (state.canGoNewer) TvAction("Newer videos", enabled = !state.busy, onClick = model::newer)
            if (state.cursor != null) TvAction("Older videos", enabled = !state.busy, onClick = model::older)
            if (state.busy) TvAction("Cancel loading", onClick = model::cancel)
            if (state.error != null) TvAction("Try again", onClick = model::retry)
        }
        Text(state.message, color = if (state.error == null) Muted else Warning, fontSize = 13.sp)
        LazyVerticalGrid(GridCells.Adaptive(200.dp), state = grid, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.videos, key = { it.id.value }) { video ->
                val focus = remember(video.id) { FocusRequester() }
                DisposableEffect(video.id) { cards[video.id.value] = focus; onDispose { cards.remove(video.id.value) } }
                CompactVideoCard(video, Modifier.focusRequester(focus).onFocusChanged { if (it.isFocused) model.focusedVideo = video.id.value }) {
                    model.focusedVideo = video.id.value; model.listIndex = grid.firstVisibleItemIndex; model.listOffset = grid.firstVisibleItemScrollOffset
                    model.cancel(); play(video)
                }
            }
        }
    }
}
