package org.privateview.quietscreen.extractor

/** Explicit, in-memory test observation. Disabled by default, bounded, and stores
 * only allowed destination hosts/roles; never URLs, search terms, IDs or headers. */
object NetworkAudit {
    private val counts = LinkedHashMap<String, Long>()
    @Volatile private var enabled = false
    @Synchronized fun begin() { counts.clear(); enabled = true }
    @Synchronized fun end(): Map<String, Long> { enabled = false; return counts.toMap() }
    @Synchronized fun snapshot(): Map<String, Long> = counts.toMap()
    @Synchronized internal fun record(role: NetworkRole, host: String) {
        if (!enabled) return
        val key = "$role:$host"
        val boundedKey = if (key in counts || counts.size < 128) key else "$role:<additional allowed hosts>"
        counts[boundedKey] = (counts[boundedKey] ?: 0) + 1
    }
}
