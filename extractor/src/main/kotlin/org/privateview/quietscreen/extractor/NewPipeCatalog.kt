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

class NewPipeCatalog : VideoCatalog, StreamResolver {
    private val downloader = NewPipeDownloader()
    private val pages = LinkedHashMap<String, Pair<String, Page>>()
    override fun cancelPending() = downloader.cancel()

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
                if (android.util.Log.isLoggable("QuietScreenExtractor", android.util.Log.DEBUG)) {
                    var cause: Throwable? = e
                    repeat(5) {
                        cause?.let { failure ->
                            android.util.Log.d("QuietScreenExtractor", "failureType=${failure.javaClass.name} frames=" +
                                failure.stackTrace.take(6).joinToString("|") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" })
                        }
                        cause = cause?.cause
                    }
                }
                throw AppException(ErrorCode.UPSTREAM_CHANGED, e)
            }
        }
    }

    override suspend fun search(query: String, cursor: String?): CatalogPage {
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 200) throw AppException(ErrorCode.INVALID_QUERY)
        return extract {
            val service = NewPipe.getService("YouTube")
            val e = service.getSearchExtractor(clean, listOf("videos"), "")
            val page = try { if (cursor == null) { pages.clear(); e.fetchPage(); e.initialPage }
                else { val stored = pages.remove(cursor) ?: throw AppException(ErrorCode.UPSTREAM_CHANGED)
                    if (stored.first != clean) throw AppException(ErrorCode.UPSTREAM_CHANGED)
                    e.getPage(stored.second) }
            } catch (_: NothingFoundException) { return@extract CatalogPage(emptyList(), null) }
            val items = page.items.filterIsInstance<StreamInfoItem>().take(50).mapNotNull { item ->
                runCatching {
                    val id = service.streamLHFactory.getId(item.url)
                    VideoSummary(VideoId(id), item.name.take(500), item.uploaderName.orEmpty().take(200),
                        item.thumbnails.firstOrNull()?.url?.takeIf { runCatching { NetworkPolicy.validate(it, NetworkRole.THUMBNAIL) }.isSuccess },
                        item.duration.takeIf { it >= 0 }, item.streamType == StreamType.LIVE_STREAM)
                }.getOrNull()
            }.distinctBy { it.id }
            val nextPage = page.nextPage
            val token = if (nextPage != null && Page.isValid(nextPage)) UUID.randomUUID().toString().also {
                pages[it] = clean to nextPage
                while (pages.size > 3) pages.remove(pages.keys.first())
            } else null
            CatalogPage(items, token,
                if (cursor == null) runCatching { e.searchSuggestion?.take(200) }.getOrNull() else null,
                cursor == null && runCatching { e.isCorrectedSearch }.getOrDefault(false))
        }
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
        PlaybackPlan(id, e.name.take(500), e.length.coerceAtLeast(0) * 1000, System.currentTimeMillis(), videos, audio, hls, dash, captions)
    }

    companion object { private val mutex = Mutex() }
}
