package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ViewpointItem(val record: BrowseEvent, val label: String?, val quote: String?, val emotional: Boolean)
data class ViewpointBucket(val label: String, val start: Long, val end: Long,
    val items: List<ViewpointItem>, val score: Int?, val repeated: Int, val emotional: Int)
data class ViewpointShare(val label: String, val items: List<ViewpointItem>, val total: Int) {
    val percent: Double get() = if (total == 0) 0.0 else items.size * 100.0 / total
}

/** Local, auditable exposure signals; never a measurement of the user's mental state. */
object ViewpointTimeline {
    private val emotion = Regex("焦虑|恐慌|恐惧|绝望|完蛋|没有未来|毁掉")
    private val negatedEmotion = Regex("(?:不必|不用|无需|别|不要)[^，。；]{0,6}(?:焦虑|恐慌|恐惧|绝望)")
    private val predicate = Regex("应该|必须|需要|不能|独立|自主|焦虑|认为|觉得|导致|越来越|意味着|会|难|值得|自由")
    fun groups(items: List<ViewpointItem>): List<ViewpointShare> {
        val works = items.groupBy { it.record.packageName + "|" +
            (it.record.workKey ?: "record:${it.record.id}:${it.record.timestamp}") }.values.map { visits ->
            visits.firstOrNull { it.label != null } ?: visits.first()
        }
        return works.groupBy { it.label ?: "暂未识别观点" }.map { (label, entries) ->
            ViewpointShare(label, entries, works.size)
        }.sortedWith(compareByDescending<ViewpointShare> { it.items.size }.thenBy { it.label })
    }
    fun label(quote: String): String = ViewpointLabeler.label(quote)

    fun build(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long, zone: ZoneId,
        report: CognitiveReport? = null): List<ViewpointBucket> {
        val window = ObservationWindow.forPeriod(period, now, zone)
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = if (period == StatisticsPeriod.WEEK) day.minusDays(6).atStartOfDay(zone)
            else Instant.ofEpochMilli(window.start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val count = when (period) {
            StatisticsPeriod.WEEK -> 7
            StatisticsPeriod.DAY -> 24
            else -> ChronoUnit.HOURS.between(first, Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS)).toInt() + 1
        }
        val selected = events.filter { it.sourceType == "accessibility" && window.contains(it) }
        val observations = report?.takeIf { it.period == period.label }?.claims?.groupBy { it.eventId }.orEmpty()
        val candidates = selected.associateWith { event ->
            val quote = observations[event.id]?.firstOrNull()?.quote ?: event.visibleText.lines().map {
                it.trim().replace(Regex("(?:\\.\\.\\.|…)?\\s*展开[>〉]?\\s*$"), "").trim()
            }
                .firstOrNull { it.length in 10..300 && predicate.containsMatchIn(it) &&
                    !Regex("按钮|来自|回复|评论|图片[,，]").containsMatchIn(it) }
            ViewpointItem(event, quote?.let(::label), quote, quote?.let {
                emotion.containsMatchIn(it) && !negatedEmotion.containsMatchIn(it)
            } == true)
        }
        val slices = selected.associateWith(window::slices)
        return List(count) { index ->
            val localStart = if (period == StatisticsPeriod.WEEK) first.plusDays(index.toLong()) else first.plusHours(index.toLong())
            val localEnd = if (period == StatisticsPeriod.WEEK) localStart.plusDays(1) else localStart.plusHours(1)
            val start = maxOf(window.start, localStart.toInstant().toEpochMilli())
            val end = minOf(now, localEnd.toInstant().toEpochMilli()).coerceAtLeast(start)
            val items = if (start > end) emptyList() else selected.filter { event -> slices.getValue(event).any {
                it.start >= start && it.start < end || it.end > start && it.start < end ||
                    it.start == end && end == now && now < localEnd.toInstant().toEpochMilli() && it.start >= start
            } }.map { candidates.getValue(it) }.distinctBy { it.record.packageName + "|" +
                (it.record.workKey ?: "record:${it.record.id}:${it.record.timestamp}") }
            val recognized = items.filter { it.label != null && it.label != "待归纳" }
            val repeated = recognized.groupBy { it.label }.values.sumOf { (it.size - 1).coerceAtLeast(0) }
            val emotional = recognized.count { it.emotional }
            val score = if (recognized.size < 3) null else
                ((60.0 * repeated + 40.0 * emotional) / recognized.size).toInt().coerceIn(0, 100)
            val caption = if (period == StatisticsPeriod.WEEK) {
                if (index == 6) "今天" else "${localStart.monthValue}/${localStart.dayOfMonth}"
            } else localStart.hour.toString().padStart(2, '0')
            ViewpointBucket(caption, start, end, items, score, repeated, emotional)
        }
    }
}
