package org.privateview.quietscreen.extractor

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class NetworkRole { METADATA, MEDIA, THUMBNAIL }
class PolicyViolation : IOException("QUIETSCREEN_NETWORK_POLICY")

object NetworkPolicy {
    fun validate(raw: String, role: NetworkRole): HttpUrl {
        val u = raw.toHttpUrlOrNull() ?: throw PolicyViolation()
        if (u.scheme != "https" || u.port != 443 || u.username.isNotEmpty() || u.password.isNotEmpty()) throw PolicyViolation()
        val h = u.host
        val allowed = when (role) {
            NetworkRole.METADATA -> h in setOf("www.youtube.com", "youtube.com", "m.youtube.com", "youtubei.googleapis.com")
            NetworkRole.MEDIA -> h == "googlevideo.com" || h.endsWith(".googlevideo.com")
            NetworkRole.THUMBNAIL -> h == "ytimg.com" || h.endsWith(".ytimg.com")
        }
        if (!allowed) throw PolicyViolation()
        return u
    }

    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224) return false
            if (b[0] == 100 && b[1] in 64..127) return false
            if (b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31) return false
            if (b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] in setOf(0, 2))) return false
            if (b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100)) return false
            if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false
        } else {
            if (b[0] and 254 == 252) return false
            if (b[0] == 32 && b[1] == 1 && (b[2] == 13 && b[3] == 184 || b[2] == 0 && b[3] == 0)) return false
            if (b[0] == 32 && b[1] == 2) return false
        }
        return true
    }

    fun client(role: NetworkRole): OkHttpClient = OkHttpClient.Builder()
        .dns(Dns { host -> Dns.SYSTEM.lookup(host).also { addresses -> if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw PolicyViolation() } })
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(if (role == NetworkRole.MEDIA) 20 else 15, TimeUnit.SECONDS)
        .apply { if (role != NetworkRole.MEDIA) callTimeout(20, TimeUnit.SECONDS) }
        .addInterceptor(Interceptor { chain ->
            var request = chain.request()
            repeat(6) { hop ->
                validate(request.url.toString(), role)
                NetworkAudit.record(role, request.url.host)
                val response = chain.proceed(request)
                if (response.code !in setOf(301, 302, 303, 307, 308)) return@Interceptor response
                val location = response.header("Location")
                val next = location?.let { request.url.resolve(it) }
                response.close()
                if (hop == 5 || next == null) throw PolicyViolation()
                validate(next.toString(), role)
                if (request.url.host != next.host && request.header("Cookie") != null) throw PolicyViolation()
                request = request.newBuilder().url(next).apply {
                    if (request.url.host != next.host) { removeHeader("Cookie"); removeHeader("Authorization"); removeHeader("X-Goog-Visitor-Id") }
                    if (response.code == 303 || response.code in setOf(301, 302) && request.method == "POST") method("GET", null)
                }.build()
            }
            throw PolicyViolation()
        }).build()
}
