package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent

/** Model fields remain tied to retained text; blank fields represent missing information. */
data class ArgumentUnit(
    val quote: String,
    val topic: String,
    val subject: String,
    val dimension: String,
    val stance: String,
    val evidence: String = "",
    val conditions: String = "",
    val uncertain: Boolean = true,
    /** Verbatim people or case entities within evidence, not the claim subject or post author. */
    val evidenceActors: List<String> = emptyList(),
    /** Verbatim names of the original evidence material, not the post or its publisher. */
    val evidenceOrigins: List<String> = emptyList(),
    val presentation: ClaimPresentation? = null
)

/** analyzedAt selects a result version. The associated event supplies the browsing time. */
data class EventSemantics(
    val eventId: Long,
    val inputText: String,
    val analyzedAt: Long,
    val claims: List<ArgumentUnit>,
    val inputKind: String = "text"
) {
    fun validFor(event: BrowseEvent): Boolean =
        eventId == event.id && inputText == event.visibleText && inputText.isNotBlank() &&
            inputText.length <= 16_000 && analyzedAt >= 0 && inputKind.isNotBlank() && inputKind.length <= 40 &&
            claims.size <= 3 && claims.all { claim ->
                claim.quote.isNotBlank() && claim.quote.length <= 500 && inputText.contains(claim.quote) &&
                    claim.topic.length <= 80 && claim.subject.length <= 100 && claim.dimension.length <= 80 &&
                    claim.stance.length <= 200 && claim.conditions.length <= 200 && claim.evidence.length <= 500 &&
                    (claim.evidence.isEmpty() || claim.evidence.isNotBlank() && inputText.contains(claim.evidence)) &&
                    (claim.presentation?.validFor(inputText) != false) &&
                    listOf(claim.evidenceActors, claim.evidenceOrigins).all { anchors ->
                        anchors.size <= 4 && anchors.distinct().size == anchors.size &&
                            anchors.all { it.isNotBlank() && it.length <= 80 && claim.evidence.contains(it) }
                    }
            }
}
