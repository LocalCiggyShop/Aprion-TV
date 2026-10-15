package org.privateview.quietscreen.extractor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.privateview.quietscreen.core.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.*
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.*
import org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.coroutineContext

class NewPipeCatalog : VideoCatalog, StreamResolver, ChannelCatalog {
    private val downloader = NewPipeDownloader()
    private val pages = LinkedHashMap<String, Pair<String, Page>>()
    private val channelPath = Regex("/channel/(UC[A-Za-z0-9_-]{22})(?:$|[/?])")
    override fun cancelPending() = downloader.cancel()
    private fun summary(item: StreamInfoItem): VideoSummary? = runCatching {
        val service = NewPipe.getService("YouTube")
        val channelId = channelPath.find(item.uploaderUrl.orEmpty())?.groupValues?.get(1)
        VideoSummary(VideoId(service.streamLHFactory.getId(item.url)), item.name.take(500), item.uploaderName.orEmpty().take(200),
            item.thumbnails.firstOrNull()?.url?.takeIf { runCatching { NetworkPolicy.validate(it, NetworkRole.THUMBNAIL) }.isSuccess },
            item.duration.takeIf { it >= 0 }, item.streamType == StreamType.LIVE_STREAM, channelId,
            runCatching { item.uploadDate?.instant?.toEpochMilli()?.takeIf { it > 0 } }.getOrNull())
    }.getOrNull()

    private suspend fun <T> extract(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            coroutineContext.ensureActive()
            NewPipe.init(downloader, Localization("en", "AU"), ContentCountry("AU"))
            YoutubeParsingHelper.setConsentAccepted(false)
            YoutubeStreamExtractor.setPoTokenProvider(null)
            YoutubeStreamExtractor.setFetchIosClient(false)
            downloader.begin()
            try { block().also { coroutineContext.ensureActive() } }
            catch (e: CancellationException) { throw e }
            catch (e: AppException) { throw e }
            catch (e: PolicyViolation) { throw AppException(ErrorCode.SECURITY_POLICY, e) }
            catch (e: SecurityException) { throw AppException(ErrorCode.SECURITY_POLICY, e) }
            catch (e: SignInConfirmNotBotException) { throw AppException(ErrorCode.SIGN_IN_REQUIRED, e) }
            catch (e: AgeRestrictedContentException) { throw AppException(ErrorCode.RESTRICTED, e) }
            catch (e: GeographicRestrictionException) { throw AppException(ErrorCode.RESTRICTED, e) }
            catch (e: PaidContentException) { throw AppException(ErrorCode.RESTRICTED, e) }
            catch (e: ContentNotAvailableException) { throw AppException(ErrorCode.UNAVAILABLE, e) }
            catch (e: SocketTimeoutException) { throw AppException(ErrorCode.TIMEOUT, e) }
            catch (e: IOException) { throw AppException(ErrorCode.OFFLINE, e) }
            catch (e: Exception) {
                // Explicit local opt-in; never include exception messages, video
                // identifiers, queries, request URLs, response bodies or cookies.
                if (android.util.Log.isLoggable("AprionTVExtractor", android.util.Log.DEBUG)) {
                    var cause: Throwable? = e
                    repeat(5) {
                        cause?.let { failure ->
                            android.util.Log.d("AprionTVExtractor", "failureType=${failure.javaClass.name} frames=" +
                                failure.stackTrace.take(6).joinToString("|") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" })
                        }
                        cause = cause?.cause
                    }
                }
                throw AppException(ErrorCode.UPSTREAM_CHANGED, e)
            }
        }
    }

