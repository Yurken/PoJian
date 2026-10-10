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
    val uncertain: Boolean = true
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
                    (claim.evidence.isEmpty() || claim.evidence.isNotBlank() && inputText.contains(claim.evidence))
            }
}
