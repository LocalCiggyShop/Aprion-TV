package org.privateview.quietscreen

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import org.privateview.quietscreen.core.UpdateDescriptor
import org.privateview.quietscreen.updates.UpdateApk

/** Exercises the actual Android package parser and signer comparison with real APKs. */
class UpdateApkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String): File = File(context.cacheDir, name).apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }
    private fun descriptor(file: File) = UpdateDescriptor(context.packageName, 6, "0.3.2-updater-test", 26, file.length(),
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
        try { reject { UpdateApk.verify(context, file, descriptor(file)) } } finally { file.delete() }
    }
}
