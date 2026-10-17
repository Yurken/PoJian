package com.emerge.app.monitoring

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.capture.VisibilityInterval
import com.emerge.app.capture.VisibilityIntervals
import com.emerge.app.capture.VisibilityRegion
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DerivedMonitorFindingsTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val quote = "当前招聘岗位减少"
    private val proof = "报告松岚记录本季度招聘岗位同比减少24%"
    private val emotionalQuote = "找不到工作让我非常焦虑"
    private val unknown = ImpactSignal(ImpactLevel.UNKNOWN, "尚未评估", "")
    private data class Fixture(val report: CognitiveReport, val events: List<BrowseEvent>)
    private fun fixture(newLatest: Boolean = false, lowLatest: Boolean = false): Fixture {
        val events = List(30) { index ->
            val value = if (newLatest && index == 29) proof.replace("24%", "25%") else proof
            val at = now - (2 - index / 10) * 86_400_000L - (10 - index % 10) * 4 * 60_000L
            BrowseEvent(index + 1L, "reader", at, 32, "$quote，因为$value。$emotionalQuote。", sampleCount = 2,
                workKey = "post-$index", endTimestamp = at + 4 * 60_000L, durationMillis = 4 * 60_000L,
                visibilityIntervals = VisibilityIntervals.encode(listOf(VisibilityInterval(at, at + 4 * 60_000L,
                    4 * 60_000L, VisibilityRegion.MAIN))))
        }
        val semantics = events.map { event ->
            val presentation = ClaimPresentation("求职", "岗位变化", unknown, unknown, unknown, listOf("核对岗位要求"),
                listOf(ContentEmotion(EmotionCategory.ANXIETY,
                    if (lowLatest && event.id == 30L) ImpactLevel.LOW else ImpactLevel.HIGH, emotionalQuote)))
            EventSemantics(event.id, event.visibleText, now, listOf(ArgumentUnit(quote, "就业", "招聘岗位", "数量", "减少",
                event.visibleText.substringAfter("因为").substringBefore("。"), "本季度", false, presentation = presentation)))
        }
        return Fixture(CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics), events)
    }
    private fun build(sample: Fixture, config: MonitorConfig = MonitorConfig(),
        screen: String = sample.events.last().visibleText, packageName: String = "reader") =
        DerivedMonitorFindings.build(sample.report, sample.events, config, packageName, screen, "current-screen")

    @Test fun verifiedRepeatedCurrentEvidenceCanBindTheConcentrationFinding() {
        val findings = build(fixture())
        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals("cocoon", finding.kind)
        assertEquals("concentration", finding.verdict)
        assertEquals("event:30", finding.contentKey)
        assertEquals(quote, finding.claim)
        assertEquals("current-screen", finding.contextToken)
        assertTrue(finding.explanation.contains("30"))
        assertTrue(finding.explanation.contains("29"))
        assertTrue(finding.explanation.contains("#30"))
    }

    @Test fun aNewArgumentDoesNotActivateAnExistingHistoricalConcentrationFinding() {
        val sample = fixture(newLatest = true)
        assertTrue(sample.report.exposure!!.signals.isNotEmpty())
        val findings = build(sample)
        assertEquals(1, findings.size)
        assertEquals("event:29", findings.single().contentKey)
        assertEquals("", findings.single().contextToken)
    }

    @Test fun seeingOnlyTheSameViewpointOrAnotherAppDoesNotBindOldEvidence() {
        val sample = fixture()
        assertEquals("", build(sample, screen = quote).single().contextToken)
        assertEquals("", build(sample, packageName = "other.reader").single().contextToken)
    }

    @Test fun repeatedTagsWithoutVerifiedReferencesCannotCreateAConcentrationFinding() {
        val sample = fixture()
        val exposure = sample.report.exposure!!
        val missing = exposure.copy(viewpoints = exposure.viewpoints.map { it.copy(longitudinal = it.longitudinal.copy(
            evidence = it.longitudinal.evidence.map { reference -> reference.copy(historyMatches = emptyList()) })) })
        assertTrue(build(sample.copy(report = sample.report.copy(exposure = missing))).isEmpty())
    }

    @Test fun deletedOriginalEvidenceCannotSurviveAsADerivedConcentrationFinding() {
        val sample = fixture()
        assertTrue(build(sample.copy(events = sample.events.dropLast(1))).isEmpty())
        assertTrue(build(sample.copy(events = sample.events.dropLast(1) + sample.events.last().copy(visibleText = "已更正"))).isEmpty())
    }

    @Test fun emotionFindingKeepsTheActualHighQuoteAndTopicCategory() {
        val findings = build(fixture(), MonitorConfig(cocoon = false, emotion = true))
        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals("emotion", finding.kind)
        assertEquals("emotional_exposure", finding.verdict)
        assertEquals("就业 · 焦虑", finding.topic)
        assertEquals(emotionalQuote, finding.claim)
        assertEquals("event:30", finding.contentKey)
        assertEquals("current-screen", finding.contextToken)
        assertTrue(finding.explanation.contains("#30"))
    }

    @Test fun earlierHighEvidenceCannotBindToANewLowIntensityRecord() {
        val sample = fixture(lowLatest = true)
        val findings = build(sample, MonitorConfig(cocoon = false, emotion = true))
        assertEquals(1, findings.size)
        assertEquals("event:29", findings.single().contentKey)
        assertEquals("", findings.single().contextToken)
    }

    @Test fun disabledKindsDoNotProduceDerivedFindings() {
        val sample = fixture()
        assertTrue(build(sample, MonitorConfig(cocoon = false, emotion = false)).isEmpty())
        assertEquals(setOf("cocoon", "emotion"), build(sample, MonitorConfig(cocoon = true, emotion = true)).map { it.kind }.toSet())
    }
}
