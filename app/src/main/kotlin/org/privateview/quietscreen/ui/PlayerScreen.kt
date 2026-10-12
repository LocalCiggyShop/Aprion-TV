package org.privateview.quietscreen.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.privateview.quietscreen.AppSettings
import org.privateview.quietscreen.core.StreamResolver
import org.privateview.quietscreen.core.VideoSummary
import org.privateview.quietscreen.playback.PlayerController

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(video: VideoSummary, resolver: StreamResolver, settings: AppSettings, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(video.id) { PlayerController(context, resolver, scope) }
    val status by controller.status.collectAsStateWithLifecycle()
    val view = remember(controller) { PlayerView(context).apply {
        player = controller.player; useController = false; isFocusable = false; isFocusableInTouchMode = false
    } }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    var controls by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf<String?>(null) }
    var lastControl by remember { mutableStateOf("play") }
    var interaction by remember { mutableLongStateOf(0) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        // External captions failed qualification; keep them disabled in this trial.
        controller.setDefaultCaptions(false)
        controller.start(video.id, settings.maxHeight)
    }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && !released) {
                position = controller.player.currentPosition.coerceAtLeast(0)
                duration = controller.player.duration.takeIf { it > 0 } ?: 0
                playing = controller.player.isPlaying
                delay(500)
            }
        }
    }
    DisposableEffect(controller, lifecycle) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fun release() {
            released = true; view.player = null; controller.release()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); release() }
    }
    LaunchedEffect(controls, menu, interaction, playing) {
        if (controls && menu == null && playing) {
            delay(8000)
            controls = false
        }
    }
    LaunchedEffect(controls, menu) {
        withFrameNanos { }
        if (menu == null) {
            if (!controls) rootFocus.requestFocus()
            else when (lastControl) {
                "quality" -> qualityFocus.requestFocus()
                "audio" -> audioFocus.requestFocus()
                else -> playFocus.requestFocus()
            }
        }
    }
    BackHandler { if (menu != null) { menu = null; controls = true } else back() }

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || released) false else {
            interaction++
            when (event.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (controller.player.playWhenReady) controller.player.pause() else controller.player.play(); controls = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { controller.player.play(); controls = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { controller.player.pause(); controls = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { controller.seekBy(-10000); controls = true; true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { controller.seekBy(10000); controls = true; true }
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    if (!controls) { controls = true; true } else false
                }
                else -> false
            }
        }
    }.focusable()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (menu == null && (controls || !playing || status.error != null)) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .96f))))
                .padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(video.title, color = Cloud, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status.text + if (status.renderedHeight > 0) " · ${status.renderedHeight}p" else "",
                    color = if (status.error == null) Muted else Color(0xFFFFD1AA), fontSize = 14.sp,
                    modifier = Modifier.semantics { contentDescription = "Playback ${status.text}; rendered ${status.renderedHeight}p" })
                Box(Modifier.fillMaxWidth().height(3.dp).background(Panel)) {
                    Box(Modifier.fillMaxWidth(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f).height(3.dp).background(Mint))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${timeLabel(position)} / ${timeLabel(duration)}", color = Muted, fontSize = 13.sp)
                    Text(if (status.fixedQuality) "Fixed source · up to ${status.requestedHeight}p" else "Auto · up to ${status.requestedHeight}p", color = Muted, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction("Back", onClick = back)
                    TvAction("−10 s", enabled = duration > 0) { controller.seekBy(-10000) }
                    TvAction(if (playing) "Pause" else if (controller.player.playbackState == Player.STATE_ENDED) "Replay" else "Play", Modifier.focusRequester(playFocus)) {
                        lastControl = "play"
                        if (controller.player.playbackState == Player.STATE_ENDED) { controller.player.seekTo(0); controller.player.play() }
                        else if (controller.player.playWhenReady) controller.player.pause() else controller.player.play()
                    }
                    TvAction("+10 s", enabled = duration > 0) { controller.seekBy(10000) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction("Quality", Modifier.focusRequester(qualityFocus), enabled = controller.plan != null) { lastControl = "quality"; menu = "quality" }
                    TvAction("Audio", Modifier.focusRequester(audioFocus), enabled = controller.plan != null) { lastControl = "audio"; menu = "audio" }
                }
                if (status.error != null) TvAction("Try playback again") { controller.start(video.id, settings.maxHeight) }
            }
        }
        menu?.let { currentMenu ->
            val choices: List<Pair<String, () -> Unit>> = when (currentMenu) {
                "quality" -> listOf("Auto (up to ${settings.maxHeight}p)" to { controller.selectQuality(null, settings.maxHeight) }) +
                    controller.qualityHeights().map { height -> "${height}p · fixed source" to { controller.selectQuality(height, settings.maxHeight) } }
                else -> controller.audioChoices().map { choice -> (choice.label + if (choice.selected) " ✓" else "") to { controller.selectAudio(choice.key) } }
            }
            ChoicePanel(currentMenu.replaceFirstChar { it.uppercase() }, choices,
                close = { menu = null; controls = true })
        }
    }
}

@Composable
private fun ChoicePanel(title: String, choices: List<Pair<String, () -> Unit>>, explanation: String? = null, close: () -> Unit) {
    val first = remember(title) { FocusRequester() }
    LaunchedEffect(title) { withFrameNanos { }; first.requestFocus() }
    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(400.dp).heightIn(max = 420.dp).background(Panel).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Cloud, fontSize = 25.sp)
            if (explanation != null) Text(explanation, color = Muted, fontSize = 15.sp)
            if (choices.isEmpty()) Text("No other tracks are available for this source.", color = Muted, fontSize = 16.sp)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(choices) { index, choice ->
                    TvAction(choice.first, Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)) { choice.second(); close() }
                }
            }
            TvAction("Close", if (choices.isEmpty()) Modifier.focusRequester(first) else Modifier, onClick = close)
        }
    }
}
