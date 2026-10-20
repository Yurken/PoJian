package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText
import com.emerge.app.ui.StatisticsPeriod
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** These categories describe retained content, not a measurement of the viewer's feelings. */
enum class EmotionTone(val label: String) {
    NEGATIVE("负面情绪"), OBJECTIVE("客观内容"), NEUTRAL("无明显情绪"),
    POSITIVE("正面情绪"), UNKNOWN("待分析")
}

data class EmotionPost(
    val workIdentity: String,
    val event: BrowseEvent,
    val topic: String,
    val tone: EmotionTone,
    val summary: String,
    val emotions: List<EmotionEvidence>,
    val evidence: List<String>
)

data class EmotionHour(
    val start: Long,
    val end: Long,
    val durations: Map<EmotionTone, Long>,
    val posts: List<EmotionPost>,
    val unknownDurationWorks: Int
) {
    val durationMillis get() = durations.values.sum()
    val workCount get() = posts.map { it.workIdentity }.distinct().size
}

object EmotionTimeline {
    private val negative = setOf(EmotionCategory.ANXIETY, EmotionCategory.ANGER,
        EmotionCategory.FEAR, EmotionCategory.SADNESS)
    private data class Material(val event: BrowseEvent, val claim: ArgumentUnit)
    private data class TimedTone(val slice: SessionSlice, val tone: EmotionTone, val id: Long)

