package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId

data class ContentWord(val text: String, val recordCount: Int)

/** Aggregate model-authored concepts; raw text is only used to validate the input snapshot. */
fun recentContentWords(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault()): List<ContentWord> {
    val counts = mutableMapOf<String, Int>()
    recentConceptRecords(events, period, now, semantics, zone).forEach { (_, concepts) ->
        concepts.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts.map { ContentWord(it.key, it.value) }
        .sortedWith(compareByDescending<ContentWord> { it.recordCount }.thenBy { it.text }).take(24)
}

/** Trace model concepts using the same input snapshot and time window as the cloud counts. */
fun recentContentWordRecords(word: String, events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault()): List<BrowseEvent> =
    recentConceptRecords(events, period, now, semantics, zone)
        .filter { (_, concepts) -> word in concepts }.map { it.first }.sortedByDescending { it.timestamp }

private fun recentConceptRecords(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId): List<Pair<BrowseEvent, List<String>>> {
    val window = ObservationWindow.forPeriod(period, now, zone)
    val latest = semantics.withIndex().groupBy { it.value.eventId }.mapValues { (_, versions) ->
        versions.maxWith(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }
            .thenBy { it.index }).value
    }
    return events.asSequence().filter(window::contains).distinctBy { it.id }.mapNotNull { event ->
        val result = latest[event.id]?.takeIf { it.validFor(event) } ?: return@mapNotNull null
        val concepts = result.claims.asSequence().flatMap { sequenceOf(it.topic, it.subject) }
            .map { it.trim() }.filter { it.isNotEmpty() && it !in missingConcepts }
            .distinct().toList()
        event to concepts
    }.toList()
}

private val missingConcepts = setOf("未知", "未分类", "未识别", "不明确", "无")
