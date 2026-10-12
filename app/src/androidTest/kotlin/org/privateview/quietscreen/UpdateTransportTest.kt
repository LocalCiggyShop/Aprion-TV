package org.privateview.quietscreen

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.privateview.quietscreen.core.*
import org.privateview.quietscreen.updates.UpdateTransport
import java.net.UnknownHostException
import java.security.cert.CertificateFactory

class UpdateTransportTest {
    private val feed = UpdateFeed("LocalCiggyShop", "QuietScreen", "main", "org.privateview.quietscreen")
    @Test fun blockedRawHostFallsBackOnceWithoutCookiesOrAuthentication() {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request(); requests += request.url.toString()
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            assertEquals("no-cache", request.header("Cache-Control"))
            if (request.url.toString() == feed.raw) throw UnknownHostException("private diagnostic that must not escape")
            assertEquals(feed.api, request.url.toString())
            assertEquals("application/vnd.github.raw+json", request.header("Accept"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("signed fixture".toResponseBody()).build()
        }.build()
        val transport = UpdateTransport("LocalCiggyShop", "QuietScreen", "main", "org.privateview.quietscreen", client)
        transport.begin()
        val result = feed.fetch { url, _ -> transport.read(url, true) { it.readBytes() } }
        assertEquals(UpdateSource.API, result.source)
        assertEquals(listOf(feed.raw, feed.api), requests)
        assertEquals("signed fixture", result.bytes.toString(Charsets.UTF_8))
    }
    @Test fun metadataRedirectsAndUntrustedDestinationsStayBlockedAndHttpCodesStaySafe() {
        var requests = 0
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("private secret")
                .header("Location", "https://attacker.example/?signed-token=secret").body("private body".toResponseBody()).build()
        }.build()
        val transport = UpdateTransport("LocalCiggyShop", "QuietScreen", "main", "org.privateview.quietscreen", client)
        transport.begin()
        try { transport.read(feed.raw, true) { it.readBytes() }; fail() } catch (failure: UpdateFailure) { assertEquals(UpdateProblem.POLICY, failure.problem) }
        assertEquals(1, requests)
        try { transport.read(feed.api.replace("ref=main", "ref=other"), true) { it.readBytes() }; fail() }
        catch (failure: UpdateFailure) { assertEquals(UpdateProblem.POLICY, failure.problem) }
        assertEquals(1, requests)
        try { transport.read("https://github.com/other/repo/releases/download/v1/QuietScreen.apk", false) { it.readBytes() }; fail() }
        catch (_: UpdateFailure) {}
        assertEquals(1, requests)
        transport.cancel()
        try { transport.read(feed.raw, true) { it.readBytes() }; fail() } catch (_: java.util.concurrent.CancellationException) {}
        assertEquals(1, requests)
    }
    @Test fun actualAnonymousApiReturnsTheSameVerifiedSignedProductionFeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transport = UpdateTransport("LocalCiggyShop", "QuietScreen", "main", "org.privateview.quietscreen")
        transport.begin()
        val bytes = transport.read(feed.api, true) { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
            while (true) { val read = stream.read(buffer); if (read < 0) break; require(output.size() + read <= 8192); output.write(buffer, 0, read) }
            output.toByteArray()
        }
        val key = instrumentation.context.assets.open("production-public.der").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it).publicKey
        }
        val expected = instrumentation.context.assets.open("production-feed.qsu").use { it.readBytes() }
        assertArrayEquals(expected, bytes)
        val verified = UpdateManifest.verify(bytes, key, "org.privateview.quietscreen", 5, android.os.Build.VERSION.SDK_INT, "LocalCiggyShop", "QuietScreen")
        assertNotNull(verified); assertEquals(6L, verified!!.versionCode)
    }
}
