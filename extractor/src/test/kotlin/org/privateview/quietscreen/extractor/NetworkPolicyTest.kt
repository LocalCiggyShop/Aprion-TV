package org.privateview.quietscreen.extractor

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.utils.JavaScript
import java.net.InetAddress

class NetworkPolicyTest {
    @Test fun rejectsHostConfusionAndUnsafeSchemes() {
        listOf("http://www.youtube.com/", "https://www.youtube.com.evil.test/", "https://youtube.com@evil.test/", "https://127.0.0.1/", "https://www.youtube.com:8443/", "file:///tmp/video").forEach {
            assertThrows(PolicyViolation::class.java) { NetworkPolicy.validate(it, NetworkRole.METADATA) }
        }
        assertEquals("www.youtube.com", NetworkPolicy.validate("https://www.youtube.com/youtubei/v1/search", NetworkRole.METADATA).host)
        assertThrows(PolicyViolation::class.java) { NetworkPolicy.validate("https://www.youtube.com/", NetworkRole.MEDIA) }
        assertThrows(PolicyViolation::class.java) { NetworkPolicy.validate("https://evilgooglevideo.com/", NetworkRole.MEDIA) }
    }
    @Test fun rejectsNonPublicDestinationsIncludingIpv6() {
        listOf("127.0.0.1", "10.1.1.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "::1", "fe80::1", "fd00::1", "2001:db8::1").forEach { assertFalse(it, NetworkPolicy.isPublic(InetAddress.getByName(it))) }
        assertTrue(NetworkPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun nestedJsonIsLimitedButBracesInStringsAreData() {
        assertThrows(PolicyViolation::class.java) { JsonBudget.validate("[".repeat(97) + "0" + "]".repeat(97)) }
        JsonBudget.validate("{\"text\":\"" + "[".repeat(300) + "\"}")
    }
    @Test fun allJavascriptExecutionEntryPointsDenyImmediately() {
        assertThrows(SecurityException::class.java) { JavaScript.run("function f(){while(true){}}", "f") }
        assertThrows(SecurityException::class.java) { JavaScript.compileOrThrow("(".repeat(100000)) }
    }
    @Test fun accountCookiesAndAuthorizationNeverReachTheNetwork() {
        val d = NewPipeDownloader()
        for (header in listOf("Cookie" to "SID=secret", "Authorization" to "Bearer secret")) {
            val request = org.schabi.newpipe.extractor.downloader.Request.newBuilder().get("https://www.youtube.com/").setHeader(header.first, header.second).build()
            assertThrows(PolicyViolation::class.java) { d.execute(request) }
        }
    }
}
