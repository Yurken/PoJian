package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId

data class EmotionEvidence(val eventId: Long, val category: EmotionCategory, val level: ImpactLevel,
    val evidence: String, val trigger: String? = null, val topic: String = "")
data class EmotionWorkExposure(val workIdentity: String, val eventIds: List<Long>,
    val dates: List<String>, val topics: List<String>, val emotions: List<EmotionEvidence>)
data class EmotionCategoryCount(val category: EmotionCategory, val works: Int, val eventIds: List<Long>)
data class EmotionTopicExposure(val topic: String, val knownWorks: Int, val unknownWorks: Int,
    val counts: List<EmotionCategoryCount>)
data class EmotionDateExposure(val date: String, val knownWorks: Int, val unknownWorks: Int,
    val counts: List<EmotionCategoryCount>)
data class EmotionExposureSummary(val knownWorks: Int = 0, val unknownWorks: Int = 0,
    val counts: List<EmotionCategoryCount> = emptyList(), val byTopic: List<EmotionTopicExposure> = emptyList(),
    val byDate: List<EmotionDateExposure> = emptyList(), val works: List<EmotionWorkExposure> = emptyList()) {
    val totalWorks get() = knownWorks + unknownWorks
}

object EmotionExposure {
    private data class Material(val workIdentity: String, val eventId: Long, val dates: List<String>,
        val topic: String?, val emotions: List<EmotionEvidence>)

    fun analyze(events: List<BrowseEvent>, semantics: List<EventSemantics>, start: Long, end: Long,
        zone: ZoneId = ZoneId.systemDefault()): EmotionExposureSummary {
        if (end < start) return EmotionExposureSummary()
        val window = ObservationWindow(start, end, zone)
        // A frame or transcript can describe a retained viewing; it cannot create a new viewing.
        val primary = events.filter { it.sourceType == "accessibility" && window.contains(it) &&
            !it.packageName.contains("launcher") && it.packageName != "com.android.systemui" }
        val parents = primary.associateBy { it.id }
        val keys = primary.zip(BrowseWorkKey.resolve(primary)).associate { it.first.id to it.second }
        val latest = semantics.groupBy { it.eventId }.mapValues { (_, versions) ->
            versions.withIndex().maxWithOrNull(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }
                .thenBy { it.index })!!.value
        }
        val selected = primary + events.filter { it.sourceType in setOf("screen_ocr", "visual_model", "audio_playback") &&
            window.contains(it) && parents[it.parentEventId]?.let { parent ->
                parent.packageName == it.packageName && parent.workKey == it.workKey
            } == true }
        val material = selected.flatMap { event ->
            val key = keys[if (event.sourceType == "accessibility") event.id else event.parentEventId] ?: return@flatMap emptyList()
            val dates = window.slices(event).map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString() }.distinct()
            val valid = latest[event.id]?.takeIf { it.validFor(event) }
            valid?.claims?.takeIf { it.isNotEmpty() }?.map { claim ->
                Material(key, event.id, dates, claim.topic.trim().takeIf { it.isNotEmpty() },
                    claim.presentation?.emotions.orEmpty().map {
                        EmotionEvidence(event.id, it.category, it.level, it.evidence, it.trigger, claim.topic.trim())
                    })
            } ?: listOf(Material(key, event.id, dates, null, emptyList()))
        }
        val summaryWorks = works(material)
        val unclassifiedKeys = summaryWorks.filter { it.topics == listOf("未分类") }.map { it.workIdentity }.toSet()
        val topics = summaryWorks.flatMap { it.topics }.distinct().sorted().map { topic ->
            // Unclassified parents do not manufacture another topic beside their classified OCR/audio.
            val subset = material.filter { it.topic == topic || topic == "未分类" && it.topic == null &&
                it.workIdentity in unclassifiedKeys }
            val topicWorks = works(subset)
            EmotionTopicExposure(topic, topicWorks.count { it.emotions.isNotEmpty() },
                topicWorks.count { it.emotions.isEmpty() }, counts(topicWorks))
        }
        val dates = material.flatMap { it.dates }.distinct().sorted().map { date ->
            // Project the material before counting: later evidence cannot reclassify an earlier unknown day.
            val dateWorks = works(material.filter { date in it.dates })
            EmotionDateExposure(date, dateWorks.count { it.emotions.isNotEmpty() },
                dateWorks.count { it.emotions.isEmpty() }, counts(dateWorks))
        }
        return EmotionExposureSummary(summaryWorks.count { it.emotions.isNotEmpty() }, summaryWorks.count { it.emotions.isEmpty() },
            counts(summaryWorks), topics, dates, summaryWorks)
    }

    private fun works(material: List<Material>): List<EmotionWorkExposure> = material.groupBy { it.workIdentity }
        .map { (key, rows) ->
            val emotions = rows.flatMap { it.emotions }.distinct()
            val hasEmotion = emotions.any { it.category != EmotionCategory.NEUTRAL }
            EmotionWorkExposure(key, rows.map { it.eventId }.distinct().sorted(), rows.flatMap { it.dates }.distinct().sorted(),
                rows.mapNotNull { it.topic }.distinct().sorted().ifEmpty { listOf("未分类") },
                emotions.filter { !hasEmotion || it.category != EmotionCategory.NEUTRAL })
        }.sortedBy { it.workIdentity }

    private fun counts(works: List<EmotionWorkExposure>): List<EmotionCategoryCount> = EmotionCategory.entries.mapNotNull { category ->
        val matching = works.filter { work -> work.emotions.any { it.category == category } }
        if (matching.isEmpty()) null else EmotionCategoryCount(category, matching.size,
            matching.flatMap { it.emotions }.filter { it.category == category }.map { it.eventId }.distinct().sorted())
    }
}
