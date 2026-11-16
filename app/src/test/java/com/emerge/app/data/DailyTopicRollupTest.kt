package com.emerge.app.data

import com.emerge.app.analysis.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyTopicRollupTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = Instant.parse("2026-11-16T15:59:59Z").toEpochMilli()
    private val text = "公司减少岗位。所有毕业生都没有出路。"
    private fun event() = BrowseEvent(1, "reader", time, 0, text, time + 2000, 2000, 2,
        workKey = "work-one", identityMethod = "platform_id")
    private fun semantic(event: BrowseEvent, history: String? = null, fact: String = "公司减少岗位") = EventSemantics(
        event.id, event.visibleText, time + 3000, emptyList(), semanticV2 = SemanticAnalysisV2(
            "work-one", "就业", false, listOf(SemanticViewpoint("就业困难", "支持", evidences = listOf(
                SemanticEvidence("公司减少岗位", fact, history != null, history)))),
            SemanticGuidance(true, "公司减少岗位", "所有毕业生都没有出路", "个别公司到所有毕业生", "", "无助化"),
            emptyList(), if (history == null) 1 else 0, 1, if (history == null) 1.0 else 0.0, 1))

    @Test fun crossMidnightPreservesDurationAndStableFactIdentity() {
        val row = event()
        val projection = DailyTopicRollupProjector.project(row, semantic(row), zone)
        assertEquals(listOf("2026-11-16", "2026-11-17"), projection.contributions.map { it.dateKey })
        assertEquals(listOf(1000L, 1000L), projection.contributions.map { it.guidanceDurationMillis })
        assertEquals(1, projection.facts.map { it.factKey }.distinct().size)
        assertEquals(listOf(true, false), projection.facts.map { it.isNew })
    }

    @Test fun historicalCoverageUsesTheProvidedStableIdentity() {
        val row = event()
        val projection = DailyTopicRollupProjector.project(row, semantic(row, "fact:prior"), zone)
        assertTrue(projection.facts.all { it.factKey == "fact:prior" && !it.isNew })
    }

    @Test fun invalidSpansAndLegacyClaimsDoNotInventMeasuredFacts() {
        val row = event()
        assertTrue(DailyTopicRollupProjector.project(row, semantic(row, fact = "原文不存在"), zone).facts.isEmpty())
        val legacy = EventSemantics(row.id, row.visibleText, time, listOf(
            ArgumentUnit("公司减少岗位", "就业", "公司", "招聘", "减少", "公司减少岗位", uncertain = false)))
        val projection = DailyTopicRollupProjector.project(row, legacy, zone)
        assertTrue(projection.facts.isEmpty())
        assertTrue(projection.contributions.all { it.analyzedEvents == 0 && it.guidanceEvents == 0 })
    }

    @Test fun materialDoesNotCreateAnExtraViewOrGuidanceDuration() {
        val row = event().copy(sourceType = "screen_ocr", parentEventId = 2)
        val projection = DailyTopicRollupProjector.project(row, semantic(row), zone)
        assertTrue(projection.contributions.all { it.viewEvents == 0 && it.guidanceDurationMillis == 0L })
        assertEquals(2, projection.facts.size)
    }

    @Test fun fallbackDoesNotCountPriorDayNoveltyAgainAfterMidnight() {
        val row = event()
        val result = CognitiveAnalyzer().analyze(listOf(row), com.emerge.app.ui.StatisticsPeriod.DAY,
            time + 3000, zone, semantics = listOf(semantic(row)))
        assertNull(result.dailyRollup)
        assertEquals(EvidenceMetric(0, 0, 1), result.reportNewFactMetric())
    }

    @Test fun staleRollupFingerprintCannotReplaceSnapshotFacts() {
        val row = event().copy(timestamp = time - 2000, endTimestamp = time - 1)
        val result = CognitiveAnalyzer().analyze(listOf(row), com.emerge.app.ui.StatisticsPeriod.DAY,
            time, zone, semantics = listOf(semantic(row))).copy(
            dailyRollup = DailyRollupSummary(emptyList(), 1, 1, 0, 0, 1, 0, 0, "another-snapshot"))
        assertEquals(EvidenceMetric(1, 0, 1), result.reportNewFactMetric())
    }

    @Test fun newFactMetricUsesPersistedZeroInsteadOfMissingClaims() {
        val report = CognitiveAnalyzer().analyze(listOf(event()), com.emerge.app.ui.StatisticsPeriod.DAY,
            time + 3000, zone, semantics = listOf(semantic(event()))).copy(
            dailyRollup = DailyRollupSummary(emptyList(), 1, 1, 0, 0, 1, 0, 0,
                RollupSemanticFingerprint.of(mapOf(1L to semantic(event()).semanticV2!!))))
        assertEquals(EvidenceMetric(0, 0, 1), report.reportNewFactMetric())
        assertEquals("0", report.reportNewFactMetric().display)
    }
}
