package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ViewpointTimelineTest {
    @Test fun dailySharesMergeViewpointsAndIncludeUnknownWorksInDenominator() {
        val rows = listOf(event(1, "找工作很难，很多人因此感到焦虑"),
            event(2, "求职困难让年轻人越来越焦虑"), event(3, "女性应该经济独立并自主生活"), event(4, "菜单"))
        val groups = ViewpointTimeline.groups(ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone).flatMap { it.items })
        assertEquals(2, groups.first { it.label == "就业焦虑" }.items.size)
        assertEquals(50.0, groups.first { it.label == "就业焦虑" }.percent, .001)
        assertEquals(25.0, groups.first { it.label == "未识别" }.percent, .001)
        assertEquals(100.0, groups.sumOf { it.percent }, .001)
    }

    @Test fun dailySharesDeduplicateWorksAcrossHours() {
        val rows = listOf(event(1, "女性应该经济独立并自主生活", now - 7200000, "same"),
            event(2, "女性应该经济独立并自主生活", now - 60000, "same"))
        val groups = ViewpointTimeline.groups(ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone).flatMap { it.items })
        assertEquals(1, groups.single().total)
        assertEquals(100.0, groups.single().percent, .001)
    }

    @Test fun expandUiSuffixDoesNotHideAViewpointAndCountsNeedNoRiskSampleThreshold() {
        val bucket = ViewpointTimeline.build(listOf(event(1, "女性应该经济独立并自主生活... 展开>")), StatisticsPeriod.DAY, now, zone)[11]
        assertEquals(1, bucket.items.size)
        assertEquals("女性独立", bucket.items.single().label)
        assertNull(bucket.score)
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun event(id: Long, text: String, timestamp: Long = now - 60000, work: String = "work-$id") =
        BrowseEvent(id = id, packageName = "reader", timestamp = timestamp, eventType = 4096,
            visibleText = text, workKey = work, sampleCount = 1, endTimestamp = timestamp)

    @Test fun labelsRetainSpecificViewpointsAndNegation() {
        assertEquals("就业焦虑", ViewpointTimeline.label("找工作很难，很多人因此感到焦虑"))
        assertEquals("女性独立", ViewpointTimeline.label("女性应该经济独立并自主生活"))
        assertEquals("反对女性独立", ViewpointTimeline.label("女性不应该独立生活"))
        assertEquals("AI可靠性", ViewpointTimeline.label("人工智能需要更多可靠的证据"))
    }

    @Test fun volumeAloneCannotProduceRiskAndRepeatedVisitsAreNotNewWorks() {
        val rows = (1L..20L).map { event(it, "菜单\n搜索") }
        assertNull(ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone)[11].score)
        val repeats = (1L..20L).map { event(it, "找工作很难，很多人因此感到焦虑", work = "same") }
        val bucket = ViewpointTimeline.build(repeats, StatisticsPeriod.DAY, now, zone)[11]
        assertEquals(1, bucket.items.size)
        assertNull(bucket.score)
    }

    @Test fun riskIsExplainableFromDistinctWorksAndNotDuration() {
        val rows = (1L..3L).map { event(it, "找工作很难，很多人因此感到焦虑") }
        val bucket = ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone)[11]
        assertEquals(2, bucket.repeated)
        assertEquals(3, bucket.emotional)
        assertEquals(80, bucket.score)
    }

    @Test fun exactHourBoundaryIsAssignedOnlyToCurrentHour() {
        val buckets = ViewpointTimeline.build(listOf(event(1, "女性应该经济独立并自主生活", now)), StatisticsPeriod.DAY, now, zone)
        assertTrue(buckets[11].items.isEmpty())
        assertEquals(1, buckets[12].items.size)
        assertTrue(buckets.all { it.end >= it.start })
    }

    @Test fun weekClickIncludesOnlyWorksInTheSelectedDayAndExcludesAudio() {
        val audio = event(3, "女性应该经济独立并自主生活").copy(sourceType = "audio_transcript")
        val buckets = ViewpointTimeline.build(listOf(event(1, "女性应该经济独立并自主生活"),
            event(2, "就业困难让很多求职者焦虑", now - 86400000), audio), StatisticsPeriod.WEEK, now, zone)
        assertEquals(listOf(2L), buckets[5].items.map { it.record.id })
        assertEquals(listOf(1L), buckets[6].items.map { it.record.id })
    }

    @Test fun effectiveModelWorksWithoutGeneratedReportAndRetainsMultipleViewpoints() {
        val row = event(1, "今年招聘减少，未来岗位需求可能回升")
        val result = semantic(row, listOf(argument("今年招聘减少", "当前招聘", "减少", "今年"),
            argument("未来岗位需求可能回升", "未来需求", "回升", "未来")))
        val groups = ViewpointTimeline.groups(ViewpointTimeline.build(listOf(row), StatisticsPeriod.DAY,
            now, zone, semantics = listOf(result)).flatMap { it.items })
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.total == 1 && it.percent == 100.0 && it.analysisSource == "structured_model" })
        assertEquals(200.0, groups.sumOf { it.percent }, .001)
        assertEquals(setOf("今年招聘减少", "未来岗位需求可能回升"), groups.map { it.items.single().quote }.toSet())
    }

    @Test fun sameShortLabelCannotMergeDifferentModelDimensionsConditionsOrStances() {
        val rows = listOf(event(1, "今年就业困难让人焦虑"), event(2, "未来就业困难让人焦虑"),
            event(3, "今年就业困难让人焦虑"), event(4, "今年就业困难让人焦虑"))
        val results = rows.mapIndexed { index, row -> semantic(row, listOf(argument(row.visibleText,
            if (index == 2) "薪资" else "招聘", if (index == 3) "改善" else "困难",
            if (index == 1) "未来" else "今年"))) }
        val groups = ViewpointTimeline.groups(ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now,
            zone, semantics = results).flatMap { it.items })
        assertEquals(4, groups.size)
        assertTrue(groups.all { it.items.size == 1 && it.total == 4 })
    }

    @Test fun emptyModelResultIsUnknownAndStaleSnapshotFallsBackWithoutChangingWorkDenominator() {
        val first = event(1, "女性应该经济独立并自主生活")
        val second = event(2, "找工作很难，很多人因此感到焦虑")
        val results = listOf(semantic(first, emptyList()), semantic(second, listOf(argument(second.visibleText)))
            .copy(inputText = "之前的原文"))
        val groups = ViewpointTimeline.groups(ViewpointTimeline.build(listOf(first, second), StatisticsPeriod.DAY,
            now, zone, semantics = results).flatMap { it.items })
        assertEquals(2, groups.size)
        assertEquals(1, groups.first { it.label == "未识别" }.items.size)
        assertEquals("structured_model", groups.first { it.label == "未识别" }.items.single().analysisSource)
        assertEquals("local_rules", groups.first { it.label == "就业焦虑" }.analysisSource)
        assertTrue(groups.all { it.total == 2 })
    }

    @Test fun modelVisitsDeduplicateEachWorkAndNeverCountAudioOrVisualTextAsWatchedWorks() {
        val first = event(1, "今年招聘减少", work = "same")
        val repeated = event(2, first.visibleText, work = "same")
        val rows = listOf(first, repeated, event(3, first.visibleText).copy(sourceType = "audio_playback"),
            event(4, first.visibleText).copy(sourceType = "visual_model"))
        val results = rows.map { semantic(it, listOf(argument(it.visibleText))) }
        val bucket = ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone, semantics = results)[11]
        val share = ViewpointTimeline.groups(bucket.items).single()
        assertEquals(1, bucket.items.size)
        assertEquals(1, share.total)
        assertEquals(1, share.items.size)
    }

    @Test fun unconfirmedModelAttributionKeepsQuotesAndCannotProduceRisk() {
        val rows = (1L..3L).map { event(it, "今年招聘减少") }
        val results = rows.map { semantic(it, listOf(argument(it.visibleText).copy(uncertain = true))) }
        val bucket = ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone, semantics = results)[11]
        assertNull(bucket.score)
        assertEquals(0, bucket.repeated)
        assertTrue(ViewpointTimeline.groups(bucket.items).all { it.label.endsWith("待确认") })
    }

    @Test fun oldReportCannotRestoreAModelClaimAfterTheSnapshotChanges() {
        val old = event(1, "今年招聘减少，未来岗位需求可能回升")
        val result = semantic(old, listOf(argument("今年招聘减少")))
        val report = CognitiveAnalyzer().analyze(listOf(old), StatisticsPeriod.DAY, now, zone,
            semantics = listOf(result))
        val changed = old.copy(visibleText = old.visibleText + "，新补充的条件需要核实")
        val items = ViewpointTimeline.build(listOf(changed), StatisticsPeriod.DAY, now, zone,
            report, listOf(result)).flatMap { it.items }
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.analysisSource == "local_rules" })
        assertTrue(items.flatMap { it.claims }.none { it.analysisSource == "structured_model" })
    }

    @Test fun mergedWorkClaimsRemainBoundToTheVisitThatContainedEachQuote() {
        val first = event(1, "今年招聘减少", work = "same")
        val second = event(2, "未来岗位需求可能回升", timestamp = now - 30000, work = "same")
        val results = listOf(semantic(first, listOf(argument(first.visibleText))),
            semantic(second, listOf(argument(second.visibleText, "需求", "回升", "未来"))))
        val groups = ViewpointTimeline.groups(ViewpointTimeline.build(listOf(first, second),
            StatisticsPeriod.DAY, now, zone, semantics = results).flatMap { it.items })
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.total == 1 && it.items.size == 1 })
        assertTrue(groups.flatMap { it.items }.all { it.record.visibleText.contains(it.quote!!) })
        assertEquals(second.id, groups.single { it.label.endsWith("回升") }.items.single().record.id)
    }

    @Test fun equalTimestampVersionsUseTheLastResultConsistentlyWithTheReport() {
        val row = event(1, "今年招聘减少")
        val earlier = semantic(row, listOf(argument(row.visibleText)))
        val empty = earlier.copy(claims = emptyList())
        val versions = listOf(earlier, empty)
        val report = CognitiveAnalyzer().analyze(listOf(row), StatisticsPeriod.DAY, now, zone, semantics = versions)
        val items = ViewpointTimeline.build(listOf(row), StatisticsPeriod.DAY, now, zone,
            report, versions).flatMap { it.items }
        assertTrue(report.claims.isEmpty())
        assertTrue(items.all { it.claims.isEmpty() && it.analysisSource == "structured_model" })
    }

    private fun argument(quote: String, dimension: String = "招聘", stance: String = "减少", conditions: String = "今年") =
        ArgumentUnit(quote, "就业", "招聘岗位", dimension, stance, conditions = conditions, uncertain = false)
    private fun semantic(row: BrowseEvent, claims: List<ArgumentUnit>) = EventSemantics(row.id, row.visibleText, now, claims)
}
