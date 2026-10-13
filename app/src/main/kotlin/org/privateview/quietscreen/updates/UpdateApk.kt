package org.privateview.quietscreen.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import org.privateview.quietscreen.core.UpdateDescriptor

internal object UpdateApk {
    @Suppress("DEPRECATION")
    fun certificates(info: PackageInfo): List<ByteArray> = UpdateCertificates.select(
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty() else emptyList()
    ) { info.signatures?.map { it.toByteArray() }.orEmpty() }

    // Request both: older/vendor archive parsers can collect certificates only
    // when GET_SIGNATURES is set. This still invokes Android signature verification.
    @Suppress("DEPRECATION")
    private val certificateFlags get() = PackageManager.GET_SIGNATURES or
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0

    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName,
        certificateFlags)

    @Suppress("DEPRECATION")
    internal fun archive(context: Context, file: File): PackageInfo =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, certificateFlags) ?: error("UPDATE_ARCHIVE")

    @Suppress("DEPRECATION")
    fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    fun key(info: PackageInfo): java.security.PublicKey {
        val signers = certificates(info)
        require(signers.size == 1) { "UPDATE_INSTALLED_SIGNER" }
        return CertificateFactory.getInstance("X.509").generateCertificate(signers.single().inputStream()).publicKey
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, descriptor: UpdateDescriptor) {
        require(file.length() == descriptor.apkBytes) { "UPDATE_SIZE" }
        val digest = MessageDigest.getInstance("SHA-256")
        try { file.inputStream().use { stream ->
            val buffer = ByteArray(32768)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        } } catch (_: IOException) { throw IllegalStateException("UPDATE_STORAGE") }
        val expected = descriptor.apkSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(digest.digest(), expected)) { "UPDATE_HASH" }
        val info = archive(context, file)
        val current = installed(context)
        require(info.packageName == context.packageName && info.packageName == descriptor.packageName &&
            version(info) == descriptor.versionCode && version(info) > version(current) &&
            info.versionName == descriptor.versionName && info.applicationInfo?.minSdkVersion == descriptor.minSdk &&
            descriptor.minSdk <= Build.VERSION.SDK_INT) { "UPDATE_IDENTITY" }
        val incoming = certificates(info); val installed = certificates(current)
        UpdateCertificates.requireSame(incoming, installed)
        // The system installer also verifies the complete APK signature before replacing the app.
    }
}
