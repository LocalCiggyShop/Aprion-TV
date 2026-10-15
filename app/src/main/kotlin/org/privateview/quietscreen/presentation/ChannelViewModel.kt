package org.privateview.quietscreen.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.privateview.quietscreen.core.*

data class ChannelState(val id: ChannelId? = null, val name: String = "Channel", val videos: List<VideoSummary> = emptyList(),
    val page: Int = 0, val canGoNewer: Boolean = false, val cursor: String? = null, val busy: Boolean = false,
    val error: ErrorCode? = null, val message: String = "Loading channel…")

class ChannelViewModel(private val catalog: ChannelCatalog) : ViewModel() {
    private val mutable = MutableStateFlow(ChannelState())
    val state = mutable.asStateFlow()
    private val pages = ChannelPages()
    private var source: VideoSummary? = null
    private var job: Job? = null
    private var generation = 0
    private var retryOlder = false
    var focusedVideo: String? = null
    var listIndex = 0
    var listOffset = 0

    fun open(video: VideoSummary) {
        val id = video.channelId?.let { runCatching { ChannelId(it) }.getOrNull() }
        if (id != null && id == mutable.value.id && pages.current != null) return
        clear(); source = video
        mutable.value = ChannelState(id = id, name = video.channel.ifBlank { "Channel" })
        load(false)
    }
    fun latest() { cancel(); pages.clear(); resetFocus(); mutable.value = mutable.value.copy(videos = emptyList(), page = 0, canGoNewer = false, cursor = null); load(false) }
    fun newer() { if (!mutable.value.busy && pages.newer()) accept() }
    fun older() {
        if (mutable.value.busy) return
        if (pages.older()) accept() else if (mutable.value.cursor != null) load(true)
    }
    fun retry() = load(retryOlder)
    private fun resetFocus() { focusedVideo = null; listIndex = 0; listOffset = 0 }
    private fun accept() {
        val page = pages.current ?: return
        resetFocus()
        mutable.value = mutable.value.copy(videos = page.videos, page = page.number, cursor = page.cursor,
            canGoNewer = pages.canGoNewer, busy = false, error = null,
            name = page.videos.firstOrNull()?.channel?.takeIf { it.isNotBlank() } ?: mutable.value.name,
            message = if (page.videos.isEmpty()) "No public uploads on this page." else "Newest first · Page ${page.number}")
    }
    private fun load(older: Boolean) {
        if (mutable.value.busy || source == null) return
        val cursor = if (older) mutable.value.cursor ?: return else null
        retryOlder = older
        generation++; val current = generation
        catalog.cancelPending(); job?.cancel()
        mutable.value = mutable.value.copy(busy = true, error = null, message = "Loading channel…")
        job = viewModelScope.launch {
            try {
                val id = mutable.value.id ?: catalog.channelForVideo(source!!.id)
                ensureActive()
                if (current != generation) return@launch
                mutable.value = mutable.value.copy(id = id)
                val page = catalog.channel(id, cursor)
                ensureActive()
                if (current != generation) return@launch
                if (!older) pages.clear()
                pages.add(page); accept()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) {
                val error = (e as? AppException)?.code ?: ErrorCode.UPSTREAM_CHANGED
                mutable.value = mutable.value.copy(busy = false, error = error, message = error.userMessage())
            } }
        }
    }
    fun cancel() {
        generation++; job?.cancel(); catalog.cancelPending()
        mutable.value = mutable.value.copy(busy = false,
            message = if (mutable.value.busy) "Loading cancelled. Choose Latest videos to reload." else mutable.value.message)
    }
    fun clear() { cancel(); pages.clear(); source = null; resetFocus(); mutable.value = ChannelState() }
    override fun onCleared() { cancel() }
}
