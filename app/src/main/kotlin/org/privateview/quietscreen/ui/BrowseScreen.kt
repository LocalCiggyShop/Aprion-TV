package org.privateview.quietscreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.*

@Composable
fun BrowseScreen(model: BrowseViewModel, initialFocus: String, search: () -> Unit, settings: () -> Unit,
    about: () -> Unit, exit: () -> Unit, play: (VideoSummary, Genre?) -> Unit) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val column = rememberLazyListState(model.vertical.index, model.vertical.offset)
    val rail = remember { FocusRequester() }
    val initialRail = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rowFocus = remember { mutableMapOf<String, suspend (BrowseFocus) -> Boolean>() }
    val restored = remember { model.focus.takeIf { initialFocus == "home" } }
    val navigation = remember { NavigationQueue() }
    fun cancelNavigation() { navigation.pending = null; navigation.job?.cancel() }
    fun navigate(target: BrowseFocus) {
        navigation.pending = target
        if (navigation.job?.isActive == true) return
        navigation.job = scope.launch {
            var previous: BrowseFocus? = null
            while (navigation.pending != null) {
                val next = navigation.pending ?: break
                navigation.pending = null
                if (next == previous) continue
                previous = next
                val index = rows.indexOfFirst { it.key == next.rowKey }
                if (index >= 0) {
                    val visible = column.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    if (visible == null || visible.offset < 0 || visible.offset + visible.size > column.layoutInfo.viewportEndOffset - column.layoutInfo.afterContentPadding || rowFocus[next.rowKey] == null) {
                        column.scrollToItem(index)
                        // Composition attaches requesters after a lazy row enters the viewport.
                        repeat(3) { withFrameNanos { } }
                    }
                    if (rowFocus[next.rowKey]?.invoke(next) != true) rail.requestFocus()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (restored != null) navigate(restored) else initialRail.requestFocus()
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { model.cancel() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            model.vertical = ScrollPosition(column.firstVisibleItemIndex, column.firstVisibleItemScrollOffset)
            model.cancel()
        }
    }
    BackHandler { if (model.focus != null) { model.focus = null; rail.requestFocus() } else exit() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(176.dp).fillMaxHeight().background(Panel.copy(alpha = .45f))
            .padding(start = 20.dp, end = 16.dp, top = 30.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("APRION TV", color = Mint, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            listOf("home" to "Home", "search" to "Search videos", "settings" to "Settings", "about" to "About").forEach { (id, label) ->
                TvAction(label, Modifier.fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) { model.contentFocused = false; cancelNavigation() } }
                    .then(if (id == "home") Modifier.focusRequester(rail) else Modifier)
                    .then(if (id == initialFocus) Modifier.focusRequester(initialRail) else Modifier)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                            navigate(model.focus ?: BrowseFocus(rows.first().genre, rowKey = rows.first().key)); true
                        } else false
                    }) {
                    when (id) {
                        "home" -> navigate(model.focus ?: BrowseFocus(rows.first().genre, rowKey = rows.first().key))
                        "search" -> search()
                        "settings" -> settings()
                        else -> about()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("No account needed", color = Muted, fontSize = 11.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 30.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Find your next watch", color = Cloud, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(if (model.personalized) "Recommendations shaped by what you watch" else "A little of everything · Choose something you love",
                        color = Muted, fontSize = 13.sp)
                }
                if (rows.any { it.busy }) TvAction("Cancel", Modifier.focusRequester(cancelFocus)
                    .onFocusChanged { if (it.isFocused) { model.contentFocused = false; cancelNavigation() } }) { model.cancel(); rail.requestFocus() }
            }
            Spacer(Modifier.height(18.dp))
            LazyColumn(state = column, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    BrowseShelf(row, model, rowFocus, rail, navigate = ::navigate, onVertical = { direction, videoIndex ->
                        val adjacent = rows.getOrNull(index + direction)
                        if (adjacent != null) {
                            val id = adjacent.videos.getOrNull(videoIndex.coerceAtMost((adjacent.videos.size - 1).coerceAtLeast(0)))?.id?.value
                            navigate(BrowseFocus(adjacent.genre, id, column = videoIndex, rowKey = adjacent.key))
                        } else if (direction < 0) {
                            if (rows.any { it.busy }) cancelFocus.requestFocus() else rail.requestFocus()
                        }
                    }, play = play)
                }
            }
            Text("← → Browse a row    ↑ ↓ Change row    OK Play    Back Return", color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(start = 26.dp, bottom = 14.dp))
        }
    }
}

