package com.emerge.app.analysis

import com.emerge.app.ui.StatisticsPeriod

/** Matches the evidence bar denominator: all claim observations, including unknowns. */
data class RepeatedEvidenceShare(val repeated: Int, val total: Int) {
    val fraction: Float get() = repeated.toFloat() / total
}

fun repeatedEvidenceShare(state: AnalysisState, period: StatisticsPeriod): RepeatedEvidenceShare? {
    val report = state.report?.takeIf { !state.stale && it.period == period.label } ?: return null
    val claims = report.claims
    if (claims.isEmpty() || claims.all { it.increment == Increment.UNKNOWN }) return null
    return RepeatedEvidenceShare(claims.count { it.increment == Increment.REPEATED }, claims.size)
}
