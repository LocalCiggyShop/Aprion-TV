package org.privateview.quietscreen.updates

import java.net.InetAddress
import java.net.UnknownHostException
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.privateview.quietscreen.core.UpdateManifest
import org.privateview.quietscreen.core.UpdateFeed
import org.privateview.quietscreen.core.UpdateFailure
import org.privateview.quietscreen.core.UpdateProblem
import org.privateview.quietscreen.extractor.NetworkPolicy

/** Separate from video traffic; no cookies, authentication or device identifiers. */
internal class UpdateTransport(private val owner: String, private val repo: String, private val branch: String,
    packageName: String = org.privateview.quietscreen.BuildConfig.APPLICATION_ID, injectedClient: OkHttpClient? = null) {
    private val feeds by lazy { UpdateFeed(owner, repo, branch, packageName) }
    private val client = injectedClient ?: OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .dns(Dns { host ->
            val addresses = InetAddress.getAllByName(host).toList()
            if (addresses.isEmpty()) throw UnknownHostException()
            if (addresses.any { !NetworkPolicy.isPublic(it) }) throw UpdateFailure(UpdateProblem.POLICY)
            addresses
        }).build()
    @Volatile private var active: Call? = null
    @Volatile private var cancelled = false

    fun begin() { cancelled = false }
    fun cancel() { cancelled = true; active?.cancel() }

    fun <T> read(url: String, metadata: Boolean, consume: (InputStream) -> T): T {
        var destination = url.toHttpUrl()
        repeat(4) { hop ->
            if (cancelled) throw CancellationException()
            if (!(destination.scheme == "https" && destination.port == 443 &&
                destination.username.isEmpty() && destination.password.isEmpty() && destination.fragment == null)) throw UpdateFailure(UpdateProblem.POLICY)
            if (metadata) {
                if (!feeds.allowed(destination.toString())) throw UpdateFailure(UpdateProblem.POLICY)
            } else if (hop == 0 || destination.host == "github.com") {
                if (!UpdateManifest.validateApkUrl(destination.toString(), owner, repo)) throw UpdateFailure(UpdateProblem.POLICY)
            } else {
                if (destination.host !in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) throw UpdateFailure(UpdateProblem.POLICY)
            }
            val request = Request.Builder().url(destination).header("Accept-Encoding", "identity")
                // Refresh short-lived GitHub asset redirects and newly published metadata.
                .header("Cache-Control", "no-cache")
            if (metadata && destination.toString() == feeds.api) request.header("Accept", "application/vnd.github.raw+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
            val call = client.newCall(request.build())
            active = call
            if (cancelled) call.cancel()
            try { call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    if (metadata || hop >= 3) throw UpdateFailure(UpdateProblem.POLICY)
                    destination = response.header("Location")?.let { destination.resolve(it) } ?: throw UpdateFailure(UpdateProblem.POLICY)
                } else {
                    if (response.code != 200) throw UpdateFailure(UpdateProblem.HTTP, response.code)
                    val body = response.body ?: throw UpdateFailure(UpdateProblem.METADATA)
                    return consume(body.byteStream())
                }
            } } catch (failure: IOException) { if (cancelled) throw CancellationException(); throw failure }
            finally { active = null }
        }
        throw UpdateFailure(UpdateProblem.POLICY)
    }
}
