package com.zhiheng.app.ui

import com.zhiheng.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class StatisticsPeriod(val label: String) { DAY("过去 24 小时"), WEEK("最近 7 天") }
data class TrendBucket(val label: String, val count: Int)
data class AppShare(val packageName: String, val count: Int)
data class BrowseStatistics(val total: Int, val appCount: Int, val textCharacters: Long,
    val trend: List<TrendBucket>, val apps: List<AppShare>)

// Statistics use all retained database records, not the 200-item history preview.
fun calculateStatistics(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): BrowseStatistics {
    val end = Instant.ofEpochMilli(now)
    val today = end.atZone(zone).toLocalDate()
    val start = if (period == StatisticsPeriod.DAY) end.minusSeconds(24 * 3600)
        else today.minusDays(6).atStartOfDay(zone).toInstant()
    val selected = events.filter { it.timestamp >= start.toEpochMilli() && it.timestamp <= now }
    val bucketCounts = IntArray(if (period == StatisticsPeriod.DAY) 24 else 7)
    selected.forEach { event ->
        val index = if (period == StatisticsPeriod.DAY)
            ((event.timestamp - start.toEpochMilli()) / 3_600_000).toInt().coerceAtMost(23)
        else java.time.temporal.ChronoUnit.DAYS.between(today.minusDays(6),
            Instant.ofEpochMilli(event.timestamp).atZone(zone).toLocalDate()).toInt()
        bucketCounts[index]++
    }
    val trend = bucketCounts.mapIndexed { index, count ->
        val label = if (period == StatisticsPeriod.DAY)
            start.plusSeconds(index * 3600L).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        else if (index == 6) "今天" else today.minusDays(6 - index.toLong()).format(DateTimeFormatter.ofPattern("M/d"))
        TrendBucket(label, count)
    }
    val apps = selected.groupingBy { it.packageName }.eachCount().map { AppShare(it.key, it.value) }
        .sortedWith(compareByDescending<AppShare> { it.count }.thenBy { it.packageName })
    return BrowseStatistics(selected.size, apps.size, selected.sumOf { it.visibleText.length.toLong() }, trend, apps)
}
