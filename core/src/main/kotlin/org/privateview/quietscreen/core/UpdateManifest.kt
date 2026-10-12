package org.privateview.quietscreen.core

import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

data class UpdateDescriptor(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkBytes: Long,
    val apkSha256: String,
    val apkUrl: String,
)

/** Verifies a bounded, signed release descriptor before interpreting its metadata. */
object UpdateManifest {
    const val MAX_APK_BYTES = 64 * 1024 * 1024L
    const val MAX_ENVELOPE_BYTES = 8192

    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val ownerPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
    private val repoPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    private val pathPartPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val decimalPattern = Regex("[1-9][0-9]*")
    private val hashPattern = Regex("[A-Fa-f0-9]{64}")
    private val base64Pattern = Regex("[A-Za-z0-9+/]+={0,2}")

    fun verify(
        envelope: ByteArray,
        publicKey: PublicKey,
        expectedPackage: String,
        currentVersion: Long,
        sdk: Int,
        owner: String,
        repo: String,
    ): UpdateDescriptor? {
        require(envelope.isNotEmpty() && envelope.size <= MAX_ENVELOPE_BYTES) { "UPDATE_ENVELOPE" }
        require(envelope.all { it.toInt() in 0..127 }) { "UPDATE_ENVELOPE" }
        val outer = envelope.toString(Charsets.US_ASCII).replace("\r\n", "\n")
        require('\r' !in outer) { "UPDATE_ENVELOPE" }
        val lines = outer.removeSuffix("\n").split('\n')
        require(lines.size == 3 && lines[0] == "QUIETSCREEN_SIGNED_UPDATE_V1") { "UPDATE_ENVELOPE" }
        val payload = decodeBase64(lines[1])
        val signature = decodeBase64(lines[2])
        require(publicKey is RSAPublicKey && publicKey.modulus.bitLength() >= 2048) { "UPDATE_KEY" }
        val verified = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("UPDATE_SIGNATURE")
        }
        require(verified) { "UPDATE_SIGNATURE" }

        // No unsigned payload values are parsed, logged, or included in failures.
        require(payload.all { it.toInt() in 0..127 }) { "UPDATE_PAYLOAD" }
        val fields = payload.toString(Charsets.US_ASCII).split('\n')
        require(fields.size == 8 && fields[0] == "QUIETSCREEN_UPDATE_V1") { "UPDATE_PAYLOAD" }
        require(packagePattern.matches(fields[1]) && fields[1] == expectedPackage) { "UPDATE_PACKAGE" }
        val versionCode = positiveDecimal(fields[2], Int.MAX_VALUE.toLong())
        require(versionPattern.matches(fields[3])) { "UPDATE_PAYLOAD" }
        val minSdk = positiveDecimal(fields[4], 100).toInt()
        require(minSdk <= sdk) { "UPDATE_SDK" }
        val apkBytes = positiveDecimal(fields[5], MAX_APK_BYTES)
        require(hashPattern.matches(fields[6])) { "UPDATE_PAYLOAD" }
        require(validateApkUrl(fields[7], owner, repo)) { "UPDATE_URL" }
        val descriptor = UpdateDescriptor(fields[1], versionCode, fields[3], minSdk, apkBytes,
            fields[6].lowercase(java.util.Locale.ROOT), fields[7])
        return descriptor.takeIf { versionCode > currentVersion }
    }

    fun validateRepository(owner: String, repo: String): Boolean =
        ownerPattern.matches(owner) && repoPattern.matches(repo)

    /** Restricts initial and redirect destinations to this repository's release APK assets. */
    fun validateApkUrl(url: String, owner: String, repo: String): Boolean {
        if (!validateRepository(owner, repo) || url.any { it.code !in 33..126 }) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port !in listOf(-1, 443) ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath ?: return false
        if ('%' in path) return false
        val parts = path.split('/')
        return parts.size == 7 && parts[0].isEmpty() && parts[1] == owner && parts[2] == repo &&
            parts[3] == "releases" && parts[4] == "download" &&
            pathPartPattern.matches(parts[5]) && pathPartPattern.matches(parts[6]) &&
            parts[6].endsWith(".apk")
    }

    private fun positiveDecimal(value: String, maximum: Long): Long {
        require(decimalPattern.matches(value)) { "UPDATE_PAYLOAD" }
        val parsed = value.toLongOrNull()
        require(parsed != null && parsed in 1..maximum) { "UPDATE_PAYLOAD" }
        return parsed
    }

    private fun decodeBase64(value: String): ByteArray {
        require(value.length % 4 == 0 && base64Pattern.matches(value)) { "UPDATE_ENVELOPE" }
        val decoded = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("UPDATE_ENVELOPE")
        }
        require(Base64.getEncoder().encodeToString(decoded) == value) { "UPDATE_ENVELOPE" }
        return decoded
    }
}