    override suspend fun search(query: String, cursor: String?) = search(query, cursor, SearchSort.RELEVANCE)
    override suspend fun search(query: String, cursor: String?, sort: SearchSort): CatalogPage {
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 200) throw AppException(ErrorCode.INVALID_QUERY)
        val pageKey = "${sort.name}\u0000$clean"
        return extract {
            val service = NewPipe.getService("YouTube")
            val e = service.getSearchExtractor(clean, listOf("videos"), "")
            val page = try { if (cursor == null) { e.fetchPage(); e.initialPage }
                else { val stored = pages[cursor] ?: throw AppException(ErrorCode.UPSTREAM_CHANGED)
                    if (stored.first != pageKey) throw AppException(ErrorCode.UPSTREAM_CHANGED)
                    e.getPage(stored.second) }
            } catch (_: NothingFoundException) { return@extract CatalogPage(emptyList(), null) }
            // Retain opaque cursors within the bounded cache: cancellation can arrive
            // after extraction succeeds but before the caller accepts the result.
            val items = page.items.filterIsInstance<StreamInfoItem>().take(50).mapNotNull(::summary).distinctBy { it.id }
            val nextPage = page.nextPage
            val token = if (nextPage != null && Page.isValid(nextPage)) UUID.randomUUID().toString().also {
                pages[it] = pageKey to nextPage
                while (pages.size > 16) pages.remove(pages.keys.first())
            } else null
            CatalogPage(sort.order(items), token,
                if (cursor == null) runCatching { e.searchSuggestion?.take(200) }.getOrNull() else null,
                cursor == null && runCatching { e.isCorrectedSearch }.getOrDefault(false))
        }
    }

    override suspend fun channelForVideo(id: VideoId): ChannelId = extract {
        val e = NewPipe.getService("YouTube").getStreamExtractor("https://www.youtube.com/watch?v=${id.value}")
        e.fetchPage()
        if (e.ageLimit > 0) throw AppException(ErrorCode.RESTRICTED)
        val uploader = channelPath.find(e.uploaderUrl.orEmpty())?.groupValues?.get(1)
            ?: throw AppException(ErrorCode.UNAVAILABLE)
        ChannelId(uploader)
    }

    override suspend fun channel(id: ChannelId, cursor: String?): CatalogPage = extract {
        val key = "channel\u0000${id.value}\u0000videos"
        // The Videos tab requests the provider's default Latest feed, not search relevance.
        // Shorts and Streams are separate tabs and are deliberately not requested.
        val e = NewPipe.getService("YouTube").getChannelTabExtractorFromId("channel/${id.value}", ChannelTabs.VIDEOS)
        val page = if (cursor == null) {
            e.fetchPage()
            if (e.id != id.value) throw AppException(ErrorCode.UPSTREAM_CHANGED)
            e.initialPage
        } else {
            val stored = pages[cursor] ?: throw AppException(ErrorCode.UPSTREAM_CHANGED)
            if (stored.first != key) throw AppException(ErrorCode.UPSTREAM_CHANGED)
            e.getPage(stored.second)
        }
        val items = page.items.filterIsInstance<StreamInfoItem>().take(50).mapNotNull(::summary)
            .filterNot { it.live }.map { it.copy(channelId = id.value) }.distinctBy { it.id }
        val next = page.nextPage
        val token = if (next != null && Page.isValid(next)) UUID.randomUUID().toString().also {
            pages[it] = key to next
            while (pages.size > 16) pages.remove(pages.keys.first())
        } else null
        // Preserve provider order here so live verification checks the actual Latest feed.
        CatalogPage(items, token)
    }

    override suspend fun resolve(id: VideoId): PlaybackPlan = extract {
        val e = NewPipe.getService("YouTube").getStreamExtractor("https://www.youtube.com/watch?v=${id.value}")
        e.fetchPage()
        if (e.ageLimit > 0) throw AppException(ErrorCode.RESTRICTED)
        if (e.streamType != StreamType.VIDEO_STREAM) throw AppException(ErrorCode.UNSUPPORTED_FORMAT)
        fun endpoint(raw: String?): MediaEndpoint? = raw?.takeIf { it.isNotBlank() }?.let {
            NetworkPolicy.validate(it, NetworkRole.MEDIA); MediaEndpoint(it)
        }
        val videos = (e.videoStreams + e.videoOnlyStreams).mapNotNull { v ->
            if (!v.isUrl || v.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return@mapNotNull null
            val u = endpoint(v.content) ?: return@mapNotNull null
            VideoVariant(v.id + "-" + v.codec, u, v.format?.mimeType ?: "video/mp4", v.codec,
                v.height.takeIf { it > 0 } ?: Regex("\\d+").find(v.resolution)?.value?.toIntOrNull() ?: 0,
                v.fps.coerceAtLeast(0), v.bitrate.coerceAtLeast(0), v.isVideoOnly, Delivery.PROGRESSIVE)
        }.distinctBy { it.key }
        val audio = e.audioStreams.mapNotNull { a ->
            if (!a.isUrl || a.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return@mapNotNull null
            AudioVariant(a.id + "-" + a.audioTrackId, endpoint(a.content) ?: return@mapNotNull null,
                a.format?.mimeType ?: "audio/mp4", a.codec, a.bitrate.coerceAtLeast(0),
                a.audioTrackType == null || a.audioTrackType == AudioTrackType.ORIGINAL, a.audioLocale?.language, Delivery.PROGRESSIVE)
        }
        val hls = runCatching { endpoint(e.hlsUrl) }.getOrNull()
        val dash = runCatching { endpoint(e.dashMpdUrl) }.getOrNull()
        if (videos.isEmpty() && hls == null && dash == null) throw AppException(ErrorCode.UNSUPPORTED_FORMAT)
        // Only bounded, allowlisted subtitle descriptors cross into playback.
        val captions = runCatching { e.subtitlesDefault }.getOrDefault(emptyList()).take(30).mapNotNull { c ->
            runCatching { NetworkPolicy.validate(c.content, NetworkRole.METADATA); CaptionTrack(c.languageTag.take(64), c.format?.mimeType.orEmpty().take(100), MediaEndpoint(c.content)) }.getOrNull()
        }
        // getRelatedItems parses the already-fetched watch-next response; no new request.
        val related = runCatching { e.relatedItems?.items.orEmpty().filterIsInstance<StreamInfoItem>()
            .take(32).mapNotNull(::summary).filterNot { it.id == id || it.live }.distinctBy { it.id } }.getOrDefault(emptyList())
        PlaybackPlan(id, e.name.take(500), e.length.coerceAtLeast(0) * 1000, System.currentTimeMillis(), videos, audio, hls, dash, captions,
            genre = runCatching { GenreClassifier.providerCategory(e.category) }.getOrNull(), related = related,
            uploadedAtMs = runCatching { e.uploadDate?.instant?.toEpochMilli()?.takeIf { it > 0 } }.getOrNull(),
            channelId = runCatching { channelPath.find(e.uploaderUrl.orEmpty())?.groupValues?.get(1) }.getOrNull(),
            channel = runCatching { e.uploaderName?.take(200) }.getOrNull())
    }

    companion object { private val mutex = Mutex() }
}
