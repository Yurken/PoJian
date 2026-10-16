package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.ZoneId

internal data class SwissEvidenceSource(val text: String, val eventIds: List<Long>,
    val historyMatches: List<EvidenceHistoryMatch> = emptyList(), val label: String = "")

internal data class SwissViewpointDetails(val workCount: Int, val sourceIds: List<Long>,
    val evidence: List<SwissEvidenceSource>)

internal fun swissViewpointDetails(group: ViewpointShare, events: List<BrowseEvent>,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault(), report: CognitiveReport? = null): SwissViewpointDetails {
    val byId = events.associateBy { it.id }
    val latest = semantics.groupBy { it.eventId }.mapValues { (_, versions) ->
        versions.withIndex().maxWith(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }.thenBy { it.index }).value
    }
    val parents = events.filter { it.sourceType == "accessibility" }
    val workKeys = parents.zip(BrowseWorkKey.resolve(parents)).associate { it.first.id to it.second }
    val ids = group.items.flatMap { it.records }.map { it.id }.toSet()
    val works = linkedSetOf<String>()
    val sources = linkedSetOf<Long>()
    val evidence = mutableListOf<SwissEvidenceSource>()
    val reportClaims = report?.claims.orEmpty().groupBy { it.eventId }
    events.filter { it.id in ids }.sortedWith(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id }).forEach { event ->
        val parent = if (event.sourceType == "accessibility") event else byId[event.parentEventId]?.takeIf {
            event.sourceType in setOf("screen_ocr", "visual_model", "audio_playback") &&
                it.sourceType == "accessibility" && it.packageName == event.packageName && it.workKey == event.workKey
        } ?: return@forEach
        val work = workKeys[parent.id]?.takeIf { key -> group.items.any { it.workIdentity == key } } ?: return@forEach
        val retained = reportClaims[event.id].orEmpty().filter { it.packageName == event.packageName &&
            it.timestamp == event.timestamp && it.quote.isNotBlank() && event.visibleText.contains(it.quote) }
        val model = latest[event.id]?.takeIf { it.validFor(event) }
        val claims = if (model != null) model.claims.map {
            ViewpointClaim(it.quote, it.topic, it.subject, it.dimension, it.stance, it.conditions, it.uncertain,
                "structured_model", event, zone.id, it.evidence, it.presentation, model.inputKind, model.analyzedAt)
        } else if (event.sourceType == "accessibility") ViewpointTimeline.localClaims(event, retained, zone) else emptyList()
        claims.filter { it.key == group.key }.forEach { claim ->
            sources += event.id
            works += work
            if (claim.evidence.isNotBlank() && event.visibleText.contains(claim.evidence))
                evidence += SwissEvidenceSource(claim.evidence, listOf(event.id))
            retained.filter { it.quote == claim.quote && it.toViewpointClaim(event, zone).key == claim.key }
                .flatMap { it.evidenceDetails }.filter { it.text.isNotBlank() && event.visibleText.contains(it.text) &&
                    (claim.analysisSource == "local_rules" || claim.evidence.contains(it.text)) }
                .forEach { fragment ->
                    evidence += SwissEvidenceSource(fragment.text, listOf(event.id), fragment.historyMatches.filter { match ->
                        byId[match.eventId]?.let { it.timestamp < event.timestamp && it.visibleText.contains(match.quote) } == true
                    })
                }
        }
    }
    return SwissViewpointDetails(works.size, sources.toList(), evidence.groupBy { it.text }.map { (text, fragments) ->
        SwissEvidenceSource(text, fragments.flatMap { it.eventIds }.distinct(), fragments.flatMap { it.historyMatches }.distinct())
    })
}

private fun ClaimObservation.toViewpointClaim(event: BrowseEvent, zone: ZoneId) = ViewpointClaim(quote, topic, subject,
    dimension, stance, conditions, uncertainAttribution, analysisSource, event, zone.id)

internal fun swissSourceEvent(id: Long, events: List<BrowseEvent>): BrowseEvent? {
    val event = events.firstOrNull { it.id == id } ?: return null
    return events.firstOrNull { it.id == event.parentEventId && it.sourceType == "accessibility" &&
        it.packageName == event.packageName && it.workKey == event.workKey } ?: event
}
