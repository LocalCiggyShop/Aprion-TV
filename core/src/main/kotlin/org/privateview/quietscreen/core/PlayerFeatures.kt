package org.privateview.quietscreen.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

fun viewCountLabel(count: Long?, compact: Boolean = true): String {
    if (count == null || count < 0) return "Views unavailable"
    if (!compact) return NumberFormat.getIntegerInstance(Locale.US).format(count) + if (count == 1L) " view" else " views"
    val unit = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K").firstOrNull { count >= it.first }
    return if (unit == null) "$count " + if (count == 1L) "view" else "views"
    else BigDecimal.valueOf(count).divide(BigDecimal.valueOf(unit.first), 1, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + unit.second + " views"
}

/** URLs remain redacted and memory-only; arithmetic is independent of upstream's bounds helper. */
class PreviewStoryboard(val sheets: List<MediaEndpoint>, val width: Int, val height: Int,
    val columns: Int, val rows: Int, val count: Int, val intervalMs: Long) {
    init {
        require(sheets.size in 1..256 && width in 1..640 && height in 1..360)
        require(columns in 1..20 && rows in 1..20 && count in 1..100_000 && intervalMs in 1..3_600_000)
        require(width.toLong() * columns * height * rows <= 16_000_000)
        require((count.toLong() + columns * rows - 1) / (columns * rows) <= sheets.size)
    }
    fun frame(positionMs: Long): PreviewFrame {
        val frame = (positionMs.coerceAtLeast(0) / intervalMs).coerceAtMost(count.toLong() - 1).toInt()
        val perSheet = columns * rows
        val local = frame % perSheet
        return PreviewFrame(frame / perSheet, local % columns * width, local / columns * height, width, height)
    }
}
data class PreviewFrame(val sheet: Int, val left: Int, val top: Int, val width: Int, val height: Int) {
    fun fits(imageWidth: Int, imageHeight: Int) = left >= 0 && top >= 0 && left.toLong() + width <= imageWidth && top.toLong() + height <= imageHeight
}

data class SeekSession(val originalMs: Long, val pendingMs: Long, val durationMs: Long, val resume: Boolean) {
    init { require(durationMs > 0 && originalMs in 0..durationMs && pendingMs in 0..durationMs) }
    fun move(forward: Boolean, repeated: Int = 0): SeekSession {
        val step = when { repeated >= 30 -> 60_000L; repeated >= 10 -> 30_000L; else -> 10_000L }
        val next = if (forward) pendingMs + minOf(step, durationMs - pendingMs) else pendingMs - minOf(step, pendingMs)
        return copy(pendingMs = next)
    }
}

/** One countdown per completion; cancellation is sticky until playback leaves ended state. */
class AutoplayCountdown {
    private var cancelled = false
    private var deadline: Long? = null
    var next: VideoId? = null; private set
    fun reset() { cancelled = false; deadline = null; next = null }
    fun cancel() { cancelled = true; deadline = null; next = null }
    fun update(ended: Boolean, enabled: Boolean, eligible: List<VideoId>, nowMs: Long): Int? {
        if (!ended) { reset(); return null }
        if (!enabled) { cancel(); return null }
        if (cancelled) return null
        if (next != null && next !in eligible) { cancel(); return null }
        if (next == null) {
            next = eligible.firstOrNull() ?: return null
            deadline = nowMs + 15_000
        }
        return ((deadline!! - nowMs).coerceAtLeast(0) + 999).div(1000).toInt()
    }
}
