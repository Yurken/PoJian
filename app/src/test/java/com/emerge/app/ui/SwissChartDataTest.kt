package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SwissChartDataTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = Instant.parse("2026-10-15T16:00:00Z").toEpochMilli()

    private fun item(id: Long, stance: String, topic: String = "就业"): ViewpointItem {
        val record = BrowseEvent(id, "reader", start + id, 32, "岗位调整", workKey = "work:$id")
        val claim = ViewpointClaim(record.visibleText, topic, "企业", "岗位", stance,
            record = record, analysisSource = "structured_model")
        return ViewpointItem(record, claim.label, claim.quote, false, listOf(claim), claim.analysisSource)
    }

    @Test fun selectedDayUsesCalendarDateAndKeepsUnknownWorksOutOfNeutralCounts() {
        val bucket = ViewpointBucket("今天", start, start + 60_000L,
            listOf(item(1, "支持"), item(2, "尚待判断")), null, 0, 0)
        val day = swissStanceDay(bucket, null, zone)
        assertEquals(LocalDate.of(2026, 10, 16), day.date)
        assertEquals(1f, day.value!!, .001f)
        assertEquals(1, day.counts.getValue(ViewpointStance.SUPPORT))
        assertEquals(1, day.counts.getValue(ViewpointStance.UNKNOWN))
        assertEquals(0, day.counts.getValue(ViewpointStance.NEUTRAL))
    }

    @Test fun selectedTopicKeepsItsOwnCountsAndUnknownOnlyDayHasNoPoint() {
        val bucket = ViewpointBucket("今天", start, start + 60_000L,
            listOf(item(1, "支持", "科技"), item(2, "尚待判断")), null, 0, 0)
        val day = swissStanceDay(bucket, "就业", zone)
        assertNull(day.value)
        assertEquals(0, day.counts.getValue(ViewpointStance.SUPPORT))
        assertEquals(1, day.counts.getValue(ViewpointStance.UNKNOWN))
    }

    @Test fun chartSelectionClampsToActualEdgesAndChoosesNearestDay() {
        assertEquals(0, swissChartSelection(-20f, 300f, 7, edgePoints = true))
        assertEquals(3, swissChartSelection(150f, 300f, 7, edgePoints = true))
        assertEquals(6, swissChartSelection(330f, 300f, 7, edgePoints = true))
        assertEquals(0, swissChartSelection(0f, 300f, 25))
        assertEquals(24, swissChartSelection(300f, 300f, 25))
        assertNull(swissChartSelection(100f, 300f, 0))
        assertNull(swissChartSelection(100f, 0f, 7))
    }
}
