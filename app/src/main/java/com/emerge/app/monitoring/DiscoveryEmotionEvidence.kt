package com.emerge.app.monitoring

import com.emerge.app.analysis.EmotionEvidence
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent

/** Drill-down only: never reconstruct emotion labels or triggers from a reminder's prose. */
object DiscoveryEmotionEvidence {
    fun resolve(finding: MonitorFinding, events: List<BrowseEvent>, semantics: List<EventSemantics>): List<EmotionEvidence> {
        if (finding.kind != "emotion" || finding.claim.isBlank()) return emptyList()
        val current = semantics.groupBy { it.eventId }.mapValues { (_, versions) ->
            versions.withIndex().maxWithOrNull(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }
                .thenBy { it.index })!!.value
        }
        return events.asSequence().filter { it.packageName == finding.packageName && it.timestamp <= finding.capturedAt &&
            it.visibleText.contains(finding.claim) }.sortedByDescending { it.timestamp }.flatMap { event ->
            val valid = current[event.id]?.takeIf { it.validFor(event) } ?: return@flatMap emptySequence()
            valid.claims.asSequence().flatMap { claim -> claim.presentation?.emotions.orEmpty().asSequence()
                .filter { emotion -> emotion.evidence == finding.claim &&
                    "${claim.topic.trim()} · ${emotion.category.label}" == finding.topic }
                .map { emotion -> EmotionEvidence(event.id, emotion.category, emotion.level, emotion.evidence,
                    emotion.trigger, claim.topic.trim()) }
            }
        }.distinct().toList()
    }
}
