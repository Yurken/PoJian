package com.emerge.app.monitoring

import com.emerge.app.analysis.ContentEmotion
import com.emerge.app.analysis.BrowseWorkKey
import com.emerge.app.analysis.EmotionCategory
import com.emerge.app.analysis.EmotionEvidence
import com.emerge.app.analysis.EmotionExposureSummary
import com.emerge.app.analysis.ImpactLevel
import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.analysis.SessionSlice
import com.emerge.app.data.BrowseEvent
import java.time.ZoneOffset

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
    private data class Work(val identity: String, val topics: Set<String>, val evidence: List<EmotionEvidence>, val parents: Set<Long>)
    private data class Segment(val parent: Long, val work: Work, val slice: SessionSlice)
    // Two-second sampling plus debounce: bridge short transitions, but never count the gaps as exposure.
    private const val SAMPLING_GAP_MILLIS = 3000L

    fun assess(summary: EmotionExposureSummary, events: List<BrowseEvent>, start: Long = 0,
        end: Long = Long.MAX_VALUE): List<EmotionAlertSignal> {
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
        fun parentId(id: Long): Long? = byId[id]?.let { if (it.id in parents) it.id else it.parentEventId }
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
            Work(key, topics, evidence, retained.flatMap { it.eventIds }.filter { byId[it]?.let(::identity) == key }
                .mapNotNull(::parentId).toSet())
        }
        val window = ObservationWindow(start, end, ZoneOffset.UTC)
        val workByParent = works.flatMap { work -> work.parents.map { it to work } }.toMap()
        val segments = parents.values.flatMap { event ->
            val work = workByParent[event.id] ?: return@flatMap emptyList()
            // Legacy aggregate duration cannot locate pauses. Clock anomalies cannot prove continuity.
            if (event.visibilityIntervals == null || !window.durationKnown(event)) return@flatMap emptyList()
            window.slices(event).filter { it.durationMillis > 0 && it.durationMillis == it.end - it.start }
                .map { Segment(event.id, work, it) }
        }.sortedBy { it.slice.start }
        return works.flatMap { it.topics }.distinct().sorted().flatMap { topic ->
            val episodes = mutableListOf<MutableList<Segment>>()
            var lastEnd = 0L
            segments.filter { topic in it.work.topics }.forEach { segment ->
                if (episodes.isEmpty() || segment.slice.start - lastEnd > SAMPLING_GAP_MILLIS) {
                    episodes += mutableListOf(segment)
                    lastEnd = segment.slice.end
                } else {
                    episodes.last() += segment
                    lastEnd = maxOf(lastEnd, segment.slice.end)
                }
            }
            negative.mapNotNull { category ->
                episodes.mapNotNull episode@ { episode ->
                    val ids = episode.map { it.parent }.toSet()
                    val relevant = episode.map { it.work }.distinctBy { it.identity }.map { work ->
                        work.copy(evidence = work.evidence.filter { parentId(it.eventId) in ids && it.topic == topic }) }
                    val known = relevant.filter { work -> work.evidence.any { it.level != ImpactLevel.UNKNOWN } }
                    if (known.size < 8 || known.size.toDouble() / relevant.size < .6) return@episode null
                    val highWorks = known.mapNotNull { work -> work.evidence.filter {
                        it.category == category && it.level == ImpactLevel.HIGH }.takeIf { it.isNotEmpty() } }
                    if (highWorks.size.toDouble() / known.size < .6) return@episode null
                    val sources = highWorks.flatten().distinct().sortedWith(compareBy<EmotionEvidence> {
                        byId.getValue(it.eventId).timestamp }.thenBy { it.eventId })
                    val highIds = sources.mapNotNull { parentId(it.eventId) }.toSet()
                    val highDuration = window.unionSlices(episode.filter { it.parent in highIds }.map { it.slice })
                    val totalDuration = window.unionSlices(episode.map { it.slice })
                    if (highDuration < 30 * 60_000L || highDuration.toDouble() / totalDuration < .6) return@episode null
                    EmotionAlertSignal(topic, category, known.size, highWorks.size, relevant.size - known.size,
                        relevant.size, sources.map { it.eventId }.distinct(), sources,
                        episode.minOf { it.slice.start }, episode.maxOf { it.slice.end })
                }.maxByOrNull { it.latest }
            }
        }
    }
}
