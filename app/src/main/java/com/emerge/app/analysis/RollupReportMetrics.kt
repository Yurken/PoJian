package com.emerge.app.analysis

import com.emerge.app.data.DailyTopicRollupProjector
import com.emerge.app.ui.calculateSummaryStatistics

/** Valid empty analyses are measured zero; missing analyses remain unknown. */
fun CognitiveReport.reportNewFactMetric(): EvidenceMetric {
    dailyRollup?.takeIf { it.analyzedEvents > 0 &&
        it.semanticFingerprint == (semanticFingerprint ?: com.emerge.app.data.RollupSemanticFingerprint.of(semanticV2)) }?.let { rollup ->
        val summary = calculateSummaryStatistics(rollup)
        return EvidenceMetric(summary.newFacts, (usableSnapshots - rollup.analyzedEvents).coerceAtLeast(0),
            maxOf(usableSnapshots, rollup.analyzedEvents))
    }
    if (semanticV2.isNotEmpty()) {
        val times = claims.associate { it.eventId to it.timestamp } + semanticEventTimes
        val unknownNovelty = semanticV2.count { (id, value) -> id !in times && value.facts.any { !it.isCoveredByHistory } }
        val facts = semanticV2.flatMap { (eventId, result) -> result.facts.map { fact ->
            val enteredWindow = times[eventId]?.let { it in start..end } == true
            (fact.matchedHistoryId ?: DailyTopicRollupProjector.stableFactId(result.topicName, fact.factPoint)) to
                (enteredWindow && !fact.isCoveredByHistory)
        } }.groupBy { it.first }
        return EvidenceMetric(facts.values.count { versions -> versions.any { it.second } },
            (usableSnapshots - semanticV2.size).coerceAtLeast(0) + unknownNovelty, maxOf(usableSnapshots, semanticV2.size))
    }
    return newFactMetric(claims)
}
