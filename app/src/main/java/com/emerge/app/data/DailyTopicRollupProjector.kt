package com.emerge.app.data

import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.analysis.factKey
import com.emerge.app.analysis.SemanticAnalysisV2
import com.emerge.app.analysis.SemanticV2Codec
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

internal data class RollupProjection(val contributions: List<RollupContribution>, val facts: List<RollupFact>)

/** Projection has no model calls: only validated retained semantics count as facts. */
internal object DailyTopicRollupProjector {
    fun project(event: BrowseEvent, supplied: EventSemantics?, zone: ZoneId): RollupProjection {
        if (event.sourceType == "recommendation_list") return RollupProjection(emptyList(), emptyList())
        val semantics = supplied?.takeIf { it.validFor(event) }
        val v2 = semantics?.semanticV2
        val topics = v2?.let { listOf(it.topicName) }
            ?: semantics?.claims?.map { it.topic.trim().ifBlank { "未分类" } }?.distinct()?.takeIf { it.isNotEmpty() }
            ?: listOf("未分类")
        val isVisit = event.sourceType == "accessibility" && event.parentEventId == null
        val workKey = event.workKey ?: "event:${event.id}"
        val durationEnd = if (event.endTimestamp > 0) event.endTimestamp else
            event.timestamp + event.durationMillis.coerceIn(0, Long.MAX_VALUE - event.timestamp.coerceAtLeast(0))
        val window = ObservationWindow(event.timestamp, maxOf(event.timestamp, durationEnd), zone)
        val dates = window.slices(event).groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString() }
            .mapValues { (_, slices) -> window.unionSlices(slices) }
            .ifEmpty { mapOf(Instant.ofEpochMilli(event.timestamp).atZone(zone).toLocalDate().toString() to 0L) }
        val semanticFingerprint = v2?.let(RollupSemanticFingerprint::of).orEmpty()
        val firstDate = Instant.ofEpochMilli(event.timestamp).atZone(zone).toLocalDate().toString()
        val guided = isVisit && v2?.emotionalGuidance?.isOverExtrapolation == true
        val contributions = dates.flatMap { (date, duration) -> topics.map { topic ->
            RollupContribution(event.id, date, topic, workKey, if (isVisit) 1 else 0,
                if (guided) 1 else 0, if (guided) duration else 0, if (v2 != null) 1 else 0, event.timestamp, semanticFingerprint)
        } }
        // The same fact spanning midnight is visible on each day; range totals use the stable key.
        val facts = v2?.facts.orEmpty().flatMap { fact ->
            val identity = fact.matchedHistoryId ?: stableFactId(v2!!.topicName, fact.factPoint)
            dates.keys.map { date -> RollupFact(event.id, date, v2!!.topicName, identity,
                fact.factPoint, event.packageName, event.timestamp, !fact.isCoveredByHistory && date == firstDate) }
        }.distinctBy { Triple(it.dateKey, it.topicName, it.factKey) }
        return RollupProjection(contributions, facts)
    }

    fun stableFactId(topic: String, fact: String): String = "fact:" + MessageDigest.getInstance("SHA-256")
        .digest((factKey(topic) + "\u0000" + factKey(fact)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** A summary may be attached to a UI snapshot only when the exact V2 result identities agree. */
object RollupSemanticFingerprint {
    fun of(value: SemanticAnalysisV2): String = sha(SemanticV2Codec.encode(value).toString())
    fun of(values: Map<Long, SemanticAnalysisV2>): String = combine(values.map { (id, value) -> RollupSemanticKey(id, of(value)) })
    fun combine(keys: List<RollupSemanticKey>): String = sha(keys.distinct().sortedBy { it.eventId }
        .joinToString("\n") { "${it.eventId}:${it.semanticFingerprint}" })
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
