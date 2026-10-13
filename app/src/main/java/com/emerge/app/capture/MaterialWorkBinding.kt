package com.emerge.app.capture

import com.emerge.app.data.BrowseEvent

/** A full playback segment must fit one measured visit, with no observation holes. */
object MaterialWorkBinding {
    fun playbackParent(visits: List<BrowseEvent>, packageName: String, start: Long, end: Long): BrowseEvent? {
        if (start < 0 || end <= start) return null
        return visits.filter { visit ->
            visit.sourceType == "accessibility" && visit.packageName == packageName && visit.workKey != null &&
                visit.timestamp <= start && visit.endTimestamp >= end &&
                visit.visibilityIntervals?.let(VisibilityIntervals::decode)?.let { intervals ->
                    if (VisibilityIntervals.duration(intervals) != visit.durationMillis) return@let false
                    var covered = start
                    for (interval in intervals.sortedBy { it.start }) {
                        if (interval.start > covered) break
                        // Proportional legacy/clock-adjusted intervals aren't evidence of continuous visibility.
                        if (interval.durationMillis == interval.end - interval.start && interval.end > covered)
                            covered = interval.end
                    }
                    covered >= end
                } == true
        }.singleOrNull()
    }
}
