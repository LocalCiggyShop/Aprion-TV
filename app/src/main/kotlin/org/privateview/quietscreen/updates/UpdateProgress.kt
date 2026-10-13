package org.privateview.quietscreen.updates

import java.util.Locale

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, VERIFYING, PREPARING, INSTALLING }

data class UpdateStatus(val message: String = "Updates are checked automatically when you open the app.",
    val busy: Boolean = false, val ready: Boolean = false, val automatic: Boolean = true, val detail: String? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE, val downloadedBytes: Long = 0, val totalBytes: Long = 0) {
    val showProgress get() = busy && phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.PREPARING, UpdatePhase.INSTALLING)
    val cancellable get() = phase == UpdatePhase.DOWNLOADING || phase == UpdatePhase.VERIFYING
    val fraction get() = if (totalBytes > 0) (downloadedBytes.coerceIn(0, totalBytes).toDouble() / totalBytes).toFloat() else 0f
    val sizeLabel get() = String.format(Locale.ROOT, "%.2f / %.2f MB", downloadedBytes.coerceAtLeast(0) / 1_000_000.0, totalBytes.coerceAtLeast(0) / 1_000_000.0)
}
