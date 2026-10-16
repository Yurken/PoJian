package com.emerge.app.analysis

import java.time.Instant
import java.time.ZoneId

/** Retained original evidence, with only still-valid historical quotations. */
data class LongitudinalEvidenceReference(val eventId: Long, val quote: String,
    val historyMatches: List<EvidenceHistoryMatch> = emptyList())

/** Conservative engineering gates, not calibrated risk or evidence of the user's beliefs. */
data class LongitudinalEvidenceAssessment(val contacts: Int = 0, val days: Int = 0,
    val known: Int = 0, val repeated: Int = 0,
    val evidence: List<LongitudinalEvidenceReference> = emptyList()) {
    val knownCoverage: Double get() = if (contacts == 0) 0.0 else known.toDouble() / contacts
    val repeatedShare: Double get() = if (known == 0) 0.0 else repeated.toDouble() / known
    val eligible: Boolean get() = days >= 3 && known >= 8 && knownCoverage >= .6 && repeatedShare >= .6
    val eventIds: List<Long> get() = evidence.flatMap { listOf(it.eventId) + it.historyMatches.map { match -> match.eventId } }.distinct()
}

/** Evaluates one comparable scope and its dominant stance; repeated samples never become new works. */
internal object LongitudinalEvidence {
    fun assess(claims: List<ClaimObservation>, history: List<ClaimObservation>,
        workByEvent: Map<Long, String>, dominantStance: String, zone: ZoneId,
        checkCancellation: () -> Unit = {}): LongitudinalEvidenceAssessment {
        fun stance(claim: ClaimObservation) = SemanticFieldKeys.stance(claim.stance, claim.subject, claim.dimension)
        val contacts = claims.filter { it.eventId in workByEvent }.groupBy { workByEvent.getValue(it.eventId) }
            .values.filter { rows -> rows.any { stance(it) == dominantStance } }
        val historical = history.filter { it.eventId in workByEvent && stance(it) == dominantStance }.groupBy { it.eventId }
        fun resolved(fragment: EvidenceFragment) = !fragment.identityUnresolved &&
            !fragment.identity.unresolvedCase && !fragment.identity.unresolvedReferences && fragment.text.isNotBlank()
        fun known(claim: ClaimObservation) = !claim.uncertainAttribution && !claim.historyComparisonTruncated &&
            claim.increment in setOf(Increment.FIRST, Increment.NEW, Increment.REPEATED) &&
            claim.evidenceDetails.isNotEmpty() && claim.evidenceDetails.all(::resolved)
        fun atom(fragment: EvidenceFragment) = EvidenceAtom(fragment.text, fragment.comparisonText,
            fragment.identity, fragment.strictComparison, fragment.relativeTimeScope)
        fun retainedMatches(claim: ClaimObservation, fragment: EvidenceFragment) = fragment.historyMatches.filter { match ->
            checkCancellation()
            historical[match.eventId].orEmpty().any { prior ->
                prior.timestamp < claim.timestamp && workByEvent[prior.eventId] != workByEvent[claim.eventId] &&
                    known(prior) && prior.evidenceDetails.any { old -> old.text == match.quote && old.type == fragment.type &&
                    EvidenceAtoms.compare(atom(fragment), atom(old)) == EvidenceRelation.REUSED }
            }
        }.distinct()
        var knownCount = 0
        var repeatedCount = 0
        val dates = linkedSetOf<java.time.LocalDate>()
        val evidence = mutableListOf<LongitudinalEvidenceReference>()
        contacts.forEach { samples ->
            checkCancellation()
            val rows = samples.distinct()
            // Conflicting/unknown attribution or evidence cannot borrow certainty from another sample.
            if (rows.any { !known(it) || stance(it) != dominantStance }) return@forEach
            val references = rows.flatMap { claim -> claim.evidenceDetails.map { fragment ->
                Triple(claim, fragment, retainedMatches(claim, fragment))
            } }
            if (references.any { (claim, fragment, matches) ->
                    claim.increment == Increment.REPEATED && (fragment.isNew || matches.isEmpty()) ||
                        claim.increment == Increment.NEW && !fragment.isNew && matches.isEmpty()
                }) return@forEach
            knownCount++
            dates += Instant.ofEpochMilli(rows.minOf { it.timestamp }).atZone(zone).toLocalDate()
            // A work that supplies any new material remains a learning contact, even if sampled again later.
            if (rows.all { it.increment == Increment.REPEATED }) repeatedCount++
            evidence += references.map { (claim, fragment, matches) ->
                LongitudinalEvidenceReference(claim.eventId, fragment.text, matches)
            }.distinct()
        }
        return LongitudinalEvidenceAssessment(contacts.size, dates.size, knownCount, repeatedCount, evidence)
    }
}
