package org.privateview.quietscreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.presentation.BrowseRow
import kotlin.math.cos
import kotlin.math.sin

internal data class PlayerOverlayInfo(val video: VideoSummary, val position: Long, val duration: Long,
    val playing: Boolean, val ended: Boolean, val status: String, val renderedHeight: Int = 0,
    val error: ErrorCode? = null, val hasPlan: Boolean = false, val canSeek: Boolean = false,
    val requestedHeight: Int = 1080, val fixedQuality: Boolean = false, val choicesRevision: Int = 0,
    val buffering: Boolean = false)

/** Pure presentation: decoder/lifecycle/history stay in PlayerScreen. */
@Composable
internal fun PlayerOverlay(info: PlayerOverlayInfo, row: BrowseRow,
    loadRecommendations: (Boolean) -> Unit, cancelRecommendations: () -> Unit,
    togglePlayback: () -> Unit, setPlaying: (Boolean) -> Unit, seek: (Long) -> Unit,
    playNext: (VideoSummary, Genre?) -> Unit, back: () -> Unit, retry: () -> Unit,
    qualityChoices: () -> List<Pair<String, () -> Unit>>, audioChoices: () -> List<Pair<String, () -> Unit>>) {
    var visible by remember(info.video.id) { mutableStateOf(true) }
    var area by remember(info.video.id) { mutableStateOf(PlayerArea.TRANSPORT) }
    var menu by remember(info.video.id) { mutableStateOf<String?>(null) }
    var lastOption by remember(info.video.id) { mutableStateOf("captions") }
    var interaction by remember(info.video.id) { mutableLongStateOf(0) }
    var seekFeedback by remember(info.video.id) { mutableStateOf<String?>(null) }
    var seekPulse by remember(info.video.id) { mutableLongStateOf(0) }
    val videoFocus = remember { FocusRequester() }
    val transportFocus = remember { FocusRequester() }
    val captionsFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val shelfAction = remember { FocusRequester() }
    val shelf = rememberLazyListState()
    val cardsFocus = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    var navigation by remember { mutableStateOf<Job?>(null) }
    val rawCards = row.videos.filterNot { it.id == info.video.id }.take(12)
    var pinnedCards by remember(info.video.id) { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var pinnedOrigins by remember(info.video.id) { mutableStateOf<Map<String, Genre>>(emptyMap()) }
    var shelfIndex by remember { mutableIntStateOf(0) }
    var waitingForCard by remember { mutableStateOf(false) }
    val cards = if (area == PlayerArea.RECOMMENDATIONS && pinnedCards.isNotEmpty()) pinnedCards else rawCards
    val currentArea by rememberUpdatedState(area)
    fun freezeShelf() { pinnedCards = rawCards; pinnedOrigins = row.origins.filterKeys { key -> rawCards.any { it.id.value == key } } }
    fun focusShelf(index: Int) {
        navigation?.cancel()
        navigation = scope.launch {
            repeat(3) { withFrameNanos { } }
            if (currentArea != PlayerArea.RECOMMENDATIONS) return@launch
            if (cards.isEmpty()) { shelfAction.requestFocus(); return@launch }
            val chosen = index.coerceIn(0, cards.lastIndex)
            shelf.scrollToItem(chosen)
            repeat(3) { withFrameNanos { } }
            if (currentArea == PlayerArea.RECOMMENDATIONS) cardsFocus[cards[chosen].id.value]?.requestFocus()
        }
    }
    fun enterShelf() {
        visible = true; area = PlayerArea.RECOMMENDATIONS; freezeShelf()
        waitingForCard = rawCards.isEmpty(); focusShelf(shelfIndex)
    }
    fun closeMenu() {
        if (menu == "quality" || menu == "audio") menu = "settings"
        else { menu = null; area = PlayerArea.OPTIONS; visible = true }
    }
    fun showTransport() { navigation?.cancel(); visible = true; area = PlayerArea.TRANSPORT }
    fun hideControls() {
        navigation?.cancel(); menu = null; visible = false; area = PlayerArea.VIDEO
        pinnedCards = emptyList(); pinnedOrigins = emptyMap(); waitingForCard = false
    }
    fun seekBy(delta: Long) {
        if (info.canSeek) { seek(delta); seekFeedback = if (delta < 0) "−10 seconds" else "+10 seconds" }
        else seekFeedback = "Seeking isn't available yet"
        seekPulse++
    }
    val overlayVisible = visible
    // New failures reveal recovery once; Back can still dismiss an error menu.
    LaunchedEffect(info.error, info.ended) { if (info.error != null || info.ended) showTransport() }
    LaunchedEffect(overlayVisible, menu) {
        if (overlayVisible && menu == null) loadRecommendations(false) else cancelRecommendations()
    }
    DisposableEffect(Unit) { onDispose { navigation?.cancel(); cancelRecommendations() } }
    LaunchedEffect(visible, area, menu, interaction, info.playing, info.error) {
        if (visible && menu == null && playerAutoHide(info.playing, area, info.error != null)) {
            delay(8000); hideControls()
        }
    }
    LaunchedEffect(seekPulse) { if (seekFeedback != null) { delay(1000); seekFeedback = null } }
    LaunchedEffect(area, menu, overlayVisible) {
        if (menu == null) {
            repeat(2) { withFrameNanos { } }
            when {
                !overlayVisible -> videoFocus.requestFocus()
                area == PlayerArea.OPTIONS -> if (lastOption == "settings") settingsFocus.requestFocus() else captionsFocus.requestFocus()
                area == PlayerArea.TRANSPORT || area == PlayerArea.VIDEO -> transportFocus.requestFocus()
            }
        }
        if (area != PlayerArea.RECOMMENDATIONS) { pinnedCards = emptyList(); pinnedOrigins = emptyMap(); waitingForCard = false }
    }
    LaunchedEffect(rawCards.map { it.id }, area) {
        if (area == PlayerArea.RECOMMENDATIONS && waitingForCard && rawCards.isNotEmpty()) {
            freezeShelf(); waitingForCard = false; focusShelf(shelfIndex)
        }
    }
    BackHandler {
        interaction++
        when {
            overlayVisible || menu != null -> hideControls()
            else -> back()
        }
    }
    Box(Modifier.fillMaxSize().focusRequester(videoFocus).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else {
            interaction++
            when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); visible = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { setPlaying(true); visible = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { setPlaying(false); visible = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); true }
                else -> {
                    val direction = when (event.key) {
                        Key.DirectionLeft -> PlayerDirection.LEFT; Key.DirectionRight -> PlayerDirection.RIGHT
                        Key.DirectionUp -> PlayerDirection.UP; Key.DirectionDown -> PlayerDirection.DOWN; else -> null
                    }
                    if (direction != null) when (playerDirection(if (menu != null) PlayerArea.MENU else area, direction)) {
                        PlayerRemoteAction.SEEK_BACK -> { seekBy(-10_000); true }
                        PlayerRemoteAction.SEEK_FORWARD -> { seekBy(10_000); true }
                        PlayerRemoteAction.SHOW_OPTIONS -> { visible = true; area = PlayerArea.OPTIONS; true }
                        PlayerRemoteAction.SHOW_RECOMMENDATIONS -> {
                            if (info.error != null && (area == PlayerArea.TRANSPORT || area == PlayerArea.VIDEO)) {
                                visible = true; area = PlayerArea.RECOVERY; retryFocus.requestFocus()
                            } else enterShelf()
                            true
                        }
                        PlayerRemoteAction.SHOW_TRANSPORT -> { showTransport(); true }
                        PlayerRemoteAction.HIDE_CONTROLS -> { hideControls(); true }
                        PlayerRemoteAction.NAVIGATE -> if (menu == null && area == PlayerArea.TRANSPORT &&
                            (direction == PlayerDirection.LEFT || direction == PlayerDirection.RIGHT)) {
                            area = PlayerArea.OPTIONS; lastOption = if (direction == PlayerDirection.RIGHT) "captions" else "settings"
                            true
                        } else false
                    } else if (!overlayVisible && (event.key == Key.DirectionCenter || event.key == Key.Enter)) { showTransport(); true } else false
                }
            }
        }
    }.focusable()) {
        if (info.buffering && info.error == null) BufferingIndicator(Modifier
            .align(if (overlayVisible && menu == null) Alignment.TopCenter else Alignment.Center)
            .padding(top = if (overlayVisible && menu == null) 48.dp else 0.dp).zIndex(1f))
        if (seekFeedback != null) Text(seekFeedback!!, color = Cloud, fontSize = 23.sp,
            modifier = Modifier.align(if (seekFeedback!!.startsWith("−")) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 64.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(14.dp)).padding(20.dp))
        if (overlayVisible && menu == null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f), Color.Black.copy(alpha = .94f))))
            .padding(start = 32.dp, end = 32.dp, top = 36.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                var focused by remember { mutableStateOf(false) }
                Button(onClick = togglePlayback, modifier = Modifier.weight(1f).widthIn(max = 570.dp).focusRequester(transportFocus)
                    .onFocusChanged { if (it.isFocused) area = PlayerArea.TRANSPORT; focused = it.isFocused }
                    .border(if (focused) 1.dp else 0.dp, if (focused) Mint else Color.Transparent, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = if (info.ended) "Replay video" else if (info.playing) "Pause video" else "Play video" },
                    contentPadding = PaddingValues(12.dp),
                    scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.colors(containerColor = Color.Black.copy(alpha = .5f), focusedContainerColor = Color.Black.copy(alpha = .7f),
                        contentColor = Cloud, focusedContentColor = Cloud)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlaybackGlyph(if (info.playing) "pause" else "play", Cloud, Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(info.video.title, color = Cloud, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(info.video.channel, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(info.status + if (info.renderedHeight > 0) " · ${info.renderedHeight}p" else "", color = if (info.error == null) Muted else Color(0xFFFFD1AA),
                                fontSize = 11.sp, modifier = Modifier.semantics { contentDescription = "Playback ${info.status}; rendered ${info.renderedHeight}p" })
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverlayIcon("Captions", "captions", captionsFocus, focused = { if (it) { area = PlayerArea.OPTIONS; lastOption = "captions" } },
                        horizontal = { right -> if (right) settingsFocus.requestFocus() else showTransport() }) { lastOption = "captions"; menu = "captions"; area = PlayerArea.MENU }
                    OverlayIcon("Settings", "settings", settingsFocus, focused = { if (it) { area = PlayerArea.OPTIONS; lastOption = "settings" } },
                        horizontal = { right -> if (right) showTransport() else captionsFocus.requestFocus() }) { lastOption = "settings"; menu = "settings"; area = PlayerArea.MENU }
                }
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .3f), RoundedCornerShape(2.dp))
                .semantics { contentDescription = "Playback position ${info.position / 1000} of ${info.duration / 1000} seconds" }) {
                Box(Modifier.fillMaxWidth(if (info.duration > 0) (info.position.toFloat() / info.duration).coerceIn(0f, 1f) else 0f)
                    .height(4.dp).background(Mint, RoundedCornerShape(2.dp)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(info.position), color = Muted, fontSize = 11.sp)
                Text(timeLabel(info.duration), color = Muted, fontSize = 11.sp)
            }
            if (info.error != null) TvAction("Try playback again", Modifier.focusRequester(retryFocus)
                .onFocusChanged { if (it.isFocused) area = PlayerArea.RECOVERY }) { retry(); showTransport() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recommended", color = Cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (area == PlayerArea.RECOMMENDATIONS) "↑ Playback · OK Watch" else "↓ Explore videos", color = Muted, fontSize = 11.sp)
            }
            LazyRow(state = shelf, modifier = Modifier.fillMaxWidth().height(if (area == PlayerArea.RECOMMENDATIONS) 162.dp else 68.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(4.dp)) {
                itemsIndexed(cards, key = { _, video -> video.id.value }) { index, video ->
                    val focus = remember(video.id) { FocusRequester() }
                    DisposableEffect(video.id) { cardsFocus[video.id.value] = focus; onDispose { cardsFocus.remove(video.id.value) } }
                    CompactVideoCard(video, Modifier.focusRequester(focus).onFocusChanged { if (it.isFocused) { area = PlayerArea.RECOMMENDATIONS; shelfIndex = index } }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionLeft -> { if (index > 0) focusShelf(index - 1); true }
                                Key.DirectionRight -> { if (index < cards.lastIndex) focusShelf(index + 1) else shelfAction.requestFocus(); true }
                                else -> false
                            }
                        }) { playNext(video, (if (pinnedCards.isNotEmpty()) pinnedOrigins else row.origins)[video.id.value]) }
                }
                item(key = "action") {
                    Column(Modifier.width(210.dp).height(156.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.Center) {
                        Text(when { row.error != null -> row.error.userMessage(); row.busy -> "Finding videos…"; cards.isEmpty() -> "No suggestions yet. Try again when you're ready."; else -> "More discoveries are waiting on Home." },
                            color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        TvAction(if (row.busy) "Cancel loading" else if (row.error != null) "Try again" else "Refresh", Modifier.focusRequester(shelfAction)
                            .onFocusChanged { if (it.isFocused) area = PlayerArea.RECOMMENDATIONS }
                            .onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                if (cards.isEmpty()) showTransport() else focusShelf(cards.lastIndex); true
                            } else false }) {
                            if (row.busy) cancelRecommendations() else {
                                pinnedCards = emptyList(); pinnedOrigins = emptyMap(); waitingForCard = true; loadRecommendations(true)
                            }
                        }
                    }
                }
            }
            Text(when (area) { PlayerArea.OPTIONS -> "←/→ Choose button · OK Open · ↓ Playback"; PlayerArea.RECOMMENDATIONS -> "←/→ Browse · OK Watch · Back Hide controls";
                else -> "←/→ Choose button · OK Pause / play · ↓ Recommended · Back Hide controls" }, color = Muted, fontSize = 10.sp)
        }
        menu?.let { currentMenu ->
            val choices = remember(currentMenu, info.choicesRevision, info.hasPlan, info.fixedQuality, info.requestedHeight) {
                when (currentMenu) {
                    "settings" -> if (info.hasPlan) listOf(
                        ("Video quality · " + if (info.fixedQuality) "${info.requestedHeight}p" else "Auto") to { menu = "quality" },
                        "Audio track" to { menu = "audio" }) else emptyList()
                    "quality" -> qualityChoices()
                    "audio" -> audioChoices()
                    else -> emptyList()
                }
            }
            PlayerChoicePanel(if (currentMenu == "quality") "Video quality" else currentMenu.replaceFirstChar { it.uppercase() }, choices,
                explanation = when {
                    currentMenu == "captions" -> "Captions aren't available in this version yet."
                    currentMenu == "settings" && !info.hasPlan -> "Quality and audio options will appear once the video loads."
                    else -> null
                }, close = ::closeMenu, select = { choice ->
                    choice(); interaction++
                    if (currentMenu == "quality" || currentMenu == "audio") { menu = null; area = PlayerArea.OPTIONS }
                })
        }
    }
}

