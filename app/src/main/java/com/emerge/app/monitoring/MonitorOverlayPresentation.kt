package com.emerge.app.monitoring

import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.Increment
import com.emerge.app.data.BrowseEvent

/** A read-only display projection, never a new monitoring decision or stored analysis result. */
internal data class MonitorOverlayPresentation(
    val concentration: Float? = null,
    private val screens: Map<Pair<String, String>, MonitorOverlayDetails> = emptyMap()
) {
    fun current(packageName: String, screenText: String): MonitorOverlayDetails? =
        screens[packageName to screenText.trim()]
}

internal data class MonitorOverlayDetails(
    val topic: String?,
    val repeatedMaterials: Int?,
    val emotions: List<String>
)

/** Duration ticks do not change the concentration or current text shown by the overlay. */
internal fun sameOverlayDisplayEvents(previous: List<BrowseEvent>, current: List<BrowseEvent>): Boolean =
    previous.size == current.size && previous.indices.all { index ->
        val old = previous[index]
        val new = current[index]
        old.id == new.id && old.packageName == new.packageName && old.timestamp == new.timestamp &&
            old.eventType == new.eventType && old.visibleText == new.visibleText && old.sourceType == new.sourceType &&
            old.audioSessionId == new.audioSessionId && old.mediaStartMillis == new.mediaStartMillis &&
            old.mediaEndMillis == new.mediaEndMillis && old.workKey == new.workKey &&
            old.identityMethod == new.identityMethod && old.parentEventId == new.parentEventId
    }

internal fun monitorOverlayPresentation(report: CognitiveReport, events: List<BrowseEvent>): MonitorOverlayPresentation {
    val eventsById = events.associateBy { it.id }
    val claimsByEvent = report.claims.groupBy { it.eventId }
    val emotionsByEvent = report.emotionExposure.works.flatMap { it.emotions }.groupBy { it.eventId }
    val screens = events.asSequence()
        .filter { it.sourceType == "accessibility" && it.timestamp <= report.end &&
            it.endTimestamp.coerceAtLeast(it.timestamp) >= report.start && it.visibleText.isNotBlank() }
        .sortedWith(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id })
        .associate { event ->
            val claims = claimsByEvent[event.id].orEmpty()
            val fragments = claims.flatMap { it.evidenceDetails }
            val repeat = if (fragments.isEmpty() || claims.all { it.increment == Increment.UNKNOWN }) null
                else fragments.filter { fragment -> fragment.historyMatches.any { match ->
                    match.quote.isNotBlank() && eventsById[match.eventId]?.visibleText?.contains(match.quote) == true
                } }.distinctBy { it.text }.size
            (event.packageName to event.visibleText.trim()) to MonitorOverlayDetails(
                topic = claims.map { it.topic }.distinct().filter { it.isNotBlank() && it != "未分类" }.joinToString(" · ").ifBlank { null },
                repeatedMaterials = repeat,
                emotions = emotionsByEvent[event.id].orEmpty().map { it.category.label }.distinct()
            )
        }
    return MonitorOverlayPresentation(report.exposure?.primary?.share?.toFloat(), screens)
}
