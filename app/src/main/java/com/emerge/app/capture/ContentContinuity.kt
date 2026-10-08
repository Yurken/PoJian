package com.emerge.app.capture

/** Conservative textual identity: numbers inside prose remain significant. No semantic inference. */
object ContentContinuity {
    fun same(first: String, second: String): Boolean {
        if (first == second) return true
        fun anchors(text: String) = text.lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 16 && it.any(Char::isLetter) }
            .toSet()
        val a = anchors(first)
        val b = anchors(second)
        if (a.isEmpty() || b.isEmpty()) return false
        val common = a.intersect(b)
        return common.any { it.length >= 24 } && common.size.toDouble() / a.union(b).size >= 0.7
    }
}