@Composable
private fun BrowseShelf(row: BrowseRow, model: BrowseViewModel,
    focusers: MutableMap<String, suspend (BrowseFocus) -> Boolean>, rail: FocusRequester,
    navigate: (BrowseFocus) -> Unit, onVertical: (Int, Int) -> Unit, play: (VideoSummary, Genre?) -> Unit) {
    val saved = model.horizontal[row.key] ?: ScrollPosition()
    val list = rememberLazyListState(saved.index, saved.offset)
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val action = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(row.key, row.loaded, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.loadRow(row.key) }
    }
    val currentRow by rememberUpdatedState(row)
    var pendingCard by remember { mutableStateOf(false) }
    var desiredColumn by remember { mutableIntStateOf(0) }
    DisposableEffect(row.key) {
        focusers[row.key] = { target ->
            desiredColumn = target.column
            pendingCard = !target.action && currentRow.videos.isEmpty()
            var acquired = false
            repeat(4) {
                if (!acquired) {
                    val snapshot = currentRow
                    val index = snapshot.videos.indexOfFirst { it.id.value == target.videoId }
                    val chosen = if (target.action || snapshot.videos.isEmpty()) snapshot.videos.size
                        else if (index >= 0) index else target.column.coerceIn(0, snapshot.videos.lastIndex)
                    val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == chosen }
                    if (visible == null || visible.offset < 0 || visible.offset + visible.size > list.layoutInfo.viewportEndOffset - list.layoutInfo.afterContentPadding) {
                        list.scrollToItem(chosen)
                        withFrameNanos { }
                    }
                    val key = snapshot.videos.getOrNull(chosen)?.id?.value ?: "action"
                    acquired = requesters[key]?.requestFocus(FocusDirection.Enter) == true
                    if (!acquired) withFrameNanos { }
                }
            }
            acquired
        }
        onDispose {
            model.horizontal[row.key] = ScrollPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            focusers.remove(row.key)
        }
    }
    LaunchedEffect(row.videos.size) {
        val focused = model.focus
        if (row.videos.isNotEmpty() && model.contentFocused && focused != null && focused.rowKey == row.key && focused.action) {
            if (pendingCard) {
                pendingCard = false
                val index = desiredColumn.coerceIn(0, row.videos.lastIndex)
                list.scrollToItem(index)
                repeat(3) { withFrameNanos { } }
                if (model.contentFocused && model.focus == focused) requesters[row.videos[index].id.value]?.requestFocus()
            } else {
                // Keep the paging action attached when appended cards move it further right.
                list.scrollToItem(row.videos.size)
                repeat(3) { withFrameNanos { } }
                if (model.contentFocused && model.focus == focused) action.requestFocus()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, color = Cloud, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(row.hint, color = Muted, fontSize = 12.sp)
        }
        LazyRow(state = list, modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(4.dp)) {
            itemsIndexed(row.videos, key = { _, video -> video.id.value }) { index, video ->
                val focus = remember(video.id) { FocusRequester() }
                DisposableEffect(video.id) { requesters[video.id.value] = focus; onDispose { requesters.remove(video.id.value) } }
                VideoCard(video, Modifier.focusRequester(focus).onFocusChanged {
                    if (it.isFocused) { model.contentFocused = true; pendingCard = false; desiredColumn = index; model.focus = BrowseFocus(row.genre, video.id.value, column = index, rowKey = row.key) }
                }.onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.DirectionLeft -> {
                            if (index == 0) rail.requestFocus() else navigate(BrowseFocus(row.genre, row.videos[index - 1].id.value, column = index - 1, rowKey = row.key))
                            true
                        }
                        Key.DirectionRight -> {
                            val next = row.videos.getOrNull(index + 1)
                            if (next != null || row.canLoad) navigate(BrowseFocus(row.genre, next?.id?.value, action = next == null, column = index + 1, rowKey = row.key))
                            true
                        }
                        Key.DirectionUp -> { onVertical(-1, index); true }
                        Key.DirectionDown -> { onVertical(1, index); true }
                        else -> false
                    }
                }, resumeMs = row.resumes[video.id.value]) {
                    model.focus = BrowseFocus(row.genre, video.id.value, column = index, rowKey = row.key)
                    model.horizontal[row.key] = ScrollPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                    play(video, row.genre ?: row.origins[video.id.value])
                }
            }
            if (row.canLoad) item(key = "action") {
                DisposableEffect(action) { requesters["action"] = action; onDispose { requesters.remove("action") } }
                Column(Modifier.width(224.dp).height(205.dp).background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(when {
                        row.error != null -> row.error.userMessage()
                        row.busy -> "Finding videos…"
                        row.videos.isEmpty() -> "No videos here yet. Try again or explore another genre."
                        row.cursor != null -> "Keep exploring ${row.label.lowercase()}"
                        else -> "You've reached the end of this row. Search for more."
                    }, color = Muted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    TvAction(when { row.busy -> "Cancel loading"; row.error != null -> "Try again"; row.cursor != null -> "More videos"; else -> "Refresh row" },
                        Modifier.focusRequester(action).onFocusChanged {
                            if (it.isFocused) { model.contentFocused = true; model.focus = BrowseFocus(row.genre, action = true, column = desiredColumn, rowKey = row.key) }
                        }
                            .onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                    Key.DirectionLeft -> {
                                        if (row.videos.isEmpty()) rail.requestFocus()
                                        else navigate(BrowseFocus(row.genre, row.videos.last().id.value, column = row.videos.lastIndex, rowKey = row.key))
                                        true
                                    }
                                    Key.DirectionRight -> true
                                    Key.DirectionUp -> { onVertical(-1, desiredColumn); true }
                                    Key.DirectionDown -> { onVertical(1, desiredColumn); true }
                                    else -> false
                                }
                            }) {
                        if (row.busy) model.cancel() else model.loadRow(row.key, more = row.cursor != null, retry = true)
                    }
                }
            }
        }
    }
}

