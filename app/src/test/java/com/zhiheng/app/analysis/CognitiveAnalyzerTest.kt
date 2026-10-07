package com.zhiheng.app.analysis

import com.zhiheng.app.data.BrowseEvent
import com.zhiheng.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CognitiveAnalyzerTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun event(id: Long, time: Long, text: String, pkg: String = "com.reader") =
        BrowseEvent(id, pkg, time, 32, text)
    private fun analyze(vararg events: BrowseEvent, period: StatisticsPeriod = StatisticsPeriod.DAY) =
        CognitiveAnalyzer().analyze(events.toList(), period, now, zone)

    @Test fun desktopAndButtonsAreNotOpinions() {
        val report = analyze(event(1, now - 1000, "大学就业形势很差", "com.google.android.apps.nexuslauncher"),
            event(2, now, "关注\n点赞\n评论\n收藏\n1.2万"))
        assertTrue(report.claims.isEmpty())
        assertEquals(2, report.skippedRecords)
    }
    @Test fun repeatedScreensAreMergedWithinSession() {
        val report = analyze(event(1, now - 5000, "当前就业形势很差"), event(2, now, "当前就业形势很差"))
        assertEquals(1, report.claims.size)
        assertEquals(1, report.mergedSnapshots)
    }
    @Test fun sameClaimWithNewEvidenceIsNotARepeat() {
        val report = analyze(event(1, now - 300_000, "当前就业形势很差，因为招聘岗位同比减少20%"),
            event(2, now, "当前就业形势很差，因为企业的培训预算明显减少且技能要求提高"))
        assertEquals(Increment.NEW, report.claims.last().increment)
        assertEquals(1L, report.claims.last().previousEventId)
    }
    @Test fun noEvidenceDoesNotMeanLowInformationGain() {
        val report = analyze(event(1, now - 300_000, "当前就业形势很差"), event(2, now, "当前就业形势很差"))
        assertEquals(Increment.UNKNOWN, report.claims.last().increment)
        assertFalse(report.findings.any { it.title.contains("观察到持续低增量") })
    }
    @Test fun timeConditionsAndNegationPreventCarelessGrouping() {
        val report = analyze(event(1, now - 900_000, "当前就业形势很好"),
            event(2, now - 600_000, "当前就业形势不好"), event(3, now, "未来就业形势很好"))
        assertEquals(3, report.groups.size)
    }
    @Test fun chronologyRatherThanInputOrderDeterminesEvidenceReuse() {
        val report = analyze(event(2, now, "当前就业形势很差，因为招聘岗位同比减少20%"),
            event(1, now - 300_000, "当前就业形势很差，因为招聘岗位同比减少20%"))
        assertEquals(Increment.REPEATED, report.claims.last().increment)
        assertEquals(1L, report.claims.last().previousEventId)
    }
    @Test fun persistentRepeatRequiresSeveralDaysAndEnoughDistinctEvents() {
        val events = List(12) { index -> event(index + 1L, now - (index % 3) * 86_400_000L - (index / 3) * 300_000L,
            "当前就业形势很差，因为招聘岗位同比减少20%") }
        val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, zone)
        assertEquals(3, report.observedDays)
        assertTrue(report.findings.any { it.title.contains("观察到持续低增量") })
        assertTrue(report.findings.first().eventIds.isNotEmpty())
    }
    @Test fun sameDayConcentrationIsNotCalledACocoon() {
        val events = List(12) { index -> event(index + 1L, now - index * 300_000L, "当前就业形势很差，因为招聘岗位同比减少20%") }
        val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.DAY, now, zone)
        assertTrue(report.findings.all { it.title.contains("观察不足") })
    }
    @Test fun reportContainsProvenanceAndMethodLimits() {
        val report = analyze(event(7, now, "当前就业形势很差，因为招聘岗位同比减少20%"))
        val text = ReportExporter.text(report)
        assertTrue(text.contains("事件 #7"))
        assertTrue(text.contains("未实现多模态模型"))
        assertTrue(text.contains("不代表没有信息茧房"))
        assertTrue(text.contains("当前就业形势很差"))
    }
    @Test fun changingCountersDoNotCreateRepeatedExposure() {
        val prose = "当前就业形势越来越差，因为招聘岗位数量同比下降20%"
        val report = analyze(*List(16) { index -> event(index + 1L, now - (15-index)*2000L,
            "$prose\n${index + 100}点赞") }.toTypedArray())
        assertEquals(1, report.claims.size)
        assertEquals(15, report.mergedSnapshots)
        assertEquals(Increment.FIRST, report.claims.single().increment)
        assertNull(report.claims.single().durationMillis)
    }
    @Test fun leavingAnAppAndReturningIsANewVisit() {
        val text = "当前就业形势越来越差，因为招聘岗位数量同比下降20%"
        val report = analyze(event(1, now-5000, text),
            event(2, now-3000, "首页", "com.other"), event(3, now, text))
        assertEquals(2, report.claims.size)
    }
    @Test fun explicitSessionsRemainDistinctAndExposeDuration() {
        val text = "当前就业形势越来越差，因为招聘岗位数量同比下降20%"
        val report = analyze(event(1, now-5000, text).copy(sampleCount=3, durationMillis=4000),
            event(2, now, text).copy(sampleCount=1))
        assertEquals(2, report.claims.size)
        assertEquals(4000L, report.claims.first().durationMillis)
    }
    @Test fun changedEvidenceIsNotSilentlyMerged() {
        assertFalse(com.zhiheng.app.capture.ContentContinuity.same(
            "当前就业形势越来越差，因为招聘岗位数量同比下降20%",
            "当前就业形势越来越差，因为招聘岗位数量同比下降40%"))
    }
}
