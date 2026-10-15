package org.privateview.quietscreen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.privateview.quietscreen.core.PreviewStoryboard
import org.privateview.quietscreen.extractor.NetworkPolicy
import org.privateview.quietscreen.extractor.NetworkRole
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** One screen's preview sheets. No full-size sheet bitmap or second video decoder. */
internal class PreviewLoader {
    companion object { private val client = NetworkPolicy.client(NetworkRole.THUMBNAIL) }
    private val cache = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    suspend fun load(story: PreviewStoryboard, position: Long): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val frame = story.frame(position)
            val url = story.sheets[frame.sheet].url
            NetworkPolicy.validate(url, NetworkRole.THUMBNAIL)
            val bytes = cache.get(url) ?: run {
                val call = client.newCall(Request.Builder().url(url).build())
                download(call)?.also { cache.put(url, it) } ?: return@withContext null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000 ||
                !frame.fits(bounds.outWidth, bounds.outHeight)) return@withContext null
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            try { decoder.decodeRegion(Rect(frame.left, frame.top, frame.left + frame.width, frame.top + frame.height), BitmapFactory.Options()) }
            finally { decoder.recycle() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    fun clear() { cache.evictAll() }
    private suspend fun download(call: Call): ByteArray? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use { result ->
                        if (!result.isSuccessful || result.body.contentType()?.type != "image") null
                        else {
                            val source = result.body.source()
                            if (source.request(2L * 1024 * 1024 + 1)) null else source.readByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resume(bytes) { _, _, _ -> }
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}
