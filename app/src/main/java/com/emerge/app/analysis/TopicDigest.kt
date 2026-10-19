package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import java.time.ZoneId

data class DigestEvidence(val text: String, val type: EvidenceType, val eventIds: List<Long>,
    val historyMatches: List<EvidenceHistoryMatch>, val hasNew: Boolean, val unresolved: Boolean = false)
data class DigestViewpoint(val key: String, val quote: String, val dimension: String,
    val stance: String, val conditions: String, val workKeys: Set<String>,
    val observations: List<ClaimObservation>, val evidence: List<DigestEvidence>) {
    val workCount: Int get() = workKeys.size
}
data class TopicDigest(val key: String, val topic: String, val subject: String,
    val viewpoints: List<DigestViewpoint>) {
    val workCount: Int get() = viewpoints.flatMap { it.workKeys }.distinct().size
}

object TopicDigests {
    private data class Entry(val claim: ClaimObservation, val work: String)
    private fun key(vararg fields: String) = fields.joinToString("") { "${it.length}:$it" }
    private fun normalized(value: String) = LiteralWhitespace.collapse(value.trim().lowercase(java.util.Locale.ROOT))

    /** A view over retained material; counts are supporting works, never independent sources. */
    fun build(claims: List<ClaimObservation>, events: List<BrowseEvent>, zone: ZoneId): List<TopicDigest> {
        val byId = events.associateBy { it.id }
        fun work(event: BrowseEvent): BrowseEvent? = when {
            event.sourceType == "accessibility" -> event
            event.sourceType in setOf("screen_ocr", "visual_model", "audio_playback") ->
                event.parentEventId?.let(byId::get)?.takeIf { it.sourceType == "accessibility" &&
                    it.packageName == event.packageName && it.workKey == event.workKey }
            else -> null
        }
        val entries = claims.mapNotNull { claim ->
            val event = byId[claim.eventId]?.takeIf { it.packageName == claim.packageName &&
                claim.quote.isNotBlank() && it.visibleText.contains(claim.quote) } ?: return@mapNotNull null
            val parent = work(event) ?: return@mapNotNull null
            Entry(claim, BrowseWorkKey.of(parent))
        }.distinct().sortedBy { it.claim.timestamp }
        return entries.groupBy { key(normalized(it.claim.topic), normalized(it.claim.subject)) }.map { (topicKey, rows) ->
            val viewpoints = rows.groupBy { entry ->
                val claim = entry.claim
                if (claim.uncertainAttribution || claim.analysisSource != "structured_model")
                    key("uncertain", claim.analysisSource, claim.quote,
                        SemanticFieldKeys.conditions(claim.conditions, claim.timestamp, zone))
                else key(normalized(claim.dimension), SemanticFieldKeys.stance(claim.stance, claim.subject, claim.dimension),
                    SemanticFieldKeys.conditions(claim.conditions, claim.timestamp, zone))
            }.map { (viewKey, members) ->
                val first = members.first().claim
                val fragments = members.flatMap { member -> member.claim.evidenceDetails.mapNotNull { fragment ->
                    if (fragment.text.isBlank() || byId[member.claim.eventId]?.visibleText?.contains(fragment.text) != true) null
                    else member.claim to fragment
                } }
                val evidence = fragments.groupBy { (_, fragment) -> key(fragment.type.name, fragment.text,
                    fragment.identity.actors.joinToString("|"), fragment.identity.origins.joinToString("|"), fragment.relativeTimeScope)
                }.values.map { pieces ->
                    val fragment = pieces.first().second
                    val matches = pieces.flatMap { (claim, piece) -> piece.historyMatches.filter { match ->
                        val source = byId[match.eventId]
                        source != null && source.timestamp < claim.timestamp && source.visibleText.contains(match.quote)
                    } }.distinct()
                    DigestEvidence(fragment.text, fragment.type, pieces.map { it.first.eventId }.distinct(), matches,
                        pieces.any { it.second.isNew && !it.second.identityUnresolved }, pieces.any { it.second.identityUnresolved })
                }
                DigestViewpoint(viewKey, first.quote, first.dimension, first.stance, first.conditions,
                    members.map { it.work }.toSet(), members.map { it.claim }, evidence)
            }
            TopicDigest(topicKey, rows.first().claim.topic, rows.first().claim.subject, viewpoints)
        }
    }
}