/** One active scroll/focus operation and one latest queued destination, never an unbounded key queue. */
private class NavigationQueue {
    var pending: BrowseFocus? = null
    var job: Job? = null
}

@Composable
private fun VideoCard(video: VideoSummary, modifier: Modifier, resumeMs: Long? = null, play: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = play, modifier = modifier.width(224.dp).height(205.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) Mint else Panel, RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(0.dp),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        colors = ButtonDefaults.colors(containerColor = Panel, contentColor = Cloud, focusedContainerColor = Panel, focusedContentColor = Cloud)) {
        Column(Modifier.fillMaxSize()) {
            Box {
                Thumbnail(video.thumbnail, Modifier.fillMaxWidth().height(126.dp))
                if (resumeMs != null) {
                    Text("Resume ${timeLabel(resumeMs)}", color = Cloud, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(7.dp).background(Ink.copy(alpha = .9f), RoundedCornerShape(4.dp)).padding(4.dp))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Panel)) {
                        Box(Modifier.fillMaxWidth((resumeMs.toFloat() / ((video.durationSeconds ?: 1) * 1000)).coerceIn(0f, 1f)).height(3.dp).background(Mint))
                    }
                }
                Text(if (video.live) "LIVE" else video.durationSeconds?.let { timeLabel(it * 1000) } ?: "VIDEO",
                    color = Cloud, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                        .background(Ink.copy(alpha = .9f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.channel, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
