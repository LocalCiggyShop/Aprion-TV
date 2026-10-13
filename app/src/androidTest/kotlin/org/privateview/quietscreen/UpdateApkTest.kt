package org.privateview.quietscreen

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import org.privateview.quietscreen.core.UpdateDescriptor
import org.privateview.quietscreen.updates.UpdateApk
import org.privateview.quietscreen.core.UpdateCache

/** Exercises the actual Android package parser and signer comparison with real APKs. */
class UpdateApkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, name).apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }
    private fun descriptor(file: File) = UpdateDescriptor(context.packageName, UpdateApk.version(UpdateApk.installed(context)) + 1, "updater-test", 26, file.length(),
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
        "https://github.com/LocalCiggyShop/QuietScreen/releases/download/test/fixture.apk")
    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid update accepted") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    @Test fun acceptsHigherSamePackageAndSignerAndRejectsTamperingAndIdentityChanges() {
        val file = fixture("update-valid.apk")
        try {
            val valid = descriptor(file)
            UpdateApk.verify(context, file, valid)
            reject { UpdateApk.verify(context, file, valid.copy(apkBytes = valid.apkBytes + 1)) }
            reject { UpdateApk.verify(context, file, valid.copy(apkSha256 = "00".repeat(32))) }
            reject { UpdateApk.verify(context, file, valid.copy(packageName = "org.privateview.other")) }
            reject { UpdateApk.verify(context, file, valid.copy(versionCode = 4)) }
            reject { UpdateApk.verify(context, file, valid.copy(versionName = "other")) }
            reject { UpdateApk.verify(context, file, valid.copy(minSdk = 27)) }
            file.writeBytes(ByteArray(100))
            reject { UpdateApk.verify(context, file, descriptor(file)) }
        } finally { file.delete() }
    }
    @Test fun rejectsAnOtherwiseValidApkSignedByAnotherKey() {
        val file = fixture("update-wrong-signer.apk")
        try {
            try { UpdateApk.verify(context, file, descriptor(file)); fail("Different signing key accepted") }
            catch (failure: IllegalArgumentException) { assertEquals("UPDATE_SIGNER", failure.message) }
        } finally { file.delete() }
    }
    @Test fun corruptedMatchingCacheRecoversUsingActualAndroidApkVerification() {
        val fresh = fixture("update-valid.apk")
        val directory = File(context.cacheDir, "update-recovery-fixture").apply { mkdirs() }
        val bytes = byteArrayOf(1, 2, 3)
        val candidate = File(directory, "candidate.apk")
        val envelope = File(directory, "candidate.qsu")
        try {
            candidate.writeBytes(fresh.readBytes()); envelope.writeBytes(bytes)
            val expected = descriptor(fresh)
            // Same length, corrupted content reproduces the old permanent retry loop.
            val corrupt = candidate.readBytes(); corrupt[0] = (corrupt[0].toInt() xor 1).toByte(); candidate.writeBytes(corrupt)
            var downloads = 0
            assertTrue(UpdateCache.obtain(directory, bytes, { UpdateApk.verify(context, it, expected) }, {
                downloads++; fresh.inputStream().use { source -> it.outputStream().use { output -> source.copyTo(output) } }
            }))
            assertEquals(1, downloads)
            UpdateApk.verify(context, candidate, expected)
            assertFalse(UpdateCache.obtain(directory, bytes, { UpdateApk.verify(context, it, expected) }, { fail("Valid cache redownloaded") }))
        } finally { fresh.delete(); candidate.delete(); envelope.delete(); File(directory, "candidate.part").delete(); directory.delete() }
    }
}
