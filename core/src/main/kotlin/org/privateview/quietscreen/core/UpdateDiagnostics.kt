package org.privateview.quietscreen.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class UpdateStep(val label: String) {
    FEED("checking the update feed"), MANIFEST("verifying update metadata"),
    DOWNLOAD("downloading the update"), APK("verifying the APK"), INSTALL("installing the update")
}
enum class UpdateProblem(val advice: String) {
    DNS("GitHub's address could not be resolved. Check your connection or DNS filtering."),
    CONNECTION("GitHub could not be reached. Try another connection or retry later."),
    TIMEOUT("GitHub did not respond in time. Check the connection and retry."),
    TLS("A secure connection could not be verified. Check the TV's date/time and system updates."),
    HTTP("GitHub refused or could not serve the request. Retry later or download the official APK manually."),
    POLICY("The update request was blocked by a security check. Use the official release or report this code."),
    METADATA("The update information is invalid or too large. Retry later; do not install an unverified file."),
    SIGNATURE("The update signature could not be verified. Use the official release or report this code."),
    COMPATIBILITY("This update needs a newer Android version. Check the TV's system updates."),
    SIZE("The download was incomplete or had the wrong size. Retry the download."),
    HASH("The downloaded APK did not match its signed checksum. Retry the download."),
    IDENTITY("The APK package, version or Android requirements did not match the signed update."),
    SIGNER("The APK was not signed by the installed app's key. Use the official release."),
    ARCHIVE("Android could not read the downloaded APK. Retry or use the official release."),
    STORAGE("The update could not be saved. Free space on the TV and retry."),
    INSTALL_PERMISSION("Allow installation from Aprion TV in Android settings, then retry."),
    INSTALL_ABORTED("Installation was cancelled. Choose Install update to try again."),
    INSTALL_BLOCKED("Android blocked installation. Check installation permission and device restrictions."),
    INSTALL_INCOMPATIBLE("Android reports this update is incompatible with the device."),
    INSTALL_CONFLICT("Android reports a package conflict. Report this code before uninstalling the app."),
    INSTALL_INVALID("Android rejected the update APK. Download the official release again."),
    INSTALLER("Android could not open or finish the installer. Retry or use Downloader."),
    UNKNOWN("The update could not finish. Retry or report the diagnostic code below.")
}

/** Only fixed codes and numeric HTTP status escape the updater; never exception text/URLs. */
class UpdateFailure(val problem: UpdateProblem, val httpStatus: Int? = null) : IOException(problem.name)
data class UpdateDiagnostic(val step: UpdateStep, val problem: UpdateProblem, val httpStatus: Int? = null) {
    val code get() = "QS-U-${step.name}-${problem.name}" + (httpStatus?.takeIf { it in 100..599 }?.let { "-$it" } ?: "")
    val message get() = "Update failed while ${step.label}. ${problem.advice}"
}
object UpdateDiagnostics {
    fun from(error: Throwable, step: UpdateStep): UpdateDiagnostic {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        chain.filterIsInstance<UpdateFailure>().firstOrNull()?.let { return UpdateDiagnostic(step, it.problem, it.httpStatus) }
        val reason = when {
            chain.any { it is UnknownHostException } -> UpdateProblem.DNS
            chain.any { it is SSLException } -> UpdateProblem.TLS
            chain.any { it is SocketTimeoutException } -> UpdateProblem.TIMEOUT
            chain.any { it is ConnectException } -> UpdateProblem.CONNECTION
            else -> chain.firstNotNullOfOrNull { known(it.message) }
                ?: if (chain.any { it is IOException }) UpdateProblem.CONNECTION else UpdateProblem.UNKNOWN
        }
        return UpdateDiagnostic(step, reason)
    }
    private fun known(code: String?) = when (code) {
        "UPDATE_ENVELOPE", "UPDATE_PAYLOAD" -> UpdateProblem.METADATA
        "UPDATE_SIGNATURE", "UPDATE_KEY", "UPDATE_INSTALLED_SIGNER" -> UpdateProblem.SIGNATURE
        "UPDATE_SDK" -> UpdateProblem.COMPATIBILITY
        "UPDATE_PACKAGE", "UPDATE_IDENTITY", "UPDATE_OLD" -> UpdateProblem.IDENTITY
        "UPDATE_URL", "UPDATE_REDIRECT", "UPDATE_DNS" -> UpdateProblem.POLICY
        "UPDATE_SIZE" -> UpdateProblem.SIZE
        "UPDATE_HASH" -> UpdateProblem.HASH
        "UPDATE_ARCHIVE" -> UpdateProblem.ARCHIVE
        "UPDATE_SIGNER" -> UpdateProblem.SIGNER
        "UPDATE_STORAGE" -> UpdateProblem.STORAGE
        else -> null
    }
}
