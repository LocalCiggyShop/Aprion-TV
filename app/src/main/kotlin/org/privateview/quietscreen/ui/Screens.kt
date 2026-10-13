package org.privateview.quietscreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import org.privateview.quietscreen.AppSettings
import org.privateview.quietscreen.BuildConfig
import org.privateview.quietscreen.Personalization
import org.privateview.quietscreen.ThumbnailLoader
import org.privateview.quietscreen.core.VideoSummary
import org.privateview.quietscreen.presentation.SearchViewModel
import org.privateview.quietscreen.updates.UpdateManager

val Ink = Color(0xFF101114)
val Panel = Color(0xFF1B1D23)
val RaisedPanel = Color(0xFF282B34)
val Accent = Color(0xFFA8B5FF)
val Cloud = Color(0xFFF4F5F7)
val Muted = Color(0xFFADB2C0)
val Warning = Color(0xFFFF9B91)

@Composable
fun TvAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.colors(containerColor = RaisedPanel, contentColor = Cloud,
            focusedContainerColor = Accent, focusedContentColor = Ink)) {
        Text(label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Cloud, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 15.sp)
    }
}

@Composable
fun HomeScreen(initialFocus: String = "search", search: () -> Unit, settings: () -> Unit, about: () -> Unit, exit: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = exit)
    Column(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink, Panel)))
        .padding(horizontal = 48.dp, vertical = 34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("APRION TV", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("WATCH AT YOUR PACE", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("A little more space\nfor what you watch.", color = Cloud, fontSize = 46.sp,
            fontWeight = FontWeight.Bold, lineHeight = 52.sp)
        Text("Find a public video. Settle in. You're in control.", color = Muted, fontSize = 19.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction("Search videos", if (initialFocus == "search") Modifier.focusRequester(first) else Modifier, onClick = search)
            TvAction("Settings", if (initialFocus == "settings") Modifier.focusRequester(first) else Modifier, onClick = settings)
            TvAction("About", if (initialFocus == "about") Modifier.focusRequester(first) else Modifier, onClick = about)
        }
        Spacer(Modifier.weight(1f))
        Text("Use your remote's arrows to move · OK to choose · Back to return", color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun SearchScreen(model: SearchViewModel, home: () -> Unit, play: (VideoSummary) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val input = remember { FocusRequester() }
    val submit = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState(model.listIndex, model.listOffset)
    val restoreId = remember { model.focusedVideo }
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val index = state.videos.indexOfFirst { it.id.value == restoreId }
        if (index >= 0) {
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            withFrameNanos { }
            restore.requestFocus()
        } else { input.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose { model.listIndex = list.firstVisibleItemIndex; model.listOffset = list.firstVisibleItemScrollOffset }
    }
    BackHandler { model.cancel(); keyboard?.hide(); home() }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Heading("Search", "Find something worth watching.")
            TvAction("Home") { keyboard?.hide(); home() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(state.query, model::setQuery,
                modifier = Modifier.weight(1f).focusRequester(input).onFocusChanged { inputFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            keyboard?.hide(); submit.requestFocus(); true
                        } else false
                    }
                    .background(RaisedPanel, RoundedCornerShape(10.dp))
                    .border(if (inputFocused) 2.dp else 1.dp, if (inputFocused) Accent else RaisedPanel, RoundedCornerShape(10.dp)).padding(14.dp),
                singleLine = true, textStyle = TextStyle(color = Cloud, fontSize = 19.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.search() }),
                decorationBox = { inner -> Box { if (state.query.isEmpty()) Text("Title, channel or video ID", color = Muted, fontSize = 19.sp); inner() } })
            TvAction(if (state.busy) "Search again" else "Search", Modifier.focusRequester(submit)) { keyboard?.hide(); model.search() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, color = if (state.error == null) Muted else Warning, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (state.busy) TvAction("Cancel search") { model.cancel() }
            if (state.error != null && !state.busy) TvAction("Try search again") { model.search() }
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(state.videos, key = { _, video -> video.id.value }) { _, video ->
                val focus = if (video.id.value == restoreId) Modifier.focusRequester(restore) else Modifier
                Button(onClick = {
                    model.focusedVideo = video.id.value
                    model.listIndex = list.firstVisibleItemIndex; model.listOffset = list.firstVisibleItemScrollOffset
                    keyboard?.hide(); play(video)
                }, modifier = Modifier.fillMaxWidth().then(focus).onFocusChanged { if (it.isFocused) model.focusedVideo = video.id.value },
                    colors = ButtonDefaults.colors(containerColor = RaisedPanel, contentColor = Cloud, focusedContainerColor = Accent, focusedContentColor = Ink)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(video.thumbnail)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            Text(video.title, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(video.channel + (video.durationSeconds?.let { " · ${timeLabel(it * 1000)}" } ?: ""), fontSize = 13.sp, maxLines = 1)
                        }
                        Text("PLAY", fontSize = 12.sp)
                    }
                }
            }
            if (state.cursor != null) item { TvAction("More results", enabled = !state.busy) { model.search(true) } }
        }
    }
}

@Composable
internal fun Thumbnail(url: String?, modifier: Modifier = Modifier.size(112.dp, 63.dp)) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(url, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { bitmap = url?.let { ThumbnailLoader.load(it) } }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        ?: Box(modifier.background(Ink), contentAlignment = Alignment.Center) { Text("APRION TV", color = Muted, fontSize = 11.sp) }
}

@Composable
fun SettingsScreen(settings: AppSettings, updates: UpdateManager, personalization: Personalization, clearSession: () -> Unit, home: () -> Unit) {
    val updateState by updates.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var height by remember { mutableIntStateOf(settings.maxHeight) }
    var message by remember { mutableStateOf("Preferences stay on this device.") }
    var personalized by remember { mutableStateOf(personalization.enabled) }
    var personalizationMessage by remember { mutableStateOf("All genre categories remain available.") }
    LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = home)
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Settings", "Make yourself comfortable.")
        Text("Video quality", color = Cloud, fontSize = 19.sp)
        Text("Choose the highest quality you'd like to use. Higher quality uses more data. Some videos or TVs may support a lower quality.", color = Muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(480, 720, 1080, 2160).forEachIndexed { index, value ->
                TvAction((if (value == 2160) "4K" else "${value}p") + if (height == value) " ✓" else "",
                    if (index == 0) Modifier.focusRequester(first) else Modifier) { height = value; settings.maxHeight = value }
            }
        }
        Text("To change a video's language or audio track, open Settings while it plays. Captions aren't available yet.", color = Muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Clear browsing session") { clearSession(); message = "Search results and saved watch history cleared. Your learned preferences are kept." }
            TvAction("Reset playback settings") { settings.reset(); height = settings.maxHeight; message = "Playback settings restored to their defaults." }
        }
        Text(message, color = Accent, fontSize = 14.sp)
        Text("Recommended for you", color = Cloud, fontSize = 19.sp)
        Text("Recommended learns which types of videos you enjoy from how long you watch and how much you finish. You'll see more of your favourites, along with fresh discoveries, when you return Home.", color = Muted, fontSize = 14.sp)
        Text("Videos from Search count too when their type can be identified. Learning usually starts after 30 seconds, or after 10 seconds and 80% of a short video. Even brief unfinished watches can appear in Continue watching.", color = Muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Personalization: " + if (personalized) "On" else "Off") {
                personalized = !personalized; personalization.enabled = personalized
                personalizationMessage = if (personalized) "Learning is on. Recommended will use your preferences." else "Learning is paused. Your preferences are kept until you reset them."
            }
            TvAction("Reset personalization") { personalization.reset(); personalizationMessage = "Your learned preferences are erased. Recommended starts fresh." }
        }
        Text(personalizationMessage, color = Accent, fontSize = 14.sp)
        Text("Turn personalization off to pause learning and see a varied mix. Reset personalization erases your learned preferences. Neither option clears Continue watching.", color = Muted, fontSize = 14.sp)
        Text("App updates", color = Cloud, fontSize = 19.sp)
        Text(updateState.message, color = Muted, fontSize = 14.sp)
        updateState.detail?.let { Text(it, color = Muted, fontSize = 12.sp) }
        Text("With automatic updates on, the app checks and downloads updates while Home or Settings is open. Your TV may ask permission to install. Use Check for updates to check now.", color = Muted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Automatic updates: " + if (updateState.automatic) "On" else "Off") { updates.automatic(!updateState.automatic) }
            TvAction("Check for updates", enabled = !updateState.busy) { updates.check() }
            if (updateState.ready) TvAction("Install update", enabled = !updateState.busy) { updates.install() }
        }
        Spacer(Modifier.height(12.dp))
        TvAction("Back to Home", onClick = home)
    }
}

@Composable
fun AboutScreen(home: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    BackHandler(onBack = home)
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Heading("About Aprion TV", "Version ${BuildConfig.VERSION_NAME}")
        Text("An independent player for public videos. No account is needed.", color = Cloud, fontSize = 21.sp)
        Text("Search stays in memory. Playback/update preferences and eight genre scores are private on-device. Continue watching saves up to 24 unfinished video records and 100 completed IDs; Clear browsing session erases them. Backups are disabled. Reset or pause personalization in Settings. No accounts, telemetry or external recommender.\n\nBrowsing, search, thumbnails and playback contact YouTube/CDNs; updates contact GitHub. Providers see normal requests and your IP. Genre scores and saved viewing records are never uploaded. Resuming requests the selected video's public ID from YouTube as ordinary playback does.\n\nVideo availability can change. Restricted/protected videos are unsupported. Back hides playback controls; press Back again to return Home. Home stops playback.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
        Text("Open-source software · GPL-3.0-or-later\nBuilt with AndroidX Media3 and NewPipeExtractor. See the accompanying source and notices.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        TvAction("Back to Home", Modifier.focusRequester(first), onClick = home)
    }
}

fun timeLabel(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
