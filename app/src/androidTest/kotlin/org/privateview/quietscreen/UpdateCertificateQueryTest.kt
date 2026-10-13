package org.privateview.quietscreen

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import android.os.Build
import org.privateview.quietscreen.updates.UpdateApk
import org.privateview.quietscreen.updates.UpdateCertificates

/** Independently runnable without a newer-version update fixture or a self-install. */
class UpdateCertificateQueryTest {
    @Suppress("DEPRECATION")
    @Test fun realArchiveReturnsVerifiedLegacyAndCurrentCertificates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parsed = UpdateApk.archive(context, File(context.applicationInfo.sourceDir))
        val installed = UpdateApk.certificates(UpdateApk.installed(context))
        assertEquals(1, installed.size)
        assertEquals(1, parsed.signatures?.size)
        UpdateCertificates.requireSame(UpdateApk.certificates(parsed), installed)
        // Simulate a vendor omitting SigningInfo: the separately requested legacy
        // result must still be Android-verified and exactly match this installation.
        if (Build.VERSION.SDK_INT >= 28) parsed.signingInfo = null
        UpdateCertificates.requireSame(UpdateApk.certificates(parsed), installed)
        parsed.signatures = null
        try { UpdateCertificates.requireSame(UpdateApk.certificates(parsed), installed); fail("Missing certificate accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("UPDATE_SIGNER_MISSING", failure.message) }
    }
}
