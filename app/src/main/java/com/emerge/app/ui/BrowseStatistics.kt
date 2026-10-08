package com.emerge.app.ui

import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class StatisticsPeriod(val label: String) { DAY("今天"), WEEK("最近 7 天") }
data class TrendBucket(val label: String, val count: Int, val durationMillis: Long = 0,
    val unknownDurationRecords: Int = 0)
data class AppShare(val packageName: String, val count: Int, val durationMillis: Long = 0,
    val unknownDurationRecords: Int = 0)
data class BrowseStatistics(val total: Int, val appCount: Int, val textCharacters: Long,
    val trend: List<TrendBucket>, val apps: List<AppShare>, val observedDurationMillis: Long = 0,
    val unknownDurationRecords: Int = 0)

// Each retained record is counted once overall. A session touching two buckets appears in both.
fun calculateStatistics(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): BrowseStatistics {
    val window = ObservationWindow.forPeriod(period, now, zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val selected = events.filter { window.contains(it) }
    val count = if (period == StatisticsPeriod.DAY) 24 else 7
    val bucketCounts = IntArray(count)
    val bucketDurations = LongArray(count)
    val bucketUnknown = IntArray(count)
    selected.forEach { event ->
        val slices = window.slices(event)
        fun index(timestamp: Long, hour: Int) = if (period == StatisticsPeriod.DAY) hour else
            ChronoUnit.DAYS.between(today.minusDays(6), Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()).toInt()
        val touched = mutableSetOf<Int>()
        slices.forEach { slice ->
            val bucket = index(slice.start, slice.hour)
            if (bucket in 0 until count) {
                touched += bucket
                bucketDurations[bucket] += slice.durationMillis
            }
        }
        touched.forEach { bucket ->
            bucketCounts[bucket]++
            if (!window.durationKnown(event)) bucketUnknown[bucket]++
        }
    }
    val trend = bucketCounts.mapIndexed { index, records ->
        val label = if (period == StatisticsPeriod.DAY) "${index.toString().padStart(2, '0')}:00"
        else if (index == 6) "今天" else today.minusDays(6 - index.toLong()).format(DateTimeFormatter.ofPattern("M/d"))
        TrendBucket(label, records, bucketDurations[index], bucketUnknown[index])
    }
    val apps = selected.groupBy { it.packageName }.map { (name, records) ->
        AppShare(name, records.size, records.sumOf { window.durationMillis(it) ?: 0 },
            records.count { !window.durationKnown(it) })
    }.sortedWith(compareByDescending<AppShare> { it.count }.thenBy { it.packageName })
    return BrowseStatistics(selected.size, apps.size, selected.sumOf { it.visibleText.length.toLong() }, trend,
        apps, bucketDurations.sum(), selected.count { !window.durationKnown(it) })
}
