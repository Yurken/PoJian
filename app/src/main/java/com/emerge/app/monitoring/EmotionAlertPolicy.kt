package com.emerge.app.monitoring

import com.emerge.app.analysis.ContentEmotion
import com.emerge.app.analysis.BrowseWorkKey
import com.emerge.app.analysis.EmotionCategory
import com.emerge.app.analysis.EmotionEvidence
import com.emerge.app.analysis.EmotionExposureSummary
import com.emerge.app.analysis.ImpactLevel
import com.emerge.app.data.BrowseEvent

data class EmotionAlertSignal(val topic: String, val category: EmotionCategory, val known: Int,
    val high: Int, val unknown: Int, val workCount: Int, val eventIds: List<Long>,
    val evidence: List<EmotionEvidence>, val earliest: Long, val latest: Long) {
    fun matchesCurrent(eventId: Long, emotion: ContentEmotion, currentTopic: String = topic): Boolean =
        currentTopic.trim() == topic && emotion.category == category && emotion.level == ImpactLevel.HIGH &&
            evidence.any { it.eventId == eventId && it.category == category && it.level == ImpactLevel.HIGH &&
                it.evidence == emotion.evidence }
}

/** Uncalibrated content-exposure gates; no inference about the viewer's emotional condition. */
object EmotionAlertPolicy {
    private val negative = setOf(EmotionCategory.ANXIETY, EmotionCategory.ANGER, EmotionCategory.FEAR, EmotionCategory.SADNESS)
    private data class Work(val identity: String, val topics: Set<String>, val evidence: List<EmotionEvidence>)

    fun assess(summary: EmotionExposureSummary, events: List<BrowseEvent>): List<EmotionAlertSignal> {
        val byId = events.associateBy { it.id }
        val parents = events.filter { it.sourceType == "accessibility" && it.visibleText.isNotBlank() &&
            !it.packageName.contains("launcher") && it.packageName != "com.android.systemui" }.associateBy { it.id }
        fun identity(event: BrowseEvent): String? {
            if (event.id in parents) return BrowseWorkKey.of(event)
            if (event.sourceType !in setOf("screen_ocr", "visual_model", "audio_playback")) return null
            val parent = parents[event.parentEventId] ?: return null
            if (parent.packageName != event.packageName || parent.workKey != event.workKey) return null
            return BrowseWorkKey.of(parent)
        }
        val works = summary.works.groupBy { it.workIdentity }.mapNotNull { (key, rows) ->
            val retained = rows.filter { row -> row.eventIds.any { id -> byId[id]?.let(::identity) == key } }
            if (retained.isEmpty()) return@mapNotNull null
            val topics = retained.flatMap { it.topics }.map(String::trim).filter(String::isNotEmpty).toSet()
            val evidence = retained.flatMap { row -> row.emotions.mapNotNull emotion@ { item ->
                val event = byId[item.eventId] ?: return@emotion null
                if (item.eventId !in row.eventIds || identity(event) != key ||
                    !ContentEmotion(item.category, item.level, item.evidence, item.trigger).validFor(event.visibleText))
                    return@emotion null
                val topic = item.topic.trim().ifEmpty { row.topics.singleOrNull()?.trim().orEmpty() }
                if (topic.isBlank() || topic !in row.topics.map(String::trim)) return@emotion null
                item.copy(topic = topic)
            } }.distinct()
            Work(key, topics, evidence)
        }
        return works.flatMap { it.topics }.distinct().sorted().flatMap { topic ->
            val relevant = works.filter { topic in it.topics }
            val known = relevant.filter { work -> work.evidence.any { it.topic == topic && it.level != ImpactLevel.UNKNOWN } }
            if (known.size < 8 || known.size.toDouble() / relevant.size < .6) return@flatMap emptyList()
            negative.mapNotNull { category ->
                val highWorks = known.mapNotNull { work ->
                    work.evidence.filter { it.topic == topic && it.category == category && it.level == ImpactLevel.HIGH }
                        .takeIf { it.isNotEmpty() }
                }
                if (highWorks.size.toDouble() / known.size < .6) return@mapNotNull null
                // Reopening an old work does not turn a short burst into sustained exposure.
                val firstSeen = highWorks.map { references -> references.minOf { byId.getValue(it.eventId).timestamp } }
                val earliest = firstSeen.minOrNull() ?: return@mapNotNull null
                val latest = firstSeen.maxOrNull() ?: return@mapNotNull null
                if (latest - earliest < 30 * 60_000L) return@mapNotNull null
                val sources = highWorks.flatten().distinct().sortedWith(compareBy<EmotionEvidence> {
                    byId.getValue(it.eventId).timestamp }.thenBy { it.eventId })
                EmotionAlertSignal(topic, category, known.size, highWorks.size, relevant.size - known.size,
                    relevant.size, sources.map { it.eventId }.distinct(), sources, earliest, latest)
            }
        }
    }
}
