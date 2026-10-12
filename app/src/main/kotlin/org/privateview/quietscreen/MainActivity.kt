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
import org.privateview.quietscreen.ui.*
import org.privateview.quietscreen.updates.UpdateManager

enum class Screen { HOME, SEARCH, SETTINGS, ABOUT, PLAYER }

class MainActivity : ComponentActivity() {
    private val catalog = NewPipeCatalog()
    private lateinit var searchModel: SearchViewModel
    private lateinit var settings: AppSettings
    private var screen by mutableStateOf(Screen.HOME)
    private var selected by mutableStateOf<VideoSummary?>(null)
    private var homeFocus by mutableStateOf("search")
    private lateinit var updates: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        updates = UpdateManager.get(this)
        searchModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(catalog) as T
        })[SearchViewModel::class.java]
        setContent {
            MaterialTheme {
                LaunchedEffect(screen) {
                    if (screen == Screen.HOME || screen == Screen.SETTINGS) updates.enterForeground()
                    else updates.leaveForeground()
                }
                Box(Modifier.fillMaxSize().background(Ink)) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            initialFocus = homeFocus,
                            search = { homeFocus = "search"; screen = Screen.SEARCH }, settings = { homeFocus = "settings"; screen = Screen.SETTINGS },
                            about = { homeFocus = "about"; screen = Screen.ABOUT }, exit = { finish() })
                        Screen.SEARCH -> SearchScreen(searchModel, home = { searchModel.cancel(); screen = Screen.HOME }) {
                            selected = it; screen = Screen.PLAYER
                        }
                        Screen.SETTINGS -> SettingsScreen(settings, updates, clearSession = { searchModel.clear(); ThumbnailLoader.clear() }) { screen = Screen.HOME }
                        Screen.ABOUT -> AboutScreen { screen = Screen.HOME }
                        Screen.PLAYER -> selected?.let { video ->
                            PlayerScreen(video, catalog, settings) { selected = null; screen = Screen.SEARCH }
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
        if (screen == Screen.PLAYER) { selected = null; screen = Screen.SEARCH }
        if (::searchModel.isInitialized) searchModel.cancel()
        ThumbnailLoader.cancelPending()
        catalog.cancelPending()
        super.onStop()
    }
}
