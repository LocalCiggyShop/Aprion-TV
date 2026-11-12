package org.privateview.quietscreen.core

/** UTF-16 offsets for the editor; remote movement never splits a surrogate pair. */
object SearchCaret {
    fun clamp(text: String, offset: Int): Int {
        val index = offset.coerceIn(0, text.length)
        return if (index in 1 until text.length && Character.isHighSurrogate(text[index - 1]) &&
            Character.isLowSurrogate(text[index])) index - 1 else index
    }

    fun move(text: String, offset: Int, direction: Int): Int {
        val index = clamp(text, offset)
        return when {
            direction < 0 && index > 0 -> Character.offsetByCodePoints(text, index, -1)
            direction > 0 && index < text.length -> Character.offsetByCodePoints(text, index, 1)
            else -> index
        }
    }

    fun limit(text: String, maximum: Int = 200): String = text.substring(0, clamp(text, maximum.coerceAtLeast(0)))
}

/** Preserve a deliberate remote caret against the IME's initial unchanged-word correction. */
class SearchTypingAnchor {
    private var original: String? = null
    private var offset = 0
    fun arm(text: String, cursor: Int) {
        offset = SearchCaret.clamp(text, cursor)
        original = text.takeIf { offset < it.length }
    }
    fun clear() { original = null }
    fun correction(text: String, start: Int, end: Int): Int? {
        val expected = original ?: return null
        if (text != expected || start != end) { clear(); return null }
        if (end == offset) return null
        clear()
        return offset.takeIf { end == text.length }
    }
}
