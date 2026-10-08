package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
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

    @Test fun reportDoesNotInventATimeShareForLegacySnapshots() {
        val report = analyze(event(7, now, "当前就业形势很差，因为招聘岗位同比减少20%"))
        assertEquals(1, report.unknownDurationRecords)
        assertTrue(ReportExporter.text(report).contains("时长占比 暂不可计算"))
    }

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
        assertFalse(com.emerge.app.capture.ContentContinuity.same(
            "当前就业形势越来越差，因为招聘岗位数量同比下降20%",
            "当前就业形势越来越差，因为招聘岗位数量同比下降40%"))
    }

    @Test fun todayUsesLocalMidnightAndAlwaysHasTwentyFourHours() {
        val midnight = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val report = analyze(event(1, midnight - 1, "当前就业形势很差"),
            event(2, midnight, "手机芯片价格可能下降"), event(3, now + 1, "当前就业形势很差"))
        assertEquals(midnight, report.start)
        assertEquals(1, report.rawRecords)
        assertEquals(24, report.hourlyReviews.size)
        assertEquals((0..23).toList(), report.hourlyReviews.map { it.hour })
        assertEquals(1, report.hourlyReviews.first().recordCount)
        assertEquals(0, report.hourlyReviews.last().recordCount)
        assertEquals("今天", report.period)
    }

    @Test fun firstTodayLegacySnapshotIsNotMergedIntoYesterday() {
        val midnight = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val prose = "当前就业形势很差，因为招聘岗位同比减少20%"
        val report = analyze(event(1, midnight - 1000, prose), event(2, midnight + 1000, prose))
        assertEquals(1, report.coverageSessions)
        assertEquals(0, report.mergedSnapshots)
        assertEquals(2L, report.claims.single().eventId)
        assertEquals(Increment.REPEATED, report.claims.single().increment)
        assertEquals(1, report.comparisonRecords)
        assertEquals(midnight - 1000, report.comparisonStart)
    }

    @Test fun crossingMidnightIncludesOnlyTodaysMeasuredDuration() {
        val midnight = Instant.parse("2026-10-06T16:00:00Z").toEpochMilli()
        val session = event(1, midnight - 60_000, "当前就业形势很差，因为招聘岗位同比减少20%")
            .copy(endTimestamp = midnight + 60_000, durationMillis = 120_000, sampleCount = 61)
        val report = analyze(session)
        assertEquals(1, report.rawRecords)
        assertEquals(1, report.coverageSessions)
        assertEquals(60_000L, report.observedDurationMillis)
        assertEquals(60_000L, report.hourlyReviews[0].durationMillis)
        assertEquals(60_000L, report.claims.single().durationMillis)
        assertEquals(1, report.observedDays)
        assertEquals(0, report.comparisonRecords)
    }

    @Test fun hourlyAllocationConservesDurationAndDoesNotMultiplySeveralClaims() {
        val session = event(7, now - 60_000,
            "当前就业形势很差，因为招聘岗位同比减少20%\n手机芯片价格可能下降，因为供应商扩大产量")
            .copy(endTimestamp = now + 60_000, durationMillis = 90_000, sampleCount = 46)
        val report = CognitiveAnalyzer().analyze(listOf(session), StatisticsPeriod.DAY, now + 120_000, zone)
        assertEquals(2, report.claims.size)
        assertEquals(1, report.coverageSessions)
        assertEquals(90_000L, report.observedDurationMillis)
        assertEquals(45_000L, report.hourlyReviews[19].durationMillis)
        assertEquals(45_000L, report.hourlyReviews[20].durationMillis)
        assertEquals(90_000L, report.hourlyReviews.sumOf { it.durationMillis })
        assertEquals(90_000L, report.topicExposure.sumOf { it.durationMillis })
        assertEquals(1, report.topicExposure.sumOf { it.recordCount })
        assertEquals(listOf(7L), report.hourlyReviews[19].claimEventIds)
        assertEquals(listOf(7L), report.hourlyReviews[20].claimEventIds)
    }

    @Test fun legacySnapshotsNeverAcquireDurationFromNeighborsOrStoredDefaults() {
        val report = analyze(event(1, now - 120_000, "当前就业形势很差").copy(durationMillis = 100_000),
            event(2, now, "手机芯片价格可能下降"))
        assertEquals(2, report.unknownDurationRecords)
        assertEquals(0L, report.observedDurationMillis)
        assertTrue(report.claims.all { it.durationMillis == null })
        assertEquals(2, report.hourlyReviews.sumOf { it.unknownDurationRecords })
    }

    @Test fun similarTopicWithDifferentSubjectDoesNotCreateAHistoryLink() {
        val report = analyze(event(1, now - 300_000, "程序员招聘岗位质量越来越差，因为企业预算同比减少20%"),
            event(2, now, "程序员招聘岗位薪资越来越差，因为企业预算同比减少20%"))
        assertEquals(2, report.claims.size)
        assertEquals(report.claims.first().topic, report.claims.last().topic)
        assertEquals(Increment.FIRST, report.claims.last().increment)
        assertNull(report.claims.last().previousEventId)
        assertEquals(2, report.groups.size)
    }

    @Test fun changedRegionAndNearbyPopulationConditionsStaySeparateAndTraceable() {
        val report = analyze(event(1, now - 300_000, "北京就业形势很差，因为招聘岗位同比减少20%"),
            event(2, now, "上海就业形势很差，因为招聘岗位同比减少20%"))
        assertEquals(Increment.CONDITIONS, report.claims.last().increment)
        assertEquals(2, report.groups.size)
        assertTrue(report.claims.last().evidenceDetails.any { it.type == EvidenceType.CONDITION && it.text == "上海" && it.isNew })
        val nearby = analyze(event(1, now - 300_000, "当前就业形势很差\n如果属于应届人群，应先核查招聘岗位"),
            event(2, now, "当前就业形势很差\n如果属于非应届人群，应先核查招聘岗位"))
        assertNotEquals(nearby.claims.first().conditions, nearby.claims.last().conditions)
    }

    @Test fun newFactsAndAdviceRemainTypedEvidenceRatherThanRepetition() {
        val prose = "当前就业形势很差，因为招聘岗位同比减少20%"
        val withFact = analyze(event(1, now - 300_000, prose), event(2, now, prose.replace("20%", "40%")))
        assertEquals(Increment.NEW, withFact.claims.last().increment)
        assertTrue(withFact.claims.last().evidenceDetails.any { it.type == EvidenceType.FACT && it.isNew && it.text.contains("40%") })
        val withAdvice = analyze(event(1, now - 300_000, prose), event(2, now, "$prose\n建议可以先查阅招聘岗位的具体技能要求"))
        assertEquals(Increment.NEW, withAdvice.claims.last().increment)
        assertTrue(withAdvice.claims.last().evidenceDetails.any { it.type == EvidenceType.ADVICE && it.isNew })
    }

    @Test fun quotedUncertainContentDoesNotCreateAnIncrementRelation() {
        val prose = "有人认为当前就业形势很差，因为招聘岗位同比减少20%"
        val report = analyze(event(1, now - 300_000, prose), event(2, now, prose))
        assertTrue(report.claims.all { it.uncertainAttribution })
        assertTrue(report.claims.all { it.increment == Increment.UNKNOWN && it.previousEventId == null })
    }

    @Test fun entertainmentAndContentExpressionsAreReportedWithoutPsychologicalInference() {
        val report = analyze(event(1, now - 60_000, "这部电影的音乐很好，游戏过程也很有趣"),
            event(2, now, "当前就业形势很差，令人焦虑，因为招聘岗位同比减少20%"))
        assertEquals(1, report.topicExposure.first { it.topic == "娱乐与休闲" }.recordCount)
        assertFalse(report.claims.any { it.topic == "娱乐与休闲" })
        assertEquals(2L, report.contentSignals.single().eventId)
        assertTrue(report.contentSignals.single().quote.contains("焦虑"))
        val text = ReportExporter.text(report)
        assertTrue(text.contains("破茧 Emerge"))
        assertTrue(text.contains("00:00–01:00"))
        assertTrue(text.contains("23:00–00:00"))
        assertTrue(text.contains("不推断用户情绪"))
    }

    @Test fun callerCanCancelAnalysisBeforePublishingAResult() {
        var checkpoints = 0
        try {
            CognitiveAnalyzer().analyze(listOf(event(1, now, "当前就业形势很差")),
                StatisticsPeriod.DAY, now, zone, checkCancellation = { checkpoints++; throw java.util.concurrent.CancellationException() })
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(1, checkpoints)
        }
    }

    @Test fun interveningOtherRegionDoesNotHideSameConditionHistory() {
        val beijing = "北京互联网行业招聘岗位数量持续下降，因为统计显示招聘岗位同比减少20%"
        val shanghai = beijing.replace("北京", "上海")
        val report = analyze(event(1, now - 600_000, beijing), event(2, now - 300_000, shanghai),
            event(3, now, beijing))
        assertEquals(Increment.CONDITIONS, report.claims[1].increment)
        assertEquals(Increment.REPEATED, report.claims[2].increment)
        assertEquals(1L, report.claims[2].previousEventId)
    }

    @Test fun industryOrRegionWordsAloneAreNotSupportingEvidence() {
        val prose = "北京互联网行业的招聘岗位数量持续下降"
        val report = analyze(event(1, now - 300_000, prose), event(2, now, prose))
        assertTrue(report.claims.first().evidence.isEmpty())
        assertEquals(Increment.UNKNOWN, report.claims.last().increment)
    }
}
