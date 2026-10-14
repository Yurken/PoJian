package com.emerge.app.analysis

import com.emerge.app.data.SyntheticDataset
import com.emerge.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Exercises the real production aggregators against a test-only fictional dataset. */
class SyntheticDatasetTest {
    private val data by lazy { SyntheticDataset.decode(javaClass.getResource("/synthetic/demo-v1.json")!!.readText()) }
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun report(now: Long = data.now) = CognitiveAnalyzer().analyze(data.events, StatisticsPeriod.WEEK,
        now, zone, semantics = data.semantics)

    @Test fun identitiesVisitsAndMaterialStaySeparate() {
        assertEquals(588, data.works.size)
        assertEquals(672, data.events.count { it.sourceType == "accessibility" })
        assertEquals(98, data.events.count { it.sourceType == "screen_ocr" })
        assertEquals(2016, data.samples.size)
        assertTrue(data.events.all { it.workKey!!.startsWith("synthetic:") })
        assertTrue(data.semantics.all { s -> s.validFor(data.events.single { it.id == s.eventId }) })
        assertEquals(data.events.size, data.semantics.size)
    }

    @Test fun bothWeeksHaveCorrectDistinctCountsAndVisibleDuration() {
        val expectedDuration = listOf(29_118_000L, 29_892_000L)
        for (phase in 0..1) {
            val now = data.now - (1 - phase) * 7 * 86_400_000L
            val stats = calculateStatistics(data.events, StatisticsPeriod.WEEK, now, zone)
            assertEquals(294, stats.total)
            assertEquals(336, stats.visitCount)
            assertEquals(5, stats.appCount)
            assertEquals(0, stats.unidentifiedWorks)
            assertEquals(0, stats.unknownDurationRecords)
            assertEquals(expectedDuration[phase], stats.observedDurationMillis)
            assertEquals(294, stats.apps.sumOf { it.count })
            val timeline = ViewpointTimeline.build(data.events, StatisticsPeriod.WEEK, now, zone, semantics = data.semantics)
            assertTrue(timeline.all { it.items.size == 42 })
            val cards = ViewpointTimeline.details(timeline.flatMap { it.items })
            assertEquals(294, cards.size)
            assertEquals(294, cards.map { it.workIdentity }.distinct().size)
            assertTrue(cards.any { it.records.count { r -> r.sourceType == "accessibility" } == 2 })
        }
    }

    @Test fun dayAnd24HoursAgreeAndMaterialDoesNotIncreaseCount() {
        for (period in listOf(StatisticsPeriod.DAY, StatisticsPeriod.HOURS24)) {
            val all = calculateStatistics(data.events, period, data.now, zone)
            val screenOnly = calculateStatistics(data.events.filter { it.sourceType == "accessibility" }, period, data.now, zone)
            assertEquals(screenOnly, all)
            assertEquals(42, all.total)
            assertEquals(48, all.visitCount)
        }
    }

    @Test fun realExposureAnalyzerFindsNarrowingWithoutThresholdOverrides() {
        val first = report(data.now - 7 * 86_400_000L).exposure!!
        val second = report().exposure!!
        val before = first.viewpoints.single { it.topic == "就业" && it.dimension == "求职难度" }
        val after = second.viewpoints.single { it.topic == "就业" && it.dimension == "求职难度" }
        assertEquals(49, before.distribution.contacts)
        assertEquals(252, after.distribution.contacts)
        assertEquals(4.0 / 7, before.share, .00001)
        assertEquals(8.0 / 9, after.share, .00001)
        assertEquals(before.share, after.previousShare!!, .00001)
        assertTrue(after.narrowing)
        assertTrue(after.alignedApps >= 2)
        assertTrue(after.diversityChange!! < -.1)
        assertEquals("同向观点占比上升", after.title)
        assertEquals(after.key, second.primary!!.key)
        println("SYNTHETIC_EXPOSURE before=${before.share} after=${after.share} change=${after.shareChange} diversity=${after.diversityChange} apps=${after.alignedApps}")
    }

    @Test fun anxietyAnnotationsKeepHolderNegationAndViewerSeparate() {
        val first = data.emotions.take(294)
        val last = data.emotions.drop(294)
        fun anxiety(rows: List<com.emerge.app.data.SyntheticEmotion>) = rows.count {
            it.expression == "焦虑" && it.attribution in setOf("affirmed", "reported")
        }
        assertEquals(131, anxiety(first))
        assertEquals(187, anxiety(last))
        assertTrue(data.emotions.any { it.attribution == "negated" })
        assertTrue(data.emotions.any { it.attribution == "reported" && it.holder == "虚构朋友" })
        assertTrue(data.emotions.all { it.holder.startsWith("虚构") && it.cause.isNotBlank() })
    }
}
