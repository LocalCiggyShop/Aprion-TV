package org.privateview.quietscreen.updates

import java.security.MessageDigest

/** Prefer current signers. A missing modern result may use Android's verified legacy result.
 * Never fall back from a mismatch or multiple signers. The release key is not rotated. */
internal object UpdateCertificates {
    fun select(modern: List<ByteArray>, legacy: () -> List<ByteArray>): List<ByteArray> =
        if (modern.isNotEmpty()) modern else legacy()

    fun requireSame(incoming: List<ByteArray>, installed: List<ByteArray>) {
        val code = when {
            incoming.isEmpty() -> "UPDATE_SIGNER_MISSING"
            installed.isEmpty() -> "UPDATE_INSTALLED_SIGNER"
            incoming.size != 1 || installed.size != 1 || !MessageDigest.isEqual(incoming.single(), installed.single()) -> "UPDATE_SIGNER"
            else -> return
        }
        throw UpdateSignerFailure(code, incoming, installed)
    }
}

/** Public certificate fingerprints/counts only; never exception text, keys or URLs. */
internal class UpdateSignerFailure(code: String, incoming: List<ByteArray>, installed: List<ByteArray>) : IllegalArgumentException(code) {
    private fun fingerprints(certificates: List<ByteArray>) = certificates.take(2).joinToString(",") { certificate ->
        MessageDigest.getInstance("SHA-256").digest(certificate).take(6).joinToString("") { "%02x".format(it) }
    }.ifEmpty { "none" }
    val safeDetail = "APK certificates: ${incoming.size} (${fingerprints(incoming)}) · Installed: ${installed.size} (${fingerprints(installed)})"
}
