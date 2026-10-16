package com.emerge.app.monitoring

import com.emerge.app.analysis.ClaimObservation
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.ContentEmotion
import com.emerge.app.analysis.Increment
import com.emerge.app.analysis.SemanticFieldKeys
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Rebuildable historical findings; only a freshly matching retained record receives a screen token. */
object DerivedMonitorFindings {
    fun build(report: CognitiveReport, events: List<BrowseEvent>, config: MonitorConfig,
        currentPackage: String, screenText: String, contextToken: String): List<MonitorFinding> {
        val byId = events.associateBy { it.id }
        val zone = runCatching { ZoneId.of(report.zone) }.getOrDefault(ZoneId.systemDefault())
        fun retained(eventId: Long, text: String) = text.isNotBlank() && byId[eventId]?.visibleText?.contains(text) == true
        fun field(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fun sameScope(a: ClaimObservation, b: ClaimObservation) = field(a.topic) == field(b.topic) &&
            field(a.subject) == field(b.subject) && field(a.dimension) == field(b.dimension) &&
            SemanticFieldKeys.conditions(a.conditions, a.timestamp, zone) == SemanticFieldKeys.conditions(b.conditions, b.timestamp, zone) &&
            SemanticFieldKeys.stance(a.stance, a.subject, a.dimension) == SemanticFieldKeys.stance(b.stance, b.subject, b.dimension)
        fun onCurrentScreen(eventId: Long, quotes: List<String>): Boolean {
            val source = byId[eventId] ?: return false
            if (source.packageName != currentPackage || quotes.isEmpty() || quotes.any { it.isBlank() || !screenText.contains(it) }) return false
            // A newer unassessed/LOW record with the same words is not the old HIGH/repeated record.
            val latest = events.filter { event -> event.packageName == currentPackage && event.timestamp <= report.generatedAt &&
                quotes.all(event.visibleText::contains) }.maxWithOrNull(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id })
            return latest?.id == eventId
        }
        fun recordIds(ids: List<Long>) = ids.distinct().joinToString("、") { "#$it" }
        val findings = mutableListOf<MonitorFinding>()
        if (config.cocoon) report.exposure?.signals.orEmpty().forEach { signal ->
            val assessment = signal.longitudinal
            // A stale derived report cannot preserve deleted or corrected quotations.
            if (assessment.evidence.any { reference -> !retained(reference.eventId, reference.quote) ||
                    reference.historyMatches.any { !retained(it.eventId, it.quote) } }) return@forEach
            val candidates = report.claims.filter { claim ->
                claim.increment == Increment.REPEATED && !claim.uncertainAttribution && !claim.historyComparisonTruncated &&
                    sameScope(claim, signal.latest) && retained(claim.eventId, claim.quote) && claim.evidenceDetails.isNotEmpty() &&
                    claim.evidenceDetails.all { detail -> !detail.isNew && !detail.identityUnresolved &&
                        assessment.evidence.any { reference -> reference.eventId == claim.eventId && reference.quote == detail.text &&
                            reference.historyMatches.isNotEmpty() } }
            }
            val latest = candidates.maxWithOrNull(compareBy<ClaimObservation> { it.timestamp }.thenBy { it.eventId }) ?: return@forEach
            val source = byId[latest.eventId] ?: return@forEach
            val token = contextToken.takeIf { signal.latest.eventId == latest.eventId && signal.latest.increment == Increment.REPEATED &&
                onCurrentScreen(latest.eventId, listOf(latest.quote) + latest.evidenceDetails.map { it.text }) }.orEmpty()
            val percent = String.format(Locale.CHINA, "%.1f", signal.share * 100)
            findings += MonitorFinding("exposure:" + MonitorPolicy.hash(signal.key), "event:${latest.eventId}",
                source.packageName, source.timestamp, "cocoon", "concentration", latest.quote,
                "${assessment.days}天；同向观点 $percent%；已知论据 ${assessment.known}/${assessment.contacts}；" +
                    "重复 ${assessment.repeated}/${assessment.known}；记录 ${recordIds(assessment.eventIds)}",
                topic = "${signal.topic} · ${signal.subject} · ${signal.dimension}", contextToken = token)
        }
        if (config.emotion) EmotionAlertPolicy.assess(report.emotionExposure, events).forEach { signal ->
            val latest = signal.evidence.maxWithOrNull(compareBy<com.emerge.app.analysis.EmotionEvidence> {
                byId.getValue(it.eventId).timestamp }.thenBy { it.eventId }) ?: return@forEach
            val source = byId[latest.eventId] ?: return@forEach
            val token = contextToken.takeIf { signal.matchesCurrent(latest.eventId,
                ContentEmotion(latest.category, latest.level, latest.evidence, latest.trigger), latest.topic) &&
                onCurrentScreen(latest.eventId, listOf(latest.evidence)) }.orEmpty()
            val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(zone)
            val interval = "${formatter.format(Instant.ofEpochMilli(signal.earliest))}–${formatter.format(Instant.ofEpochMilli(signal.latest))}"
            findings += MonitorFinding("emotion:" + MonitorPolicy.hash("${signal.topic}|${signal.category.wire}"),
                "event:${latest.eventId}", source.packageName, source.timestamp, "emotion", "emotional_exposure", latest.evidence,
                "已知 ${signal.known}；未知 ${signal.unknown}；强烈 ${signal.high}；$interval；记录 ${recordIds(signal.eventIds)}",
                topic = "${signal.topic} · ${signal.category.label}", contextToken = token)
        }
        return findings
    }
}
