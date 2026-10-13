package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.capture.VisibilityInterval
import com.emerge.app.capture.VisibilityIntervals
import com.emerge.app.capture.VisibilityRegion
import com.emerge.app.ui.StatisticsPeriod
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Wall-clock placement of a measured session. Legacy snapshots have no inferred duration. */
data class SessionSlice(val start: Long, val end: Long, val durationMillis: Long, val hour: Int,
    val region: VisibilityRegion? = null)

class ObservationWindow(val start: Long, val end: Long, val zone: ZoneId) {
    companion object {
        fun forPeriod(period: StatisticsPeriod, now: Long, zone: ZoneId): ObservationWindow {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            if (period == StatisticsPeriod.HOURS24) return ObservationWindow(now - 86_400_000L, now, zone)
            val firstDay = if (period == StatisticsPeriod.DAY) today else today.minusDays(6)
            return ObservationWindow(firstDay.atStartOfDay(zone).toInstant().toEpochMilli(), now, zone)
        }
    }

    private fun exactIntervals(event: BrowseEvent): List<VisibilityInterval>? = event.visibilityIntervals?.let { encoded ->
        VisibilityIntervals.decode(encoded)?.takeIf { intervals ->
            VisibilityIntervals.duration(intervals) == event.durationMillis && intervals.all {
                it.start >= event.timestamp && it.end <= event.endTimestamp
            }
        }
    }

    fun durationKnown(event: BrowseEvent): Boolean = event.sampleCount > 0 && event.durationMillis >= 0 &&
        (event.endTimestamp == 0L || event.endTimestamp >= event.timestamp) &&
        (event.visibilityIntervals == null || exactIntervals(event) != null)

    private fun sessionEnd(event: BrowseEvent): Long {
        if (!durationKnown(event)) return event.timestamp
        if (event.endTimestamp > 0) return event.endTimestamp
        // Early session records may lack the endpoint. Only an explicitly measured duration can place it.
        return if (event.durationMillis > Long.MAX_VALUE - event.timestamp.coerceAtLeast(0)) Long.MAX_VALUE
            else event.timestamp + event.durationMillis
    }

    fun contains(event: BrowseEvent): Boolean {
        val exact = if (event.visibilityIntervals != null && durationKnown(event)) exactIntervals(event) else null
        if (exact != null) return exact.any { it.start <= end && it.end > start } ||
            event.timestamp in start..end || event.endTimestamp in start..end
        return event.timestamp <= end && (event.timestamp >= start || sessionEnd(event) > start)
    }

    fun slices(event: BrowseEvent): List<SessionSlice> {
        if (!contains(event)) return emptyList()
        if (event.visibilityIntervals != null) {
            val intervals = exactIntervals(event).takeIf { durationKnown(event) }
            // A damaged exact record must never silently become a whole-visit duration estimate.
            if (intervals == null) return listOf(point(maxOf(start, event.timestamp)))
            val slices = intervals.flatMap { interval -> split(interval.start, interval.end,
                interval.durationMillis, interval.region) }
            if (slices.isNotEmpty()) return slices
            val at = if (event.timestamp in start..end) event.timestamp else event.endTimestamp.coerceIn(start, end)
            return listOf(point(at))
        }
        val measuredEnd = sessionEnd(event)
        val sliceStart = maxOf(start, event.timestamp)
        val sliceEnd = minOf(end, measuredEnd)
        if (sliceEnd <= sliceStart) return listOf(point(sliceStart))
        val span = measuredEnd - event.timestamp
        // Clock adjustments must not produce more measured time than the visible wall-clock span.
        val measuredDuration = event.durationMillis.coerceIn(0, span)
        return split(event.timestamp, measuredEnd, measuredDuration)
    }

    private fun point(at: Long) = SessionSlice(at, at, 0, Instant.ofEpochMilli(at).atZone(zone).hour)

    private fun split(intervalStart: Long, intervalEnd: Long, measuredDuration: Long,
        region: VisibilityRegion? = null): List<SessionSlice> {
        val sliceStart = maxOf(start, intervalStart)
        val sliceEnd = minOf(end, intervalEnd)
        if (sliceEnd <= sliceStart) return emptyList()
        val span = intervalEnd - intervalStart
        fun durationBefore(boundary: Long): Long = BigInteger.valueOf(measuredDuration)
            .multiply(BigInteger.valueOf(boundary - intervalStart)).divide(BigInteger.valueOf(span)).toLong()
        val result = mutableListOf<SessionSlice>()
        var cursor = sliceStart
        while (cursor < sliceEnd) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val boundary = minOf(sliceEnd, nextHour)
            result += SessionSlice(cursor, boundary, durationBefore(boundary) - durationBefore(cursor), local.hour, region)
            cursor = boundary
        }
        return result
    }

    fun durationMillis(event: BrowseEvent): Long? = if (durationKnown(event)) slices(event).sumOf { it.durationMillis } else null

    /** Sweep the union of measured intervals. Duplicate/overlapping visits cannot multiply time.
     * Legacy proportional estimates retain their density; rounding can only reduce the result. */
    fun unionDuration(events: List<BrowseEvent>): Long = unionSlices(events.filter(::durationKnown).flatMap(::slices))

    fun unionSlices(slices: List<SessionSlice>): Long {
        val valid = slices.filter { it.end > it.start && it.durationMillis > 0 }.sortedBy { it.start }
        val boundaries = valid.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val active = java.util.PriorityQueue<SessionSlice> { a,b ->
            BigInteger.valueOf(b.durationMillis).multiply(BigInteger.valueOf(a.end-a.start))
                .compareTo(BigInteger.valueOf(a.durationMillis).multiply(BigInteger.valueOf(b.end-b.start)))
        }
        var cursor=0; var total=0L
        boundaries.zipWithNext().forEach { (start,end) ->
            while(cursor < valid.size && valid[cursor].start <= start) active.add(valid[cursor++])
            while(active.isNotEmpty() && active.peek().end <= start) active.poll()
            active.peek()?.let { slice -> total += BigInteger.valueOf(end-start)
                .multiply(BigInteger.valueOf(slice.durationMillis)).divide(BigInteger.valueOf(slice.end-slice.start)).toLong() }
        }
        return total
    }
}