@Composable
private fun PlayerChoicePanel(title: String, choices: List<Pair<String, () -> Unit>>, explanation: String?,
    close: () -> Unit, select: (() -> Unit) -> Unit) {
    val first = remember(title) { FocusRequester() }
    val focusTargets = remember(title, choices.size) { List(choices.size + 1) { FocusRequester() } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var movement by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(title, choices.size) { onDispose { movement?.cancel() } }
    fun move(index: Int, direction: Int) {
        movement?.cancel()
        movement = scope.launch {
            val target = (index + direction + focusTargets.size) % focusTargets.size
            if (target < choices.size) list.scrollToItem(target)
            repeat(2) { withFrameNanos { } }
            focusTargets[target].requestFocus()
        }
    }
    fun Modifier.horizontalMenu(index: Int) = onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
            move(index, if (event.key == Key.DirectionRight) 1 else -1); true
        } else false
    }
    LaunchedEffect(title) { repeat(2) { withFrameNanos { } }; first.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).padding(32.dp), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(340.dp).heightIn(max = 420.dp).background(Panel, RoundedCornerShape(18.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Cloud, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            if (explanation != null) Text(explanation, color = Muted, fontSize = 15.sp)
            if (choices.isEmpty() && explanation == null) Text("No other options are available for this source.", color = Muted, fontSize = 15.sp)
            LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(choices) { index, choice ->
                    TvAction(choice.first, Modifier.fillMaxWidth().focusRequester(focusTargets[index])
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier).horizontalMenu(index)) {
                        select(choice.second)
                    }
                }
            }
            TvAction("Return", Modifier.focusRequester(focusTargets.last()).then(if (choices.isEmpty()) Modifier.focusRequester(first) else Modifier)
                .horizontalMenu(choices.size), onClick = close)
            Text("Arrows Choose · OK Select · Back Hide controls", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BufferingIndicator(modifier: Modifier) {
    val rotation by rememberInfiniteTransition(label = "buffering").animateFloat(0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinner")
    Column(modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(18.dp)).padding(20.dp)
        .semantics { contentDescription = "Buffering video" }, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(44.dp)) {
            drawArc(Mint, rotation, 270f, false, style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text("Buffering…", color = Cloud, fontSize = 14.sp)
    }
}

@Composable
private fun OverlayIcon(label: String, glyph: String, focus: FocusRequester, focused: (Boolean) -> Unit,
    horizontal: (Boolean) -> Unit, action: () -> Unit) {
    var selected by remember { mutableStateOf(false) }
    Button(onClick = action, modifier = Modifier.size(42.dp).focusRequester(focus).onFocusChanged { selected = it.isFocused; focused(it.isFocused) }
        .semantics { contentDescription = label }.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) { horizontal(event.key == Key.DirectionRight); true } else false
        }, contentPadding = PaddingValues(10.dp), shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(containerColor = Color.Black.copy(alpha = .5f), focusedContainerColor = Cloud, contentColor = Cloud, focusedContentColor = Ink)) {
        if (glyph == "captions") Text("CC", color = if (selected) Ink else Cloud, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clearAndSetSemantics {}.border(1.dp, if (selected) Ink else Cloud, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp))
        else PlaybackGlyph(glyph, if (selected) Ink else Cloud, Modifier.size(22.dp))
    }
}

