package org.privateview.quietscreen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException
import okhttp3.Request
import org.privateview.quietscreen.extractor.NetworkPolicy
import org.privateview.quietscreen.extractor.NetworkRole

object ThumbnailLoader {
    private val client = NetworkPolicy.client(NetworkRole.THUMBNAIL)
    private val slots = Semaphore(4)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        slots.withPermit {
            val call = client.newCall(Request.Builder().url(url).build())
            calls.add(call)
            try {
                NetworkPolicy.validate(url, NetworkRole.THUMBNAIL)
                awaitResponse(call).use { r ->
                    if (!r.isSuccessful || r.body.contentType()?.type != "image") return@use null
                    val source = r.body.source()
                    if (source.request(2L * 1024 * 1024 + 1)) return@use null
                    val bytes = source.readByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000) return@use null
                    var sample = 1
                    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 360) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(url, it) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            finally { calls.remove(call); call.cancel() }
        }
    }
    fun cancelPending() { calls.forEach { it.cancel() } }
    fun clear() { cancelPending(); cache.evictAll() }
    private suspend fun awaitResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                else response.close()
            }
        })
    }
}
