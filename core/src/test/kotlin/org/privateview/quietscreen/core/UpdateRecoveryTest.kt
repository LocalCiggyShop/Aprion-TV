package org.privateview.quietscreen.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.CancellationException

class UpdateRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val feed = UpdateFeed("LocalCiggyShop", "QuietScreen", "main", "org.privateview.quietscreen")
    @Test fun fallbackUsesOnlySameRepositoryFileAndBranchAndIsBounded() {
        assertTrue(feed.allowed(feed.raw)); assertTrue(feed.allowed(feed.api))
        assertFalse(feed.allowed(feed.api.replace("ref=main", "ref=other")))
        assertFalse(feed.allowed(feed.raw.replace("QuietScreen", "Other")))
        assertFalse(feed.allowed(feed.raw + "?token=secret"))
        val requests = mutableListOf<String>()
        val result = feed.fetch { url, _ -> requests += url; if (url == feed.raw) throw UnknownHostException("private detail"); byteArrayOf(1, 2) }
        assertEquals(listOf(feed.raw, feed.api), requests)
        assertEquals(UpdateSource.API, result.source); assertArrayEquals(byteArrayOf(1, 2), result.bytes)
        requests.clear()
        try { feed.fetch { url, _ -> requests += url; throw UpdateFailure(UpdateProblem.HTTP, 403) }; fail() }
        catch (failure: UpdateFailure) { assertEquals(403, failure.httpStatus) }
        assertEquals(2, requests.size)
    }
    @Test fun validPrimaryDoesNotUseFallbackAndSecurityFailureDoesNotTriggerAnotherFetch() {
        var requests = 0
        val primary = feed.fetch { _, _ -> requests++; byteArrayOf(1) }
        assertEquals(1, requests); assertEquals(UpdateSource.RAW, primary.source)
        requests = 0
        try { feed.fetch { _, _ -> requests++; throw UpdateFailure(UpdateProblem.METADATA) }; fail() }
        catch (_: UpdateFailure) {}
        assertEquals(1, requests)
        requests = 0
        try { feed.fetch { _, _ -> requests++; throw CancellationException() }; fail() }
        catch (_: CancellationException) {}
        assertEquals(1, requests)
    }
    @Test fun diagnosticsDistinguishFailuresWithoutLeakingExceptionMessages() {
        val errors = listOf(UnknownHostException("secret URL") to UpdateProblem.DNS,
            ConnectException("secret IP") to UpdateProblem.CONNECTION, SocketTimeoutException("private token") to UpdateProblem.TIMEOUT,
            SSLHandshakeException("private certificate details") to UpdateProblem.TLS,
            IllegalArgumentException("UPDATE_SIGNATURE") to UpdateProblem.SIGNATURE,
            IllegalArgumentException("UPDATE_HASH") to UpdateProblem.HASH,
            IllegalArgumentException("UPDATE_SDK") to UpdateProblem.COMPATIBILITY,
            IllegalArgumentException("UPDATE_SIGNER_MISSING") to UpdateProblem.SIGNER_MISSING,
            IllegalArgumentException("UPDATE_SIGNER") to UpdateProblem.SIGNER)
        errors.forEach { (error, problem) ->
            val diagnostic = UpdateDiagnostics.from(IOException("private detail", error), UpdateStep.FEED)
            assertEquals(problem, diagnostic.problem)
            assertFalse(diagnostic.message.contains("secret")); assertFalse(diagnostic.message.contains("private"))
        }
        val unknown = UpdateDiagnostics.from(IllegalStateException("https://host/path?token=secret"), UpdateStep.APK)
        assertEquals(UpdateProblem.UNKNOWN, unknown.problem); assertFalse(unknown.message.contains("token"))
        assertEquals("QS-U-DOWNLOAD-HTTP-403", UpdateDiagnostics.from(UpdateFailure(UpdateProblem.HTTP, 403), UpdateStep.DOWNLOAD).code)
        assertFalse(UpdateDiagnostics.from(UpdateFailure(UpdateProblem.HTTP, -9000), UpdateStep.DOWNLOAD).code.contains("9000"))
    }
    @Test fun damagedMatchingCacheIsDownloadedOnceThenReusableWithoutNetwork() {
        val directory = temporary.newFolder()
        val bytes = byteArrayOf(1, 2, 3)
        File(directory, "candidate.qsu").writeBytes(bytes)
        File(directory, "candidate.apk").writeText("damaged")
        var downloads = 0; var verifications = 0
        val verify: (File) -> Unit = { verifications++; require(it.readText() == "verified") { "UPDATE_HASH" } }
        assertTrue(UpdateCache.obtain(directory, bytes, verify, { downloads++; it.writeText("verified") }))
        assertEquals(1, downloads); assertEquals(2, verifications)
        assertFalse(UpdateCache.obtain(directory, bytes, verify, { fail("Reusable verified cache attempted network") }))
        assertArrayEquals(bytes, File(directory, "candidate.qsu").readBytes())
    }
    @Test fun invalidFreshDownloadIsNeverPromotedAndUnexpectedFailureDoesNotEvictValidCache() {
        val directory = temporary.newFolder(); val bytes = byteArrayOf(1)
        try { UpdateCache.obtain(directory, bytes, { throw IllegalArgumentException("UPDATE_SIGNER") }, { it.writeText("bad") }); fail() }
        catch (_: IllegalArgumentException) {}
        assertTrue(directory.listFiles()!!.isEmpty())
        File(directory, "candidate.qsu").writeBytes(bytes); File(directory, "candidate.apk").writeText("good")
        try { UpdateCache.obtain(directory, bytes, { throw IllegalArgumentException("UPDATE_INSTALLED_SIGNER") }, { fail() }); fail() }
        catch (_: IllegalArgumentException) {}
        assertEquals("good", File(directory, "candidate.apk").readText())
    }
    @Test fun oversizedCachedMetadataIsReplacedAndCancellationCleansPartialFiles() {
        val directory = temporary.newFolder(); val bytes = byteArrayOf(1)
        File(directory, "candidate.qsu").writeBytes(ByteArray(8193)); File(directory, "candidate.apk").writeText("old")
        var downloads = 0
        UpdateCache.obtain(directory, bytes, { require(it.readText() == "new") }, { downloads++; it.writeText("new") })
        assertEquals(1, downloads)
        val cancelled = temporary.newFolder(); var checks = 0
        try { UpdateCache.obtain(cancelled, bytes, {}, { it.writeText("partial") }, { if (++checks == 3) throw CancellationException() }); fail() }
        catch (_: CancellationException) {}
        assertTrue(cancelled.listFiles()!!.isEmpty())
    }
}
