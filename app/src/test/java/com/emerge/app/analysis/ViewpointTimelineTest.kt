package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ViewpointTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun event(id: Long, text: String, timestamp: Long = now - 60000, work: String = "work-$id") =
        BrowseEvent(id = id, packageName = "reader", timestamp = timestamp, eventType = 4096,
            visibleText = text, workKey = work, sampleCount = 1, endTimestamp = timestamp)

    @Test fun labelsRetainSpecificViewpointsAndNegation() {
        assertEquals("就业焦虑", ViewpointTimeline.label("找工作很难，很多人因此感到焦虑"))
        assertEquals("女性独立", ViewpointTimeline.label("女性应该经济独立并自主生活"))
        assertEquals("女性不应该独立生活", ViewpointTimeline.label("女性不应该独立生活"))
        assertEquals("人工智能需要更多可靠的证据", ViewpointTimeline.label("人工智能需要更多可靠的证据"))
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
}
