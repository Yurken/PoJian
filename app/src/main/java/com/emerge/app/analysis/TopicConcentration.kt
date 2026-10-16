package com.emerge.app.analysis

import java.time.ZoneId
import java.util.Locale

data class TopicConcentration(
    val topic: String,
    val works: Int,
    val classified: Int,
    val scope: ConcentrationScope?
)

data class ConcentrationScope(
    val subject: String,
    val dimension: String,
    val conditions: String,
    val stance: String,
    val works: Int,
    val classified: Int,
    val share: Double,
    val sufficient: Boolean
)

/** One work contributes one vote, split between its distinct confirmed stances. Only compare
 * claims about the same subject, dimension and time-anchored conditions. */
object TopicConcentrations {
    private data class Scope(val subject: String, val dimension: String, val conditions: String)
    private fun field(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun analyze(items: List<ViewpointItem>): List<TopicConcentration> {
        val works = ViewpointTimeline.details(items)
        val topics = works.flatMap { it.claims }.map { it.topic.trim() }.filter(String::isNotBlank).distinct()
        return topics.map { topic ->
            val matching = works.filter { item -> item.claims.any { it.topic.trim() == topic } }
            val classified = matching.count { item -> item.claims.any {
                it.topic.trim() == topic && it.analysisSource == "structured_model" && it.confirmed
            } }
            val candidates = matching.flatMap { item -> item.claims.filter {
                it.topic.trim() == topic && it.analysisSource == "structured_model" &&
                    it.subject.isNotBlank() && it.dimension.isNotBlank()
            }.map { item.workIdentity to it } }.groupBy { (_, claim) ->
                Scope(field(claim.subject), field(claim.dimension), claim.record?.let {
                    SemanticFieldKeys.conditions(claim.conditions, it.timestamp, ZoneId.of(claim.zoneId))
                } ?: field(claim.conditions))
            }.map { (_, entries) ->
                val byWork = entries.groupBy { it.first }
                val votes = byWork.values.map { rows -> rows.map { it.second }.filter { it.confirmed }
                    .map { SemanticFieldKeys.stance(it.stance, it.subject, it.dimension) }.distinct() }
                val known = votes.count { it.isNotEmpty() }
                val weights = mutableMapOf<String, Double>()
                votes.forEach { values -> values.forEach { weights[it] = (weights[it] ?: 0.0) + 1.0 / values.size } }
                val leader = weights.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
                    .thenBy { it.key }).firstOrNull()
                val example = entries.map { it.second }.firstOrNull {
                    it.confirmed && SemanticFieldKeys.stance(it.stance, it.subject, it.dimension) == leader?.key
                } ?: entries.first().second
                ConcentrationScope(example.subject, example.dimension, example.conditions, example.stance,
                    byWork.size, known, if (known > 0) (leader?.value ?: 0.0) / known else 0.0,
                    known >= 30 && known.toDouble() / byWork.size >= .6 && classified.toDouble() / matching.size >= .6)
            }
            TopicConcentration(topic, matching.size, classified, candidates.sortedWith(
                compareByDescending<ConcentrationScope> { it.sufficient }.thenByDescending { it.classified }
                    .thenBy { it.subject }.thenBy { it.dimension }).firstOrNull())
        }.sortedWith(compareByDescending<TopicConcentration> { it.works }.thenBy { it.topic })
    }
}
