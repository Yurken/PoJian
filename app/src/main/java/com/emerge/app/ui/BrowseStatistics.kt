package com.emerge.app.ui

import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.analysis.BrowseWorkKey
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class StatisticsPeriod(val label: String, val shortLabel: String, val reviewTitle: String,
    val calendarDays: Int = 1, val rollingHours: Int = 0) {
    DAY("今天", "今天", "今日回顾"),
    HOURS24("过去 24 小时", "24 小时", "24 小时回顾", rollingHours = 24),
    WEEK("最近 7 天", "7 天", "七日回顾", calendarDays = 7),
    HOUR("过去 1 小时", "1 小时", "小时回顾", rollingHours = 1),
    MONTH("最近 30 天", "30 天", "30 天回顾", calendarDays = 30);
    val dailyBuckets: Boolean get() = calendarDays > 1
}
data class TrendBucket(val label: String, val count: Int, val durationMillis: Long = 0,
    val unknownDurationRecords: Int = 0)
data class AppShare(val packageName: String, val count: Int, val durationMillis: Long = 0,
    val unknownDurationRecords: Int = 0, val visitCount: Int = count, val unidentifiedWorks: Int = 0)
data class BrowseStatistics(val total: Int, val appCount: Int, val textCharacters: Long,
    val trend: List<TrendBucket>, val apps: List<AppShare>, val observedDurationMillis: Long = 0,
    val unknownDurationRecords: Int = 0, val visitCount: Int = total, val unidentifiedWorks: Int = 0)

// Each retained record is counted once overall. A session touching two buckets appears in both.
fun calculateStatistics(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): BrowseStatistics {
    val window = ObservationWindow.forPeriod(period, now, zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // Audio segments and model-transcribed frames are material, not separately watched works.
    val selected = events.filter { it.sourceType == "accessibility" && window.contains(it) }
    val keys = selected.zip(BrowseWorkKey.resolve(selected))
    val firstHour = Instant.ofEpochMilli(window.start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
    val count = when {
        period.dailyBuckets -> period.calendarDays
        period == StatisticsPeriod.DAY -> 24
        else -> ChronoUnit.HOURS.between(firstHour,
            Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS)).toInt() + 1
    }
    val bucketKeys = List(count) { mutableSetOf<String>() }
    val bucketDurations = LongArray(count)
    val bucketSlices = List(count) { mutableListOf<com.emerge.app.analysis.SessionSlice>() }
    val bucketUnknown = IntArray(count)
    keys.forEach { (event, workKey) ->
        val slices = window.slices(event)
        fun index(timestamp: Long, hour: Int) = when {
            period == StatisticsPeriod.DAY -> hour
            period.dailyBuckets -> ChronoUnit.DAYS.between(today.minusDays(period.calendarDays - 1L), Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()).toInt()
            else -> ChronoUnit.HOURS.between(firstHour,
                Instant.ofEpochMilli(timestamp).atZone(zone).truncatedTo(ChronoUnit.HOURS)).toInt()
        }
        val touched = mutableSetOf<Int>()
        slices.forEach { slice ->
            val bucket = index(slice.start, slice.hour)
            if (bucket in 0 until count) {
                touched += bucket
                bucketSlices[bucket] += slice
            }
        }
        touched.forEach { bucket ->
            bucketKeys[bucket] += workKey
            if (!window.durationKnown(event)) bucketUnknown[bucket]++
        }
    }
    val trend = bucketKeys.mapIndexed { index, works ->
        bucketDurations[index] = window.unionSlices(bucketSlices[index])
        val label = when {
            period == StatisticsPeriod.DAY -> "${index.toString().padStart(2, '0')}:00"
            period.dailyBuckets -> if (index == count - 1) "今天" else today.minusDays(count - 1L - index).format(DateTimeFormatter.ofPattern("M/d"))
            else -> firstHour.plusHours(index.toLong()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        }
        TrendBucket(label, works.size, bucketDurations[index], bucketUnknown[index])
    }
    val apps = keys.groupBy { it.first.packageName }.map { (name, entries) ->
        val records = entries.map { it.first }
        AppShare(name, entries.map { it.second }.distinct().size, window.unionDuration(records),
            records.count { !window.durationKnown(it) }, records.size,
            entries.filter { it.first.identityMethod in setOf("unknown", "session_estimate") }.map { it.second }.distinct().size)
    }.sortedWith(compareByDescending<AppShare> { it.count }.thenBy { it.packageName })
    return BrowseStatistics(keys.map { it.second }.distinct().size, apps.size, selected.sumOf { it.visibleText.length.toLong() }, trend,
        apps, bucketDurations.sum(), selected.count { !window.durationKnown(it) }, selected.size,
        apps.sumOf { it.unidentifiedWorks })
}
