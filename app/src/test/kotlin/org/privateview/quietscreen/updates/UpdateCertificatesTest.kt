package org.privateview.quietscreen.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateCertificatesTest {
    private val trusted = byteArrayOf(1, 2, 3)
    private val wrong = byteArrayOf(4, 5, 6)
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("Untrusted signer accepted") }
        catch (failure: IllegalArgumentException) { assertEquals(code, failure.message) }
    }
    @Test fun absentModernResultCanUseVerifiedLegacyCertificate() {
        val selected = UpdateCertificates.select(emptyList()) { listOf(trusted.clone()) }
        UpdateCertificates.requireSame(selected, listOf(trusted))
    }
    @Test fun modernMismatchAndMultipleSignersNeverUseLegacy() {
        val selected = UpdateCertificates.select(listOf(wrong)) { fail("Fallback on modern result"); emptyList() }
        rejected("UPDATE_SIGNER") { UpdateCertificates.requireSame(selected, listOf(trusted)) }
        val multiple = UpdateCertificates.select(listOf(trusted, wrong)) { fail("Multiple signer fallback"); emptyList() }
        rejected("UPDATE_SIGNER") { UpdateCertificates.requireSame(multiple, listOf(trusted)) }
    }
    @Test fun missingAndUntrustedCertificatesFailClosedWithDistinctReasons() {
        rejected("UPDATE_SIGNER_MISSING") { UpdateCertificates.requireSame(emptyList(), listOf(trusted)) }
        rejected("UPDATE_INSTALLED_SIGNER") { UpdateCertificates.requireSame(listOf(trusted), emptyList()) }
        rejected("UPDATE_SIGNER") { UpdateCertificates.requireSame(listOf(wrong), listOf(trusted)) }
        rejected("UPDATE_SIGNER") { UpdateCertificates.requireSame(listOf(trusted), listOf(trusted, wrong)) }
    }
}