@Composable
private fun PlaybackGlyph(kind: String, tint: Color, modifier: Modifier) = Canvas(modifier) {
    val middle = Offset(size.width / 2, size.height / 2)
    when (kind) {
        "settings" -> {
            val radius = size.minDimension * .31f
            drawCircle(tint, radius, middle, style = Stroke(size.minDimension * .1f))
            drawCircle(tint, radius * .35f, middle, style = Stroke(size.minDimension * .07f))
            repeat(8) { n -> val angle = n * Math.PI / 4
                drawLine(tint, middle + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat()),
                    middle + Offset((cos(angle) * radius * 1.5).toFloat(), (sin(angle) * radius * 1.5).toFloat()), strokeWidth = size.minDimension * .1f)
            }
        }
        "pause" -> { drawRect(tint, Offset(size.width * .2f, size.height * .15f), androidx.compose.ui.geometry.Size(size.width * .2f, size.height * .7f))
            drawRect(tint, Offset(size.width * .6f, size.height * .15f), androidx.compose.ui.geometry.Size(size.width * .2f, size.height * .7f)) }
        else -> drawPath(Path().apply { moveTo(size.width * .24f, size.height * .12f); lineTo(size.width * .84f, size.height / 2); lineTo(size.width * .24f, size.height * .88f); close() }, tint)
    }
}

@Composable
private fun CompactVideoCard(video: VideoSummary, modifier: Modifier, play: () -> Unit) {
    var selected by remember { mutableStateOf(false) }
    Button(onClick = play, modifier = modifier.width(200.dp).height(156.dp).onFocusChanged { selected = it.isFocused }
        .border(if (selected) 2.dp else 0.dp, if (selected) Mint else Color.Transparent, RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(0.dp), shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f), colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Panel, contentColor = Cloud, focusedContentColor = Cloud)) {
        Column {
            Box { Thumbnail(video.thumbnail, Modifier.width(200.dp).height(112.dp))
                Text(video.durationSeconds?.let { timeLabel(it * 1000) } ?: "VIDEO", color = Cloud, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(3.dp)).padding(3.dp)) }
            Text(video.title, color = Cloud, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
        }
    }
}
