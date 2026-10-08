package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Wall-clock placement of a measured session. Legacy snapshots have no inferred duration. */
data class SessionSlice(val start: Long, val end: Long, val durationMillis: Long, val hour: Int)

class ObservationWindow(val start: Long, val end: Long, val zone: ZoneId) {
    companion object {
        fun forPeriod(period: StatisticsPeriod, now: Long, zone: ZoneId): ObservationWindow {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val firstDay = if (period == StatisticsPeriod.DAY) today else today.minusDays(6)
            return ObservationWindow(firstDay.atStartOfDay(zone).toInstant().toEpochMilli(), now, zone)
        }
    }

    fun durationKnown(event: BrowseEvent): Boolean = event.sampleCount > 0 && event.durationMillis >= 0 &&
        (event.endTimestamp == 0L || event.endTimestamp >= event.timestamp)

    private fun sessionEnd(event: BrowseEvent): Long {
        if (!durationKnown(event)) return event.timestamp
        if (event.endTimestamp > 0) return event.endTimestamp
        // Early session records may lack the endpoint. Only an explicitly measured duration can place it.
        return if (event.durationMillis > Long.MAX_VALUE - event.timestamp.coerceAtLeast(0)) Long.MAX_VALUE
            else event.timestamp + event.durationMillis
    }

    fun contains(event: BrowseEvent): Boolean = event.timestamp <= end &&
        (event.timestamp >= start || sessionEnd(event) > start)

    fun slices(event: BrowseEvent): List<SessionSlice> {
        if (!contains(event)) return emptyList()
        val measuredEnd = sessionEnd(event)
        val sliceStart = maxOf(start, event.timestamp)
        val sliceEnd = minOf(end, measuredEnd)
        if (sliceEnd <= sliceStart) return listOf(SessionSlice(sliceStart, sliceStart, 0,
            Instant.ofEpochMilli(sliceStart).atZone(zone).hour))
        val span = measuredEnd - event.timestamp
        // Clock adjustments must not produce more measured time than the visible wall-clock span.
        val measuredDuration = event.durationMillis.coerceIn(0, span)
        fun durationBefore(boundary: Long): Long = BigInteger.valueOf(measuredDuration)
            .multiply(BigInteger.valueOf(boundary - event.timestamp)).divide(BigInteger.valueOf(span)).toLong()
        val result = mutableListOf<SessionSlice>()
        var cursor = sliceStart
        while (cursor < sliceEnd) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val boundary = minOf(sliceEnd, nextHour)
            result += SessionSlice(cursor, boundary, durationBefore(boundary) - durationBefore(cursor), local.hour)
            cursor = boundary
        }
        return result
    }

    fun durationMillis(event: BrowseEvent): Long? = if (durationKnown(event)) slices(event).sumOf { it.durationMillis } else null
}
