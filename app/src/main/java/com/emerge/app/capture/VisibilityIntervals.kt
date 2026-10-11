package com.emerge.app.capture

/** Regions describe the foreground screen, never gaze, reading or agreement. */
enum class VisibilityRegion(val code: String, val label: String) {
    MAIN("m", "正文/视频页面"), COMMENTS("c", "评论区"), UNLOCATED("u", "未定位")
}

data class VisibilityInterval(val start: Long, val end: Long, val durationMillis: Long,
    val region: VisibilityRegion)

/** A bounded, versioned format usable by capture, Room and JVM report calculations. */
object VisibilityIntervals {
    const val MAX_INTERVALS = 2048
    private const val MAX_CHARACTERS = 180_000

    fun decode(encoded: String): List<VisibilityInterval>? {
        if (encoded.isEmpty()) return emptyList()
        if (encoded.length > MAX_CHARACTERS || !encoded.startsWith("v1;")) return null
        val chunks = encoded.removePrefix("v1;").split(';')
        if (chunks.size > MAX_INTERVALS || chunks.any { it.isEmpty() }) return null
        val intervals = chunks.map { chunk ->
            val fields = chunk.split(',')
            if (fields.size != 4) return null
            val start = fields[0].toLongOrNull() ?: return null
            val end = fields[1].toLongOrNull() ?: return null
            val duration = fields[2].toLongOrNull() ?: return null
            val region = VisibilityRegion.entries.firstOrNull { it.code == fields[3] } ?: return null
            VisibilityInterval(start, end, duration, region)
        }
        return intervals.takeIf(::valid)
    }

    fun encode(intervals: List<VisibilityInterval>): String {
        require(intervals.size <= MAX_INTERVALS && valid(intervals))
        if (intervals.isEmpty()) return ""
        val encoded = "v1;" + intervals.joinToString(";") { "${it.start},${it.end},${it.durationMillis},${it.region.code}" }
        require(encoded.length <= MAX_CHARACTERS)
        return encoded
    }

    fun duration(intervals: List<VisibilityInterval>): Long = intervals.fold(0L) { sum, interval ->
        Math.addExact(sum, interval.durationMillis)
    }

    /** Null means an invalid or full record; the caller must split rather than discard measured time. */
    fun append(encoded: String, interval: VisibilityInterval): String? {
        val previous = decode(encoded) ?: return null
        return encodeCombined(previous + interval)
    }

    /** Only merge exact records with exact records. Legacy NULL must remain a separate visit. */
    fun merge(first: String, second: String): String? {
        val a = decode(first) ?: return null
        val b = decode(second) ?: return null
        return encodeCombined(a + b)
    }

    private fun encodeCombined(intervals: List<VisibilityInterval>): String? {
        if (!valid(intervals)) return null
        val merged = mutableListOf<VisibilityInterval>()
        intervals.forEach { next ->
            val previous = merged.lastOrNull()
            // Unequal wall/monotonic durations retain their individual placement around hour boundaries.
            if (previous != null && previous.region == next.region && previous.end == next.start &&
                previous.durationMillis == previous.end - previous.start && next.durationMillis == next.end - next.start) {
                merged[merged.lastIndex] = previous.copy(end = next.end,
                    durationMillis = previous.durationMillis + next.durationMillis)
            } else merged += next
        }
        if (merged.size > MAX_INTERVALS) return null
        return runCatching { encode(merged) }.getOrNull()
    }

    private fun valid(intervals: List<VisibilityInterval>): Boolean {
        var previousEnd = -1L
        var total = 0L
        for (interval in intervals) {
            if (interval.start < 0 || interval.end <= interval.start || interval.start < previousEnd ||
                interval.durationMillis <= 0 || interval.durationMillis > interval.end - interval.start ||
                total > Long.MAX_VALUE - interval.durationMillis) return false
            previousEnd = interval.end
            total += interval.durationMillis
        }
        return true
    }

    fun region(hint: WorkHint): VisibilityRegion = when (hint.page) {
        WorkPage.VIDEO, WorkPage.CAPTION -> VisibilityRegion.MAIN
        WorkPage.COMMENTS -> VisibilityRegion.COMMENTS
        else -> VisibilityRegion.UNLOCATED
    }
}
