package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LongitudinalEvidenceTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val quote = "当前招聘岗位减少"
    private val repeatedEvidence = "报告松岚记录本季度招聘岗位同比减少24%"

    private data class Fixture(val events: List<BrowseEvent>, val report: CognitiveReport)
    private fun fixture(count: Int = 30, days: Int = 3,
        evidence: (Int) -> String = { repeatedEvidence },
        work: (Int) -> String = { "post-$it" },
        conditions: (Int) -> String = { "本季度" }): Fixture {
        val events = List(count) { index ->
            val day = index * days / count
            val at = now - (days - day) * 86_400_000L + index * 60_000L
            BrowseEvent(index + 1L, "reader", at, 32, "$quote，因为${evidence(index)}",
                sampleCount = 1, workKey = work(index))
        }
        val semantics = events.mapIndexed { index, event -> EventSemantics(event.id, event.visibleText, now,
            listOf(ArgumentUnit(quote, "就业", "招聘岗位", "数量", "减少", evidence(index),
                conditions(index), false))) }
        return Fixture(events, CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics))
    }

    @Test fun oneDayOfRepeatedEvidenceKeepsConcentrationWithoutALongTermSignal() {
        val assessment = fixture(days = 1).report.exposure!!
        assertTrue(assessment.primary!!.concentrated)
        assertEquals(1, assessment.primary!!.longitudinal.days)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun threeDaysOfKnownRepeatedEvidenceCanProduceASignal() {
        val assessment = fixture().report.exposure!!
        assertTrue(assessment.primary!!.concentrated)
        assertEquals(1, assessment.signals.size)
        val evidence = assessment.signals.single().longitudinal
        assertEquals(30, evidence.contacts)
        assertEquals(3, evidence.days)
        assertEquals(30, evidence.known)
        assertEquals(29, evidence.repeated)
        assertEquals(1.0, evidence.knownCoverage, .00001)
        assertEquals(29.0 / 30, evidence.repeatedShare, .00001)
        assertEquals((1L..30L).toSet(), evidence.eventIds.toSet())
        assertEquals(listOf(repeatedEvidence), evidence.evidence.map { it.quote }.distinct())
        assertTrue(evidence.evidence.drop(1).all { it.historyMatches.isNotEmpty() })
    }

    @Test fun sustainedNewEvidenceKeepsConcentrationWithoutARepetitionSignal() {
        val sample = fixture(evidence = { "报告松岚记录本季度招聘岗位同比减少${it + 1}%" })
        assertEquals(29, sample.report.claims.count { it.increment == Increment.NEW })
        assertTrue(sample.report.exposure!!.primary!!.concentrated)
        assertEquals(0, sample.report.exposure!!.primary!!.longitudinal.repeated)
        assertTrue(sample.report.exposure!!.signals.isEmpty())
    }

    @Test fun unknownEvidenceCannotSupplyKnownCoverage() {
        val assessment = fixture(evidence = { if (it < 17) repeatedEvidence else "" }).report.exposure!!
        assertTrue(assessment.primary!!.concentrated)
        assertEquals(17, assessment.primary!!.longitudinal.known)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun unknownEvidenceStillKeepsEverySupportingOriginalBeyondTheSummaryLimit() {
        val assessment = fixture(evidence = { "" }).report.exposure!!
        val view = assessment.primary!!
        assertTrue(view.longitudinal.evidence.isEmpty())
        assertEquals(8, view.eventIds.size)
        assertEquals((1L..30L).toSet(), view.supportingEventIds.toSet())
    }

    @Test fun aMinorityOfRepeatedEvidenceDoesNotTriggerDespiteFullCoverage() {
        val assessment = fixture(evidence = { if (it < 15) repeatedEvidence else
            "报告松岚记录本季度招聘岗位同比减少${it + 25}%" }).report.exposure!!
        assertTrue(assessment.primary!!.concentrated)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun repeatingTheSameWorksAcrossDaysCannotManufactureLongitudinalEvidence() {
        val assessment = fixture(work = { "same-work-${it % 10}" }).report.exposure!!
        assertTrue(assessment.primary!!.concentrated)
        assertEquals(10, assessment.primary!!.longitudinal.contacts)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun differentConditionsCannotCombineTheirObservedDays() {
        val assessment = fixture(count = 90, conditions = { "地区${it / 30}" }).report.exposure!!
        assertEquals(3, assessment.viewpoints.count { it.concentrated })
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun truncatedComparisonsCannotEstablishKnownRepetition() {
        val sample = fixture()
        val assessment = ExposureAnalyzer.analyze(sample.events,
            sample.report.claims.map { it.copy(historyComparisonTruncated = true) }, now, zone)
        assertTrue(assessment.primary!!.concentrated)
        assertEquals(0, assessment.primary!!.longitudinal.known)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun unresolvedEvidenceIdentityCannotSupplyKnownCoverage() {
        val sample = fixture()
        val assessment = ExposureAnalyzer.analyze(sample.events, sample.report.claims.map { claim ->
            if (claim.eventId <= 17) claim else claim.copy(evidenceDetails = claim.evidenceDetails.map {
                it.copy(identityUnresolved = true)
            })
        }, now, zone)
        assertTrue(assessment.primary!!.concentrated)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun conflictingStancesWithinOneWorkCannotSupplyKnownCoverage() {
        val sample = fixture()
        val conflicting = sample.report.claims.filter { it.eventId > 17 }.map { it.copy(stance = "增加") }
        val assessment = ExposureAnalyzer.analyze(sample.events, sample.report.claims + conflicting, now, zone)
        assertTrue(assessment.primary!!.concentrated)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test fun deletingTheReferencedEvidenceInvalidatesTheRepetitionSignal() {
        val sample = fixture()
        val assessment = ExposureAnalyzer.analyze(sample.events, sample.report.claims.map { claim ->
            claim.copy(evidenceDetails = claim.evidenceDetails.map { detail -> detail.copy(historyMatches =
                detail.historyMatches.map { it.copy(eventId = 99_999L) }) })
        }, now, zone)
        assertTrue(assessment.primary!!.concentrated)
        assertTrue(assessment.signals.isEmpty())
    }
}