    fun analyze(events: List<BrowseEvent>, semantics: List<EventSemantics>, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), period: StatisticsPeriod = StatisticsPeriod.HOURS24): List<EmotionHour> {
        val window = ObservationWindow.forPeriod(period, now, zone)
        val start = window.start
        val primary = events.filter { it.sourceType == "accessibility" && window.contains(it) &&
            !it.packageName.contains("launcher") && it.packageName != "com.android.systemui" }
        val latest = semantics.withIndex().groupBy { it.value.eventId }.mapValues { (_, versions) ->
            versions.maxWith(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }.thenBy { it.index }).value
        }
        val children = events.filter { it.sourceType in setOf("screen_ocr", "visual_model", "audio_playback") }
            .groupBy { it.parentEventId }
        val material = primary.associate { parent ->
            parent.id to (listOf(parent) + children[parent.id].orEmpty().filter {
                it.packageName == parent.packageName && it.workKey == parent.workKey
            }).flatMap { event -> latest[event.id]?.takeIf { it.validFor(event) }?.claims.orEmpty()
                .map { Material(event, it) } }
        }
        val slices = primary.associate { it.id to window.slices(it) }
        val keys = primary.zip(BrowseWorkKey.resolve(primary)).associate { it.first.id to it.second }
        val result = mutableListOf<EmotionHour>()
        var cursor = start
        // Include today's zero-length bucket at midnight, consistently with the other daily charts.
        while (cursor < now || period.dailyBuckets && cursor == now) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextBoundary = if (period.dailyBuckets) local.toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            else local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val end = minOf(nextBoundary, now)
            val hour = ObservationWindow(cursor, end, zone)
            fun belongs(slice: SessionSlice) = if (slice.end > slice.start) slice.start < end && slice.end > cursor
                else slice.start >= cursor && (slice.start < end || end == now && slice.start == end &&
                    (!period.dailyBuckets || nextBoundary > now || cursor == now))
            val visits = primary.filter { slices.getValue(it.id).any(::belongs) }
            val available = visits.associate { parent -> parent.id to material.getValue(parent.id).filter {
                it.event.id == parent.id || hour.slices(it.event).any(::belongs)
            } }
            val timed = visits.flatMap { parent ->
                val tone = tone(available.getValue(parent.id).map { it.claim })
                hour.slices(parent).map { TimedTone(it, tone, parent.id) }
            }
            val posts = visits.groupBy { keys.getValue(it.id) }.flatMap { (key, rows) ->
                val all = rows.flatMap { available.getValue(it.id) }
                val topics = all.map { it.claim.topic.trim() }.filter(String::isNotEmpty).distinct().ifEmpty { listOf("未分类") }
                topics.map { topic ->
                    val matches = all.filter { it.claim.topic.trim() == topic || topic == "未分类" && it.claim.topic.isBlank() }
                    val claims = matches.map { it.claim }
                    val emotions = matches.flatMap { item -> item.claim.presentation?.emotions.orEmpty().map {
                        EmotionEvidence(item.event.id, it.category, it.level, it.evidence, it.trigger, topic)
                    } }.distinctBy { listOf(it.category, it.evidence, it.trigger) }
                    // Open the parent work so its retained OCR/audio evidence remains available in the sheet.
                    val representative = rows.first()
                    EmotionPost(key, representative, topic, tone(claims),
                        claims.mapNotNull { it.presentation?.viewpoint }.distinct().joinToString("；").ifBlank {
                            claims.firstOrNull()?.quote ?: representative.contentText.trim()
                        }.take(180), emotions, claims.map { it.evidence }.filter(String::isNotBlank).distinct())
                }
            }
            result += EmotionHour(cursor, end, durations(timed), posts,
                visits.filter { !window.durationKnown(it) }.map { keys.getValue(it.id) }.distinct().size)
            if (end == now) {
                // At midnight the preceding day excludes the point at now; it belongs to today's bucket.
                if (period.dailyBuckets && nextBoundary == now && cursor < now) cursor = now else break
            } else cursor = end
        }
        return result
    }

    private fun tone(claims: List<ArgumentUnit>): EmotionTone {
        val emotions = claims.flatMap { it.presentation?.emotions.orEmpty() }
        return when {
            emotions.any { it.category in negative } -> EmotionTone.NEGATIVE
            emotions.any { it.category == EmotionCategory.JOY } -> EmotionTone.POSITIVE
            claims.isEmpty() || claims.any { it.presentation?.emotions.isNullOrEmpty() } -> EmotionTone.UNKNOWN
            claims.all { !it.uncertain && it.evidence.isNotBlank() } -> EmotionTone.OBJECTIVE
            else -> EmotionTone.NEUTRAL
        }
    }

    /** Same interval union as ObservationWindow, with one owner per overlap. On equal density,
     * prefer the most recently started observation; source id breaks ties deterministically. */
    private fun durations(timed: List<TimedTone>): Map<EmotionTone, Long> {
        val valid = timed.filter { it.slice.end > it.slice.start && it.slice.durationMillis > 0 }
            .sortedBy { it.slice.start }
        val boundaries = valid.flatMap { listOf(it.slice.start, it.slice.end) }.distinct().sorted()
        val totals = mutableMapOf<EmotionTone, Long>()
        val priority = Comparator<TimedTone> { a, b ->
            val density = BigInteger.valueOf(a.slice.durationMillis).multiply(BigInteger.valueOf(b.slice.end - b.slice.start))
                .compareTo(BigInteger.valueOf(b.slice.durationMillis).multiply(BigInteger.valueOf(a.slice.end - a.slice.start)))
            if (density != 0) density else compareValuesBy(a, b, { it.slice.start }, { it.id })
        }
        val active = java.util.PriorityQueue(priority.reversed())
        var index = 0
        boundaries.zipWithNext().forEach { (start, end) ->
            while (index < valid.size && valid[index].slice.start <= start) active.add(valid[index++])
            while (active.peek()?.let { it.slice.end <= start } == true) active.poll()
            val owner = active.peek() ?: return@forEach
            val duration = BigInteger.valueOf(end - start).multiply(BigInteger.valueOf(owner.slice.durationMillis))
                .divide(BigInteger.valueOf(owner.slice.end - owner.slice.start)).toLong()
            totals[owner.tone] = (totals[owner.tone] ?: 0L) + duration
        }
        return totals
    }
}
