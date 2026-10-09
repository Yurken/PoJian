package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ViewpointItem(val record: BrowseEvent, val label: String?, val quote: String?, val emotional: Boolean)
data class ViewpointBucket(val label: String, val start: Long, val end: Long,
    val items: List<ViewpointItem>, val score: Int?, val repeated: Int, val emotional: Int)

/** Local, auditable exposure signals; never a measurement of the user's mental state. */
object ViewpointTimeline {
    private val emotion = Regex("焦虑|恐慌|恐惧|绝望|完蛋|没有未来|毁掉")
    private val negatedEmotion = Regex("(?:不必|不用|无需|别|不要)[^，。；]{0,6}(?:焦虑|恐慌|恐惧|绝望)")
    private val predicate = Regex("应该|必须|需要|不能|独立|焦虑|认为|觉得|导致|越来越|意味着|会|难|值得|自由")
    fun label(quote: String): String = when {
        Regex("(?:不|无需|别)[^，。；]{0,8}(?:焦虑|独立)").containsMatchIn(quote) -> quote.trim().take(32)
        Regex("就业|求职|招聘|裁员|找工作").containsMatchIn(quote) && Regex("焦虑|困难|很难|难找|失业").containsMatchIn(quote) -> "就业焦虑"
        Regex("女性|女人|女生").containsMatchIn(quote) && Regex("独立|自主|不依赖").containsMatchIn(quote) -> "女性独立"
        else -> quote.trim().take(32).let { if (quote.trim().length > 32) "$it…" else it }
    }

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
            val quote = observations[event.id]?.firstOrNull()?.quote ?: event.visibleText.lines().map(String::trim)
                .firstOrNull { it.length in 10..300 && predicate.containsMatchIn(it) &&
                    !Regex("按钮|来自|回复|评论|展开|图片[,，]").containsMatchIn(it) }
            ViewpointItem(event, quote?.let(::label), quote, quote?.let {
                emotion.containsMatchIn(it) && !negatedEmotion.containsMatchIn(it)
            } == true)
        }
        return List(count) { index ->
            val localStart = if (period == StatisticsPeriod.WEEK) first.plusDays(index.toLong()) else first.plusHours(index.toLong())
            val localEnd = if (period == StatisticsPeriod.WEEK) localStart.plusDays(1) else localStart.plusHours(1)
            val start = maxOf(window.start, localStart.toInstant().toEpochMilli())
            val end = minOf(now, localEnd.toInstant().toEpochMilli()).coerceAtLeast(start)
            val items = if (start > end) emptyList() else selected.filter { event -> window.slices(event).any {
                it.start >= start && it.start < end || it.end > start && it.start < end ||
                    it.start == end && end == now && now < localEnd.toInstant().toEpochMilli() && it.start >= start
            } }.map { candidates.getValue(it) }.distinctBy { it.record.packageName + "|" +
                (it.record.workKey ?: "record:${it.record.id}:${it.record.timestamp}") }
            val recognized = items.filter { it.label != null }
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
