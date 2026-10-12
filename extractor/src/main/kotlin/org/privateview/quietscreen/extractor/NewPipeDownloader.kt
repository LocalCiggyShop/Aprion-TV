package org.privateview.quietscreen.extractor

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.privateview.quietscreen.core.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class NewPipeDownloader : Downloader() {
    private val client = NetworkPolicy.client(NetworkRole.METADATA)
    private val calls = CopyOnWriteArrayList<Call>()
    private val headerNames = setOf("accept", "accept-language", "content-type", "origin", "referer", "user-agent", "x-youtube-client-name", "x-youtube-client-version", "x-goog-visitor-id", "x-goog-api-format-version", "x-goog-fieldmask", "x-youtube-bootstrap-logged-in")
    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile private var cancelled = false
    fun begin() { cancelled = false; deadline = System.nanoTime() + 60_000_000_000L }
    fun cancel() { cancelled = true; calls.forEach { it.cancel() } }

    override fun execute(request: Request): Response {
        if (cancelled) throw java.io.InterruptedIOException("Extraction cancelled")
        val url = NetworkPolicy.validate(request.url(), NetworkRole.METADATA)
        val builder = okhttp3.Request.Builder().url(url)
        request.headers().forEach { (name, values) ->
            if (name.equals("Cookie", true)) {
                // Reviewed fixed anonymous consent rejection, never an account cookie/jar.
                if (url.host != "www.youtube.com" || values != listOf("SOCS=CAE=")) throw PolicyViolation()
                builder.header("Cookie", "SOCS=CAE=")
            } else {
                if (name.lowercase() !in headerNames) throw PolicyViolation()
                values.forEach { builder.addHeader(name, it) }
            }
        }
        val data = request.dataToSend()
        if (data != null && data.size > 1024 * 1024) throw PolicyViolation()
        builder.method(request.httpMethod(), data?.toRequestBody("application/json".toMediaTypeOrNull()))
        val call = client.newCall(builder.build())
        call.timeout().deadlineNanoTime(minOf(deadline, System.nanoTime() + 20_000_000_000L))
        calls.add(call)
        if (cancelled) call.cancel()
        try {
            call.execute().use { response ->
                if (response.code == 429) throw AppException(ErrorCode.RATE_LIMITED)
                if (response.code == 403) throw AppException(ErrorCode.SIGN_IN_REQUIRED)
                if (response.code >= 500) throw AppException(ErrorCode.UPSTREAM_CHANGED)
                val limit = if (url.encodedPath.endsWith(".js")) 4 * 1024 * 1024 else 8 * 1024 * 1024
                val output = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (output.size() + n > limit) throw PolicyViolation()
                        output.write(buf, 0, n)
                    }
                }
                val text = output.toString(Charsets.UTF_8.name())
                if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) JsonBudget.validate(text)
                return Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
            }
        } finally { calls.remove(call) }
    }
}

object JsonBudget {
    fun validate(text: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> if (++depth > 96) throw PolicyViolation(); '}', ']' -> depth-- }
        }
    }
}
