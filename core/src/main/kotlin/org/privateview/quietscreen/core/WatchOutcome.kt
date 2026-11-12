package org.privateview.quietscreen.core

/** Session evidence, independent of position/resume records. Interruptions are never dislikes. */
data class WatchOutcome(val watchedMs: Long, val durationMs: Long, val autoplay: Boolean = false,
    val ended: Boolean = false, val interrupted: Boolean = false, val failed: Boolean = false,
    val sought: Boolean = false) {
    val gain: Double get() {
        val watched = watchedMs.coerceIn(0, 3_600_000)
        val fraction = if (!sought && durationMs > 0) (watched.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        if (watched < 30_000 && !(watched >= 10_000 && fraction >= .8)) return 0.0
        return (kotlin.math.sqrt((watched / 60_000.0).coerceAtMost(10.0)) + fraction * 2) * if (autoplay) .5 else 1.0
    }
}

/** Process-session repeat saturation; contains no persistent detailed viewing history. */
class SessionCredits {
    private val credits = linkedMapOf<String, Double>()
    fun weight(id: VideoId, gain: Double): Double {
        if (!gain.isFinite() || gain <= 0) return 0.0
        val previous = credits[id.value] ?: 0.0
        val accepted = gain.coerceAtMost((6.0 - previous).coerceAtLeast(0.0))
        credits.remove(id.value)
        credits[id.value] = previous + accepted
        while (credits.size > 32) credits.remove(credits.keys.first())
        return accepted / gain
    }
    fun clear() = credits.clear()
}

/** One visit's visible dwell accounting. Call with monotonic time and the current visible IDs. */
class RecommendationImpressions {
    private val since = mutableMapOf<String, Long>()
    private val recorded = mutableSetOf<String>()
    fun update(visible: Set<String>, nowMs: Long): Set<String> {
        since.keys.retainAll(visible)
        visible.take(24).forEach { since.putIfAbsent(it, nowMs) }
        val ready = since.filter { (id, time) -> id !in recorded && nowMs - time >= 750 }.keys.toSet()
        recorded.addAll(ready.take((96 - recorded.size).coerceAtLeast(0)))
        return ready.filter { it in recorded }.toSet()
    }
    fun selected(id: String): Boolean = recorded.size < 96 && recorded.add(id)
    fun clear() { since.clear(); recorded.clear() }
}
