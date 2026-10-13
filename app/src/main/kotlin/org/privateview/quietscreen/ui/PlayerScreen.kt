package org.privateview.quietscreen.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.privateview.quietscreen.AppSettings
import org.privateview.quietscreen.core.StreamResolver
import org.privateview.quietscreen.core.VideoSummary
import org.privateview.quietscreen.core.WatchTime
import org.privateview.quietscreen.core.Genre
import org.privateview.quietscreen.core.GenreClassifier
import org.privateview.quietscreen.playback.PlayerController

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(video: VideoSummary, resolver: StreamResolver, settings: AppSettings,
    watched: (Long, Long, Genre?) -> Unit = { _, _, _ -> }, startPositionMs: Long = 0,
    progress: (Long, Long, Boolean, Genre?) -> Unit = { _, _, _, _ -> },
    recommendations: org.privateview.quietscreen.presentation.BrowseViewModel? = null,
    playNext: (VideoSummary, Genre?) -> Unit = { _, _ -> }, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(video.id) { PlayerController(context, resolver, scope) }
    val status by controller.status.collectAsStateWithLifecycle()
    val view = remember(controller) { PlayerView(context).apply {
        player = controller.player; useController = false; isFocusable = false; isFocusableInTouchMode = false
    } }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    val watchTime = remember(controller) { WatchTime() }
    val recordWatch by rememberUpdatedState(watched)
    val recordProgress by rememberUpdatedState(progress)
    var checkpoint by remember { mutableLongStateOf(0) }
    val exit = remember(controller) { PlayerExit() }
    fun knownDuration(): Long = controller.player.duration.takeIf { it > 0 }
        ?: controller.plan?.durationMs?.takeIf { it > 0 }
        ?: video.durationSeconds?.takeIf { it in 1..604800 }?.times(1000)
        ?: duration
    fun detectedGenre(): Genre? = controller.plan?.genre
        ?: GenreClassifier.classify(video.copy(title = controller.plan?.title ?: video.title))
    fun leavePlayer() { exit.release(); back() }

    LaunchedEffect(controller) {
        // External captions failed qualification; keep them disabled in this trial.
        controller.setDefaultCaptions(false)
        controller.start(video.id, settings.maxHeight, startPositionMs = startPositionMs)
    }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && !released) {
                position = controller.player.currentPosition.coerceAtLeast(0)
                duration = knownDuration()
                playing = controller.player.isPlaying
                watchTime.sample(android.os.SystemClock.elapsedRealtime(), position, playing)
                val now = android.os.SystemClock.elapsedRealtime()
                if (playing && ((checkpoint == 0L && position >= 1_000) || (checkpoint != 0L && now - checkpoint >= 5_000))) {
                    checkpoint = now
                    recordProgress(position, duration, false, detectedGenre())
                }
                delay(500)
            }
        }
    }
    DisposableEffect(controller, lifecycle) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val watchListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!released) buffering = playbackState == Player.STATE_BUFFERING
                if (!released && playbackState == Player.STATE_ENDED) recordProgress(controller.player.currentPosition.coerceAtLeast(0),
                    knownDuration(), true, detectedGenre())
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!released) watchTime.sample(android.os.SystemClock.elapsedRealtime(), controller.player.currentPosition.coerceAtLeast(0), isPlaying)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                watchTime.discontinuity()
            }
        }
        controller.player.addListener(watchListener)
        fun release() {
            if (released) return
            watchTime.sample(android.os.SystemClock.elapsedRealtime(), controller.player.currentPosition.coerceAtLeast(0), false)
            recordProgress(controller.player.currentPosition.coerceAtLeast(0), knownDuration(),
                controller.player.playbackState == Player.STATE_ENDED, detectedGenre())
            recordWatch(watchTime.watchedMs, knownDuration(), detectedGenre())
            controller.player.removeListener(watchListener)
            released = true; view.player = null; controller.release()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        exit.release = ::release
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); release() }
    }
    val recommendationRows = recommendations?.rows?.collectAsStateWithLifecycle()?.value
    val recommendationRow = recommendationRows?.firstOrNull { it.recommended }
        ?: org.privateview.quietscreen.presentation.BrowseRow(null, loaded = true, recommended = true)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        PlayerOverlay(PlayerOverlayInfo(video, position, duration, playing,
            controller.player.playbackState == Player.STATE_ENDED, status.text, status.renderedHeight, status.error,
            controller.plan != null && !released,
            !released && controller.plan != null && controller.player.playbackState != Player.STATE_IDLE && duration > 0,
            status.requestedHeight, status.fixedQuality, status.trackRevision, buffering), recommendationRow,
            loadRecommendations = { refresh -> recommendations?.loadRow("recommended", retry = refresh) },
            cancelRecommendations = { recommendations?.cancel() },
            togglePlayback = {
                if (!released) {
                    if (controller.player.playbackState == Player.STATE_ENDED) { controller.player.seekTo(0); controller.player.play() }
                    else if (controller.player.playWhenReady) controller.player.pause() else controller.player.play()
                }
            }, setPlaying = { play -> if (!released) { if (play) controller.player.play() else controller.player.pause() } },
            seek = { delta -> if (!released) controller.seekBy(delta) },
            playNext = { next, genre -> if (!released && next.id != video.id) { exit.release(); playNext(next, genre) } },
            back = ::leavePlayer, retry = { if (!released) controller.start(video.id, settings.maxHeight, startPositionMs = position.takeIf { it > 0 } ?: startPositionMs) },
            qualityChoices = { listOf("Auto (up to ${settings.maxHeight}p)" to { controller.selectQuality(null, settings.maxHeight) }) +
                controller.qualityHeights().map { height -> "${height}p" to { controller.selectQuality(height, settings.maxHeight) } } },
            audioChoices = { controller.audioChoices().map { choice -> (choice.label + if (choice.selected) " ✓" else "") to { controller.selectAudio(choice.key) } } })
    }
}

private class PlayerExit { var release: () -> Unit = {} }
