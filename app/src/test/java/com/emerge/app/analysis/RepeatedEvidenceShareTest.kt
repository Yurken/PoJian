package com.emerge.app.analysis

import com.emerge.app.ui.StatisticsPeriod
import org.junit.Assert.*
import org.junit.Test

class RepeatedEvidenceShareTest {
    private fun state(vararg increments: Increment): AnalysisState {
        val report = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.DAY, 1_792_000_000_000L)
        return AnalysisState(report = report.copy(claims = increments.mapIndexed { i, increment ->
            ClaimObservation(i.toLong(), report.end, "test", "主题", "观点", emptyList(), "", false, increment, null)
        }))
    }

    @Test fun denominatorKeepsFirstNewConditionsAndUnknownObservations() {
        val result = repeatedEvidenceShare(state(Increment.REPEATED, Increment.NEW, Increment.FIRST,
            Increment.CONDITIONS, Increment.UNKNOWN), StatisticsPeriod.DAY)!!
        assertEquals(1, result.repeated)
        assertEquals(5, result.total)
        assertEquals(.2f, result.fraction, .0001f)
    }

    @Test fun missingStaleWrongPeriodAndUnclassifiableResultsStayUnknown() {
        assertNull(repeatedEvidenceShare(AnalysisState(), StatisticsPeriod.DAY))
        assertNull(repeatedEvidenceShare(state(), StatisticsPeriod.DAY))
        assertNull(repeatedEvidenceShare(state(Increment.UNKNOWN), StatisticsPeriod.DAY))
        assertNull(repeatedEvidenceShare(state(Increment.REPEATED).copy(stale = true), StatisticsPeriod.DAY))
        assertNull(repeatedEvidenceShare(state(Increment.REPEATED), StatisticsPeriod.WEEK))
    }

    @Test fun observedZeroAndAllRepeatedRemainDistinctFromUnknown() {
        assertEquals(0f, repeatedEvidenceShare(state(Increment.FIRST, Increment.NEW), StatisticsPeriod.DAY)!!.fraction)
        assertEquals(1f, repeatedEvidenceShare(state(Increment.REPEATED, Increment.REPEATED), StatisticsPeriod.DAY)!!.fraction)
    }
}
