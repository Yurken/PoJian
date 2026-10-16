package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class TopicTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 17, 12, 30, 0, 0, zone).toInstant().toEpochMilli()
    private fun event(id: Long, at: Long = now - 60_000, work: String = "post:$id") = BrowseEvent(
        id = id, timestamp = at, packageName = "reader", eventType = 4096,
        visibleText = "教育需要长期投入，科技应该服务生活", workKey = work,
        sampleCount = 1, endTimestamp = at)
    private fun semantics(event: BrowseEvent, vararg topics: String) = EventSemantics(event.id,
        event.visibleText, now, topics.map { topic -> ArgumentUnit("教育需要长期投入", topic,
            "社会", "投入", "需要", uncertain = false) })

    // Counting topic memberships as works would turn the single retained work into two.
    @Test fun multiTopicWorkRetainsBothTopicsWithoutInflatingIndependentWorks() {
        val first = event(1)
        val revisit = event(2, now - 30_000, first.workKey!!)
        val bucket = TopicTimeline.build(listOf(first, revisit), StatisticsPeriod.DAY, now, zone,
            semantics = listOf(semantics(first, "教育", "科技"), semantics(revisit, "教育"))).first { it.works.isNotEmpty() }
        assertEquals(1, bucket.works.size)
        assertEquals(setOf("教育", "科技"), bucket.topics.map { it.title }.toSet())
        assertEquals(listOf(1, 1), bucket.topics.map { it.works.size })
        assertEquals(setOf(1L, 2L), bucket.works.single().records.map { it.id }.toSet())
    }

    @Test fun measuredVisitCrossingHourBoundaryAppearsInEachTouchedHour() {
        val at = ZonedDateTime.of(2026, 10, 17, 10, 59, 0, 0, zone).toInstant().toEpochMilli()
        val row = event(1, at).copy(endTimestamp = at + 120_000, durationMillis = 120_000)
        val buckets = TopicTimeline.build(listOf(row), StatisticsPeriod.DAY, now, zone,
            semantics = listOf(semantics(row, "教育")))
        assertEquals(listOf("10", "11"), buckets.filter { it.works.isNotEmpty() }.map { it.label })
        assertEquals(listOf(1, 1), buckets.filter { it.works.isNotEmpty() }.map { it.topics.single().works.size })
        assertEquals(24, buckets.size)
    }

    @Test fun weekUsesCalendarDaysAndRollingHourExcludesEarlierWorksAndStandaloneAudio() {
        val today = event(1)
        val yesterday = event(2, now - 86_400_000)
        val audio = event(3).copy(sourceType = "audio_transcript")
        val rows = listOf(today, yesterday, audio)
        val model = rows.map { semantics(it, "教育") }
        val week = TopicTimeline.build(rows, StatisticsPeriod.WEEK, now, zone, semantics = model)
        assertEquals(7, week.size)
        assertEquals(listOf(2L), week[5].works.map { it.record.id })
        assertEquals(listOf(1L), week[6].works.map { it.record.id })
        val hour = TopicTimeline.build(rows, StatisticsPeriod.HOUR, now, zone, semantics = model)
        assertEquals(listOf(1L), hour.flatMap { it.works }.map { it.record.id }.distinct())
    }

    @Test fun revisedAndDeletedWorksCannotKeepOldModelTopic() {
        val row = event(1)
        val old = semantics(row, "旧主题")
        val revised = row.copy(visibleText = "菜单")
        val fresh = TopicTimeline.build(listOf(revised), StatisticsPeriod.DAY, now, zone, semantics = listOf(old))
        assertEquals(listOf("未识别"), fresh.flatMap { it.topics }.map { it.title })
        assertTrue(TopicTimeline.build(emptyList(), StatisticsPeriod.DAY, now, zone,
            semantics = listOf(old)).all { it.works.isEmpty() && it.topics.isEmpty() })
    }

    @Test fun everyTopicRemainsAvailableAndRepeatedTopicClaimsDoNotDuplicateWork() {
        val rows = (1L..13L).map { event(it) }
        val model = rows.map { semantics(it, "主题${it.id}", "主题${it.id}") }
        val bucket = TopicTimeline.build(rows, StatisticsPeriod.DAY, now, zone, semantics = model)
            .first { it.works.isNotEmpty() }
        assertEquals(13, bucket.topics.size)
        assertEquals(13, bucket.works.size)
        assertTrue(bucket.topics.all { it.works.size == 1 })
    }
}
