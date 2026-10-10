package com.emerge.app.monitoring

import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent

/** Bind a result to the captured text, never to whichever screen is visible when it arrives. */
object MonitorInputs {
    fun observationTime(event: BrowseEvent) = maxOf(event.timestamp, event.endTimestamp)

    fun isAppend(old: BrowseEvent, updated: BrowseEvent): Boolean =
        old.sourceType == "accessibility" && updated.sourceType == old.sourceType &&
            old.id == updated.id && old.timestamp == updated.timestamp && old.packageName == updated.packageName &&
            old.workKey == updated.workKey && updated.sampleCount >= old.sampleCount &&
            updated.endTimestamp >= old.endTimestamp &&
            old.visibleText.lines().all { it in updated.visibleText.lines() }

    fun screenSnapshot(events: List<BrowseEvent>, packageName: String, text: String, at: Long): BrowseEvent? {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        if (lines.isEmpty()) return null
        return events.asSequence().filter {
            it.sourceType == "accessibility" && it.packageName == packageName && it.timestamp <= at &&
                at - observationTime(it) in 0..6000 && lines.all { line -> line in it.visibleText }
        }.maxWithOrNull(compareBy<BrowseEvent> { observationTime(it) }.thenBy { it.id })
    }

    /** Context can contain multiple audio segments; a quote and its evidence must belong to one segment. */
    fun allocate(snapshots: List<BrowseEvent>, claims: List<ArgumentUnit>, analyzedAt: Long,
        kind: String, sampledText: String = snapshots.joinToString("\n") { it.visibleText }): List<EventSemantics> {
        val assigned = snapshots.associate { it.id to mutableListOf<ArgumentUnit>() }
        val ordered = snapshots.sortedWith(compareBy<BrowseEvent> { if (it.sourceType == "visual_model") 1 else 0 }
            .thenByDescending { it.timestamp }.thenByDescending { it.id })
        claims.forEach { claim ->
            val owner = ordered.firstOrNull { event ->
                EventSemantics(event.id, event.visibleText, analyzedAt, listOf(claim), kind).validFor(event)
            }
            if (owner != null) assigned.getValue(owner.id).add(claim)
        }
        return snapshots.distinctBy { it.id }.mapNotNull { event ->
            val units = assigned[event.id].orEmpty()
            // A zero-claim sample cannot retract opinions in text that was never sent.
            if (claims.isEmpty() && event.visibleText !in sampledText || claims.isNotEmpty() && units.isEmpty()) null
            else EventSemantics(event.id, event.visibleText, analyzedAt, units.distinct(), kind)
                .takeIf { it.validFor(event) }
        }
    }
}
