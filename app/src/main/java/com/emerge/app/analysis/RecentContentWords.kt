package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId

data class ContentWord(val text: String, val recordCount: Int)

/** Aggregate model-authored concepts; raw text is only used to validate the input snapshot. */
fun recentContentWords(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault()): List<ContentWord> {
    val window = ObservationWindow.forPeriod(period, now, zone)
    val latest = semantics.withIndex().groupBy { it.value.eventId }.mapValues { (_, versions) ->
        versions.maxWith(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }
            .thenBy { it.index }).value
    }
    val counts = mutableMapOf<String, Int>()
    events.asSequence().filter(window::contains).distinctBy { it.id }.forEach { event ->
        val result = latest[event.id]?.takeIf { it.validFor(event) } ?: return@forEach
        result.claims.asSequence().flatMap { sequenceOf(it.topic, it.subject) }
            .map { it.trim() }.filter { it.isNotEmpty() && it !in missingConcepts }
            .distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts.map { ContentWord(it.key, it.value) }
        .sortedWith(compareByDescending<ContentWord> { it.recordCount }.thenBy { it.text }).take(24)
}

private val missingConcepts = setOf("未知", "未分类", "未识别", "不明确", "无")
