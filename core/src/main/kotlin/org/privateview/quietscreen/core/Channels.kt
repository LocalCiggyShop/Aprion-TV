package org.privateview.quietscreen.core

@JvmInline
value class ChannelId(val value: String) {
    init { require(Regex("UC[A-Za-z0-9_-]{22}").matches(value)) { "Invalid public channel ID" } }
}

interface ChannelCatalog {
    suspend fun channel(id: ChannelId, cursor: String? = null): CatalogPage
    suspend fun channelForVideo(id: VideoId): ChannelId
    fun cancelPending()
}

data class ChannelBatch(val number: Int, val videos: List<VideoSummary>, val cursor: String?)

/** Four bounded pages, with provider continuations allowing arbitrarily old uploads. */
class ChannelPages {
    private val pages = mutableListOf<ChannelBatch>()
    private var index = 0
    val current get() = pages.getOrNull(index)
    val canGoNewer get() = index > 0
    fun clear() { pages.clear(); index = 0 }
    fun newer(): Boolean = if (canGoNewer) { index--; true } else false
    fun older(): Boolean = if (index < pages.lastIndex) { index++; true } else false
    fun add(page: CatalogPage) {
        val previous = current
        val known = pages.flatMap { it.videos }.map { it.id }.toSet()
        val videos = SearchSort.NEWEST.order(page.videos.filterNot { it.live || it.id in known }.distinctBy { it.id }.take(50))
        pages += ChannelBatch((previous?.number ?: 0) + 1, videos, page.cursor?.takeUnless { it == previous?.cursor })
        if (pages.size > 4) pages.removeAt(0)
        index = pages.lastIndex
    }
}
