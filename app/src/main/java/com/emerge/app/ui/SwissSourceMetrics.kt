package com.emerge.app.ui

import com.emerge.app.analysis.*

internal data class TopicSourceRecord(val work: String, val topic: String, val subject: String, val source: SourceRecord)

/** Read all retained visits/materials, not just the representative claim of each work. */
internal fun sourceRecords(works: List<ViewpointItem>, semantics: List<EventSemantics>): List<TopicSourceRecord> {
    val latest = mutableMapOf<Long, EventSemantics>()
    semantics.forEach { result ->
        if (latest[result.eventId]?.let { it.analyzedAt > result.analyzedAt } != true) latest[result.eventId] = result
    }
    return works.flatMap { work ->
        work.records.distinctBy { it.id }.flatMap { event ->
            val result = latest[event.id]?.takeIf { it.validFor(event) }
            if (result == null) listOf(TopicSourceRecord(work.workIdentity, "", "", SourceRecord(event.id, "", null)))
            else result.claims.map { claim -> TopicSourceRecord(work.workIdentity, claim.topic, claim.subject,
                SourceRecord(event.id, claim.evidence, claim.sourceProvenance.takeUnless { claim.uncertain })) }
        }
    }
}

internal fun sourceMetric(rows: List<TopicSourceRecord>, selected: List<ViewpointItem>, topic: String?): EvidenceMetric {
    val works = selected.map { it.workIdentity }.toSet()
    return independentSourceMetric(rows.filter { it.work in works &&
        (topic == null || it.topic.isBlank() || it.topic == topic || it.subject == topic) }.map { it.source })
}
