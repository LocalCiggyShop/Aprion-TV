package org.privateview.quietscreen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.tv.material3.MaterialTheme
import org.privateview.quietscreen.core.VideoSummary
import org.privateview.quietscreen.extractor.NewPipeCatalog
import org.privateview.quietscreen.presentation.SearchViewModel
import org.privateview.quietscreen.presentation.BrowseViewModel
import org.privateview.quietscreen.core.Genre
import org.privateview.quietscreen.ui.*
import org.privateview.quietscreen.updates.UpdateManager

enum class Screen { HOME, SEARCH, SETTINGS, ABOUT, PLAYER }

class MainActivity : ComponentActivity() {
    private val catalog = NewPipeCatalog()
    private lateinit var searchModel: SearchViewModel
    private lateinit var browseModel: BrowseViewModel
    private lateinit var personalization: Personalization
    private lateinit var continuing: ContinueWatching
    private var startPositionMs = 0L
    private lateinit var settings: AppSettings
    private var screen by mutableStateOf(Screen.HOME)
    private var selected by mutableStateOf<VideoSummary?>(null)
    private var homeFocus by mutableStateOf("home")
    private var playerOrigin = Screen.SEARCH
    private var selectedGenre: Genre? = null
    private lateinit var updates: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        personalization = Personalization(this)
        continuing = ContinueWatching(this)
        updates = UpdateManager.get(this)
        searchModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(catalog) as T
        })[SearchViewModel::class.java]
        browseModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowseViewModel(NewPipeCatalog()) as T
        })[BrowseViewModel::class.java]
        browseModel.arrange(personalization.profile(), personalization.enabled)
        browseModel.continueWatching(continuing.videos())
        setContent {
            MaterialTheme {
                LaunchedEffect(screen) {
                    if (screen == Screen.HOME || screen == Screen.SETTINGS) updates.enterForeground()
                    else updates.leaveForeground()
                }
                Box(Modifier.fillMaxSize().background(Ink)) {
                    when (screen) {
                        Screen.HOME -> BrowseScreen(browseModel,
                            initialFocus = homeFocus,
                            search = { homeFocus = "search"; screen = Screen.SEARCH }, settings = { homeFocus = "settings"; screen = Screen.SETTINGS },
                            about = { homeFocus = "about"; screen = Screen.ABOUT }, exit = { finish() },
                            play = { video, genre ->
                                homeFocus = "home"; browseModel.cancel(); playerOrigin = Screen.HOME
                                selectedGenre = genre ?: continuing.genre(video.id)
                                startPositionMs = if (genre == null) continuing.position(video.id) else 0
                                selected = video; screen = Screen.PLAYER
                            })
                        Screen.SEARCH -> SearchScreen(searchModel, home = { searchModel.cancel(); screen = Screen.HOME }) {
                            searchModel.cancel(); playerOrigin = Screen.SEARCH; selectedGenre = null; startPositionMs = 0
                            selected = it; screen = Screen.PLAYER
                        }
                        Screen.SETTINGS -> SettingsScreen(settings, updates, personalization,
                            clearSession = { searchModel.clear(); continuing.clear(); browseModel.clear(); ThumbnailLoader.clear() }) {
                            browseModel.arrange(personalization.profile(), personalization.enabled); screen = Screen.HOME
                        }
                        Screen.ABOUT -> AboutScreen { screen = Screen.HOME }
                        Screen.PLAYER -> selected?.let { video ->
                            val genre = selectedGenre
                            PlayerScreen(video, catalog, settings,
                                watched = { time, duration -> personalization.record(genre, time, duration) }, startPositionMs = startPositionMs,
                                progress = { position, duration, ended ->
                                    continuing.record(video, position, duration, ended, genre)
                                    browseModel.continueWatching(continuing.videos())
                                }) {
                                selected = null; screen = playerOrigin
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updates.isInitialized && (screen == Screen.HOME || screen == Screen.SETTINGS)) updates.enterForeground()
    }

    override fun onStop() {
        if (::updates.isInitialized) updates.leaveForeground()
        // The player's ON_STOP observer releases synchronously. Do not auto-resume
        // a selected video when returning from Home/sleep/another app.
        if (screen == Screen.PLAYER) { selected = null; screen = playerOrigin }
        if (::searchModel.isInitialized) searchModel.cancel()
        if (::browseModel.isInitialized) browseModel.cancel()
        ThumbnailLoader.cancelPending()
        catalog.cancelPending()
        super.onStop()
    }
}
