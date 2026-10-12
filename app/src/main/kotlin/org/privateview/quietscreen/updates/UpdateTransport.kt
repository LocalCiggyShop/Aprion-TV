package org.privateview.quietscreen.updates

import java.net.InetAddress
import java.net.UnknownHostException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.privateview.quietscreen.core.UpdateManifest
import org.privateview.quietscreen.extractor.NetworkPolicy

/** Separate from video traffic; no cookies, authentication or device identifiers. */
internal class UpdateTransport(private val owner: String, private val repo: String, private val branch: String) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .dns(Dns { host ->
            val addresses = InetAddress.getAllByName(host).toList()
            if (addresses.isEmpty() || addresses.any { !NetworkPolicy.isPublic(it) }) throw UnknownHostException("UPDATE_DNS")
            addresses
        }).build()
    @Volatile private var active: Call? = null
    @Volatile private var cancelled = false

    fun begin() { cancelled = false }
    fun cancel() { cancelled = true; active?.cancel() }

    fun <T> read(url: String, metadata: Boolean, consume: (InputStream) -> T): T {
        var destination = url.toHttpUrl()
        repeat(4) { hop ->
            check(!cancelled) { "UPDATE_CANCELLED" }
            require(destination.scheme == "https" && destination.port == 443 &&
                destination.username.isEmpty() && destination.password.isEmpty() && destination.fragment == null)
            if (metadata) {
                require(destination.toString() == "https://raw.githubusercontent.com/$owner/$repo/$branch/updates/${org.privateview.quietscreen.BuildConfig.APPLICATION_ID}.qsu")
            } else if (hop == 0 || destination.host == "github.com") {
                require(UpdateManifest.validateApkUrl(destination.toString(), owner, repo))
            } else {
                require(destination.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
            }
            val call = client.newCall(Request.Builder().url(destination).header("Accept-Encoding", "identity").build())
            active = call
            if (cancelled) call.cancel()
            call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    require(!metadata && hop < 3)
                    destination = destination.resolve(response.header("Location") ?: error("UPDATE_REDIRECT")) ?: error("UPDATE_REDIRECT")
                } else {
                    require(response.code == 200) { "UPDATE_HTTP" }
                    val body = response.body ?: error("UPDATE_BODY")
                    return consume(body.byteStream())
                }
            }
        }
        error("UPDATE_REDIRECT")
    }
}
