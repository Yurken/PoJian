package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SwissScreenPreparationTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()

    private fun event(id: Long, text: String, at: Long = now - 60_000) =
        BrowseEvent(id, "reader", at, 1, text, workKey = "work:$id")

    private fun semantic(event: BrowseEvent, topic: String = "就业", at: Long = now) =
        EventSemantics(event.id, event.visibleText, at,
            listOf(ArgumentUnit(event.visibleText, topic, "企业", "岗位", "支持", uncertain = false)))

    private fun report(period: StatisticsPeriod = StatisticsPeriod.HOUR) =
        CognitiveAnalyzer().analyze(emptyList(), period, now, zone)

    @Test fun historyFiltersReusePreparedRecordsAndKeepKeywordSeparateFromTextSearch() {
        val first = event(1, "岗位增加")
        val second = event(2, "岗位减少")
        val source = ReadCountingList(listOf(first, second))
        val index = prepareSwissHistory(source, listOf(semantic(first), semantic(second)), null)
        source.reads = 0

        assertEquals(listOf(2L, 1L), filterSwissHistory(index, null, " 就业 ").records.map { it.id })
        assertEquals(listOf(first), filterSwissHistory(index, "reader", "就业", " 增加 ").records)
        assertTrue(filterSwissHistory(index, null, null, "就业").records.isEmpty())
        assertEquals(listOf(2L, 1L), filterSwissHistory(index, null, null, "阅读", mapOf("reader" to "阅读器")).records.map { it.id })
        assertTrue(filterSwissHistory(index, null, null, "阅读", mapOf("reader" to "新名称")).records.isEmpty())
        assertEquals(0, source.reads)
    }

    @Test fun historyRefreshRejectsSupersededModelsAndInvalidReportQuotes() {
        val current = event(1, "岗位增加")
        val original = semantic(current)
        val observed = ClaimObservation(current.id, current.timestamp, current.packageName,
            "报告话题", current.visibleText, emptyList(), "", false, Increment.FIRST, null)
        val before = prepareSwissHistory(listOf(current), listOf(original), report().copy(claims = listOf(observed)))
        assertEquals(listOf(current), filterSwissHistory(before, null, "就业").records)
        assertEquals(listOf(current), filterSwissHistory(before, null, "报告话题").records)

        val after = prepareSwissHistory(listOf(current), listOf(original, original.copy(analyzedAt = now + 1, claims = emptyList())),
            report().copy(claims = listOf(observed.copy(packageName = "other"), observed.copy(quote = "已删除原文"))))
        assertTrue(filterSwissHistory(after, null, "就业").records.isEmpty())
        assertTrue(filterSwissHistory(after, null, "报告话题").records.isEmpty())
        assertEquals(listOf(current), filterSwissHistory(after, null, null).records)
    }

    @Test fun historyEvidenceLinksRefreshAfterHistoricalContentChangesOrDisappears() {
        val old = event(1, "新增100个岗位", now - 120_000)
        val current = event(2, "新增100个岗位")
        val match = EvidenceHistoryMatch(old.id, old.visibleText)
        val observed = ClaimObservation(current.id, current.timestamp, current.packageName, "就业", current.visibleText,
            listOf(current.visibleText), "", false, Increment.REPEATED, old.id,
            evidenceDetails = listOf(EvidenceFragment(current.visibleText, EvidenceType.FACT, historyMatches = listOf(match))))
        val report = report().copy(claims = listOf(observed))
        val before = prepareSwissHistory(listOf(old, current), emptyList(), report)
        assertEquals(listOf(match), filterSwissHistory(before, "reader", null, "100").matches[current.id])

        val changed = prepareSwissHistory(listOf(old.copy(visibleText = "原文已变"), current), emptyList(), report)
        assertTrue(filterSwissHistory(changed, null, null).matches[current.id].orEmpty().isEmpty())
        val deleted = prepareSwissHistory(listOf(current), emptyList(), report)
        assertTrue(filterSwissHistory(deleted, null, null).matches[current.id].orEmpty().isEmpty())
    }

    @Test fun topicSelectionReusesTimelinesAndKeepsFullTopicStripAndWeekWindow() {
        val first = event(1, "岗位增加。技术升级")
        val second = event(2, "岗位减少")
        val yesterday = event(3, "昨日岗位增加", now - 86_400_000)
        val models = listOf(semantic(first).copy(claims = listOf(
            ArgumentUnit("岗位增加", "就业", "企业", "岗位", "支持", uncertain = false),
            ArgumentUnit("技术升级", "科技", "产品", "技术", "质疑", uncertain = false))), semantic(second), semantic(yesterday))
        val source = ReadCountingList(listOf(first, second, yesterday))
        val prepared = prepareSwissViewpoints(source, models, null, StatisticsPeriod.HOUR, now, zone)
        source.reads = 0

        val employment = filterSwissViewpoints(prepared, "就业", zone)
        assertEquals(2, employment.workCount)
        assertEquals(listOf("就业", "科技"), employment.topics)
        assertTrue(employment.candidates.flatMap { it.items }.flatMap { it.claims }.all { it.topic == "就业" })
        assertEquals(2, employment.shares.single().count)
        assertEquals(ViewpointStance.SUPPORT, employment.shares.single().stance)
        assertEquals(3, employment.trend.sumOf { it.counts.getValue(ViewpointStance.SUPPORT) })

        val technology = filterSwissViewpoints(prepared, "科技", zone)
        assertEquals(1, technology.workCount)
        assertEquals(1, technology.trend.sumOf { it.counts.getValue(ViewpointStance.QUESTION) })
        val absent = filterSwissViewpoints(prepared, "尚无话题", zone)
        assertEquals(listOf("就业", "科技", "尚无话题"), absent.topics)
        assertEquals(0, absent.workCount)
        assertTrue(absent.candidates.isEmpty())
        assertEquals(0, source.reads)
    }

    @Test fun currentPeriodReportCannotSupplyClaimsToTheIndependentWeekTrend() {
        val current = event(1, "应该提高招聘岗位数量")
        val observation = ClaimObservation(current.id, current.timestamp, current.packageName, "报告专属话题",
            current.visibleText, emptyList(), "", false, Increment.FIRST, null)
        val prepared = prepareSwissViewpoints(listOf(current), emptyList(),
            report(StatisticsPeriod.WEEK).copy(claims = listOf(observation)), StatisticsPeriod.WEEK, now, zone)
        val selected = filterSwissViewpoints(prepared, "报告专属话题", zone)
        assertEquals(1, selected.workCount)
        assertTrue(selected.trend.all { it.counts.values.sum() == 0 && it.value == null })
    }

    @Test fun viewpointRefreshRemovesModelClaimsAfterTextChangeOrNewEmptyAnalysis() {
        val current = event(1, "岗位增加")
        val model = semantic(current)
        val before = prepareSwissViewpoints(listOf(current), listOf(model), null, StatisticsPeriod.HOUR, now, zone)
        assertEquals(1, filterSwissViewpoints(before, "就业", zone).workCount)

        val changed = prepareSwissViewpoints(listOf(current.copy(visibleText = "天气晴朗")), listOf(model),
            null, StatisticsPeriod.HOUR, now, zone)
        assertEquals(0, filterSwissViewpoints(changed, "就业", zone).workCount)
        val superseded = prepareSwissViewpoints(listOf(current), listOf(model, model.copy(analyzedAt = now + 1, claims = emptyList())),
            null, StatisticsPeriod.HOUR, now, zone)
        assertEquals(0, filterSwissViewpoints(superseded, "就业", zone).workCount)
        assertTrue(filterSwissViewpoints(superseded, "就业", zone).concentrations.isEmpty())
    }

    private class ReadCountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T {
            reads++
            return values[index]
        }
    }
}
