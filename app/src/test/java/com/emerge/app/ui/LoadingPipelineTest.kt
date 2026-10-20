package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import kotlin.system.measureNanoTime
import org.junit.Assert.*
import org.junit.Test

/** Reproducible retained-history workload; timings are observations, not machine-dependent gates. */
class LoadingPipelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
    private val events = List(1200) { index ->
        val at = now - (index % 30) * 86_400_000L - (index / 30 + 1) * 60_000L
        BrowseEvent(index + 1L, "reader.${index % 3}", at, 32,
            "技术应用${index % 12}应该提高工作效率，这是第${index}项记录。",
            endTimestamp = at + 30_000, durationMillis = 30_000, sampleCount = 1, workKey = "work-$index")
    }
    private val semantics = events.mapIndexed { index, event ->
        EventSemantics(event.id, event.visibleText, now, listOf(ArgumentUnit(event.visibleText,
            "技术话题${index % 12}", "技术应用${index % 12}", "工作效率", "支持", "", "", false)))
    }

    private fun <T> measured(name: String, compute: () -> T): T {
        var value = compute()
        val elapsed = List(3) { measureNanoTime { value = compute() } / 1_000_000.0 }.sorted()
        println("LOADING,$name,records=${events.size},median_ms=${elapsed[1]}")
        return value
    }

    @Test fun monthlyModulesRetainEveryWorkAndTheirFilteringResults() {
        val report = measured("report") {
            CognitiveAnalyzer().analyze(events, StatisticsPeriod.MONTH, now, zone, semantics = semantics)
        }
        assertEquals(1200, report.rawRecords)
        val buckets = measured("viewpoints") {
            ViewpointTimeline.build(events, StatisticsPeriod.MONTH, now, zone, report, semantics)
        }
        assertEquals(1200, ViewpointTimeline.details(buckets.flatMap { it.items }).size)
        val groups = measured("viewpoint-groups") {
            ViewpointTimeline.groups(buckets.flatMap { it.items })
        }
        assertEquals(1200, groups.sumOf { it.items.size })
        assertEquals(12, groups.size)
        val topics = measured("topic-timeline") {
            TopicTimeline.build(events, StatisticsPeriod.MONTH, now, zone, report, semantics)
        }
        assertEquals(1200, topics.flatMap { it.works }.map { it.workIdentity }.distinct().size)
        val sharedTopics = measured("topic-timeline-shared") { TopicTimeline.fromViewpoints(buckets) }
        assertEquals(topics, sharedTopics)
        val history = measured("history-search") {
            swissHistoryRecords(events, semantics, report, "reader.1", "技术话题1")
        }
        assertEquals(200, history.size) // Topic 1 and topic 10 are both substring matches in reader.1.
        val historyIndex = prepareSwissHistory(events, semantics, report)
        val filteredHistory = measured("history-filter-retained") {
            filterSwissHistory(historyIndex, "reader.1", "技术话题1")
        }
        assertEquals(history, filteredHistory.records)
        val viewpointIndex = prepareSwissViewpoints(events, semantics, report, StatisticsPeriod.MONTH, now, zone)
        val filteredViewpoints = measured("viewpoint-filter-retained") {
            filterSwissViewpoints(viewpointIndex, "技术话题1", zone)
        }
        assertEquals(100, filteredViewpoints.workCount)
        val progress = ReportAnalysisProgress(events, report)
        val progressReport = measured("report-progress-retained") {
            progress.applyTo(report, mapOf(1L to "running", 2L to "failed"))
        }
        assertEquals(1, progressReport.pendingAnalysisRecords)
        assertSame(report.claims, progressReport.claims)
        val words = measured("recent-words") {
            recentContentWords(events, StatisticsPeriod.MONTH, now, semantics, zone)
        }
        assertTrue(words.any { it.text == "技术应用1" && it.recordCount == 100 })
        val emotion = measured("emotion") { EmotionTimeline.analyze(events, semantics, now, zone) }
        assertEquals(40, emotion.flatMap { it.posts }.map { it.workIdentity }.distinct().size)
        val monthlyEmotion = measured("emotion-month") {
            EmotionTimeline.analyze(events, semantics, now, zone, StatisticsPeriod.MONTH)
        }
        assertEquals(30, monthlyEmotion.size)
        assertEquals(1200, monthlyEmotion.flatMap { it.posts }.map { it.workIdentity }.distinct().size)
        assertEquals(1200 * 30_000L, monthlyEmotion.sumOf { it.durationMillis })
    }
}
