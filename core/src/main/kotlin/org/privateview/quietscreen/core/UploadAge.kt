package org.privateview.quietscreen.core

/** Uses supplied public metadata only. Relative provider dates can be approximate. */
object UploadAge {
    const val MINUTE_MS = 60_000L
    fun label(uploadedAtMs: Long?, nowMs: Long, live: Boolean = false): String {
        if (live) return "Live video"
        if (uploadedAtMs == null || uploadedAtMs <= 0 || uploadedAtMs > nowMs) return "Upload date unavailable"
        val age = nowMs - uploadedAtMs
        if (age < 1_000) return "Just uploaded"
        val (unit, scale) = when {
            age < MINUTE_MS -> "second" to 1_000L
            age < 3_600_000 -> "minute" to MINUTE_MS
            age < 86_400_000 -> "hour" to 3_600_000L
            age < 2_592_000_000L -> "day" to 86_400_000L
            age < 31_536_000_000L -> "month" to 2_592_000_000L
            else -> "year" to 31_536_000_000L
        }
        val count = age / scale
        return "Uploaded $count $unit${if (count == 1L) "" else "s"} ago"
    }
    fun recent(uploadedAtMs: Long?, nowMs: Long) = uploadedAtMs != null && uploadedAtMs > 0 &&
        uploadedAtMs <= nowMs && nowMs - uploadedAtMs < MINUTE_MS
}
