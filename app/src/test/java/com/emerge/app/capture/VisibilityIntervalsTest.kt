package com.emerge.app.capture

import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.calculateStatistics
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class VisibilityIntervalsTest {
    @Test fun repeatedWindowQueriesDoNotReuseIntervalsFromEditedRecordsOrOtherWindows() {
        val window = ObservationWindow(1000, 6000, ZoneId.of("UTC"))
        val original = BrowseEvent(7, "reader", 1000, 32, "原文", endTimestamp = 5000,
            durationMillis = 4000, sampleCount = 2, visibilityIntervals = "v1;1000,5000,4000,m")
        repeat(3) {
            assertEquals(4000L, window.durationMillis(original))
            assertEquals(VisibilityRegion.MAIN, window.slices(original).single().region)
        }
        val edited = original.copy(durationMillis = 2000, visibilityIntervals = "v1;3000,5000,2000,c")
        assertEquals(2000L, window.durationMillis(edited))
        assertEquals(VisibilityRegion.COMMENTS, window.slices(edited).single().region)
        val damaged = edited.copy(visibilityIntervals = "invalid")
        repeat(3) {
            assertNull(window.durationMillis(damaged))
            assertEquals(0L, window.slices(damaged).sumOf { it.durationMillis })
        }
        val later = ObservationWindow(4000, 6000, ZoneId.of("Asia/Shanghai"))
        assertEquals(1000L, later.durationMillis(original))
        assertEquals(8, later.slices(original).single().hour)
        assertEquals(4000L, window.durationMillis(original))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val hour = ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun hint(page: WorkPage) = WorkHint("同一作品的足够长的配文", "作者甲", page = page)

    @Test fun bodyCommentsAndReturnPreserveOneVisitAndConserveAllRegionTime() {
        val tracker = CaptureSessionTracker()
        var elapsed = 1000L
        var wall = hour - 10_000L
        fun sample(page: WorkPage, text: String): CaptureSample {
            val sample = tracker.sample("reader", text, wall, elapsed, 4096, hint(page))
            tracker.saved(sample, 7)
            wall += 2000; elapsed += 2000
            return sample
        }
        val first = sample(WorkPage.VIDEO, "原视频配文")
        sample(WorkPage.VIDEO, "原视频配文")
        sample(WorkPage.COMMENTS, "新出现的评论")
        val comments = sample(WorkPage.COMMENTS, "滚动后的评论")
        assertEquals(WorkPage.VIDEO, comments.hint.page) // work identity still inherits the original video
        assertEquals(VisibilityRegion.COMMENTS, comments.region) // screen region does not inherit it
        sample(WorkPage.VIDEO, "原视频配文")
        val last = sample(WorkPage.VIDEO, "原视频配文")
        assertTrue(last.continuing)
        assertEquals(first.event.workKey, last.event.workKey)
        assertEquals(7L, last.event.id)
        val intervals = VisibilityIntervals.decode(last.event.visibilityIntervals!!)!!
        assertEquals(10_000L, VisibilityIntervals.duration(intervals))
        assertEquals(last.event.durationMillis, VisibilityIntervals.duration(intervals))
        assertEquals(4000L, intervals.filter { it.region == VisibilityRegion.MAIN }.sumOf { it.durationMillis })
        assertEquals(2000L, intervals.filter { it.region == VisibilityRegion.COMMENTS }.sumOf { it.durationMillis })
        assertEquals(4000L, intervals.filter { it.region == VisibilityRegion.UNLOCATED }.sumOf { it.durationMillis })
    }

    @Test fun pausedWindowAcrossHourNeverSpreadsItsGapIntoTheHourlyTotals() {
        val tracker = CaptureSessionTracker()
        fun sample(at: Long, elapsed: Long) = tracker.sample("reader", "原视频配文", at, elapsed, 32, hint(WorkPage.VIDEO))
        tracker.saved(sample(hour - 4000, 1000), 7)
        tracker.saved(sample(hour - 2000, 3000), 7)
        tracker.pause()
        val resumed = sample(hour + 8000, 13_000)
        assertTrue(resumed.continuing)
        assertEquals(2000L, resumed.event.durationMillis)
        tracker.saved(resumed, 7)
        val last = sample(hour + 10_000, 15_000).event
        val window = ObservationWindow.forPeriod(StatisticsPeriod.DAY, hour + 20_000, zone)
        val slices = window.slices(last)
        assertEquals(2000L, slices.filter { it.hour == 11 }.sumOf { it.durationMillis })
        assertEquals(2000L, slices.filter { it.hour == 12 }.sumOf { it.durationMillis })
        assertEquals(4000L, slices.sumOf { it.durationMillis })
        assertTrue(slices.none { it.start < hour + 8000 && it.end > hour - 2000 })
        val stats = calculateStatistics(listOf(last), StatisticsPeriod.DAY, hour + 20_000, zone)
        assertEquals(2000L, stats.trend[11].durationMillis)
        assertEquals(2000L, stats.trend[12].durationMillis)
        assertEquals(4000L, stats.observedDurationMillis)
    }

    @Test fun exactRegionIntervalCrossingHourIsClippedAndConservesItsDuration() {
        val encoded = VisibilityIntervals.encode(listOf(VisibilityInterval(hour - 2000, hour + 3000,
            5000, VisibilityRegion.COMMENTS)))
        val event = BrowseEvent(7, "reader", hour - 2000, 32, "评论", endTimestamp = hour + 3000,
            durationMillis = 5000, sampleCount = 3, visibilityIntervals = encoded)
        val window = ObservationWindow(hour - 1000, hour + 1000, zone)
        val slices = window.slices(event)
        assertEquals(2000L, slices.sumOf { it.durationMillis })
        assertEquals(listOf(11, 12), slices.map { it.hour })
        assertTrue(slices.all { it.region == VisibilityRegion.COMMENTS })
    }

    @Test fun missingGenericPageClassificationKeepsScreenTimeUnlocated() {
        val tracker = CaptureSessionTracker()
        tracker.saved(tracker.sample("reader", "完全相同的未知页面", hour, 1000, 32), 7)
        val last = tracker.sample("reader", "完全相同的未知页面", hour + 2000, 3000, 32)
        assertEquals(VisibilityRegion.UNLOCATED,
            VisibilityIntervals.decode(last.event.visibilityIntervals!!)!!.single().region)
    }

    @Test fun clockChangeSplitsTheVisitWithoutStretchingOrReidentifyingItsWork() {
        val tracker = CaptureSessionTracker()
        val first = tracker.sample("reader", "原视频配文", hour, 1000, 32, hint(WorkPage.VIDEO))
        tracker.saved(first, 7)
        val changed = tracker.sample("reader", "原视频配文", hour + 300_000, 3000, 32, hint(WorkPage.VIDEO))
        assertFalse(changed.continuing)
        assertEquals(first.event.workKey, changed.event.workKey)
        assertEquals(0L, changed.event.durationMillis)
        assertEquals("", changed.event.visibilityIntervals)
        tracker.saved(changed, 8)
        val stable = tracker.sample("reader", "原视频配文", hour + 302_000, 5000, 32, hint(WorkPage.VIDEO))
        assertTrue(stable.continuing)
        assertEquals(2000L, stable.event.durationMillis)
        assertEquals(hour + 300_000, VisibilityIntervals.decode(stable.event.visibilityIntervals!!)!!.single().start)
    }

    @Test fun boundedIntervalCapacityStartsAnotherVisitAndPreservesTheNewMeasuredInterval() {
        val tracker = CaptureSessionTracker()
        val first = tracker.sample("reader", "原视频配文", hour, 1000, 32, hint(WorkPage.VIDEO))
        val intervals = List(VisibilityIntervals.MAX_INTERVALS) { index ->
            VisibilityInterval(hour + index, hour + index + 1, 1,
                if (index % 2 == 0) VisibilityRegion.MAIN else VisibilityRegion.COMMENTS)
        }
        val endpoint = hour + intervals.size
        val seeded = first.copy(event = first.event.copy(endTimestamp = endpoint,
            durationMillis = intervals.size.toLong(), visibilityIntervals = VisibilityIntervals.encode(intervals)),
            elapsedRealtime = 1000 + intervals.size.toLong(), region = VisibilityRegion.COMMENTS)
        tracker.saved(seeded, 7)
        val next = tracker.sample("reader", "原视频配文", endpoint + 2000,
            seeded.elapsedRealtime + 2000, 32, hint(WorkPage.VIDEO))
        assertFalse(next.continuing)
        assertEquals(first.event.workKey, next.event.workKey)
        assertEquals(endpoint, next.event.timestamp)
        assertEquals(2000L, next.event.durationMillis)
        assertEquals(2000L, VisibilityIntervals.duration(VisibilityIntervals.decode(next.event.visibilityIntervals!!)!!))
    }

    @Test fun deletedVisibleWorkStaysDiscardedAcrossForwardAndBackwardClockChanges() {
        val tracker = CaptureSessionTracker()
        tracker.saved(tracker.sample("reader", "原视频配文", hour, 1000, 32, hint(WorkPage.VIDEO)), 7)
        val missing = tracker.sample("reader", "原视频配文", hour + 2000, 3000, 32, hint(WorkPage.VIDEO))
        tracker.saved(missing, null)
        val forward = tracker.sample("reader", "原视频配文", hour + 302_000, 5000, 32, hint(WorkPage.VIDEO))
        assertTrue(forward.continuing)
        assertEquals(missing.event, forward.event)
        tracker.saved(forward, null)
        val backward = tracker.sample("reader", "原视频配文", hour - 300_000, 7000, 32, hint(WorkPage.VIDEO))
        assertTrue(backward.continuing)
        assertEquals(missing.event, backward.event)
        val changed = tracker.sample("reader", "不同作者的不同作品", hour - 298_000, 9000, 32,
            WorkHint("另一个足够长的不同作品标题", "作者乙", page = WorkPage.VIDEO))
        assertFalse(changed.continuing)
        assertEquals(0L, changed.event.id)
    }

    @Test fun deletedVisibleWorkWithFullIntervalsNeverSplitsIntoANewInsert() {
        val tracker = CaptureSessionTracker()
        val first = tracker.sample("reader", "原视频配文", hour, 1000, 32, hint(WorkPage.VIDEO))
        val intervals = List(VisibilityIntervals.MAX_INTERVALS) { index ->
            VisibilityInterval(hour + index, hour + index + 1, 1,
                if (index % 2 == 0) VisibilityRegion.MAIN else VisibilityRegion.COMMENTS)
        }
        val endpoint = hour + intervals.size
        val missing = first.copy(event = first.event.copy(id = 7, endTimestamp = endpoint,
            durationMillis = intervals.size.toLong(), visibilityIntervals = VisibilityIntervals.encode(intervals)),
            elapsedRealtime = 1000 + intervals.size.toLong(), region = VisibilityRegion.COMMENTS)
        tracker.saved(missing, null)
        val next = tracker.sample("reader", "原视频配文", endpoint + 2000,
            missing.elapsedRealtime + 2000, 32, hint(WorkPage.VIDEO))
        assertTrue(next.continuing)
        assertEquals(missing.event, next.event)
        assertEquals(7L, next.event.id)
    }

    @Test fun malformedOrInconsistentIntervalsNeverUseLegacySpanEstimation() {
        val base = BrowseEvent(7, "reader", hour - 10_000, 32, "原文", endTimestamp = hour + 10_000,
            durationMillis = 4000, sampleCount = 3)
        val window = ObservationWindow(hour - 20_000, hour + 20_000, zone)
        for (encoded in listOf("broken", "v1;1,2,1,x", "", "v1;$hour,${hour + 2000},2000,m")) {
            val malformed = base.copy(visibilityIntervals = encoded)
            assertFalse(window.durationKnown(malformed))
            assertNull(window.durationMillis(malformed))
            assertEquals(0L, window.slices(malformed).sumOf { it.durationMillis })
        }
        assertTrue(window.durationKnown(base))
        assertEquals(4000L, window.durationMillis(base))
    }

    @Test fun adjacentSameRegionsCoalesceAndInvalidMergesAreRejected() {
        val first = VisibilityIntervals.encode(listOf(VisibilityInterval(1000, 3000, 2000, VisibilityRegion.MAIN)))
        val second = VisibilityIntervals.encode(listOf(VisibilityInterval(3000, 5000, 2000, VisibilityRegion.MAIN)))
        assertEquals(listOf(VisibilityInterval(1000, 5000, 4000, VisibilityRegion.MAIN)),
            VisibilityIntervals.decode(VisibilityIntervals.merge(first, second)!!))
        assertNull(VisibilityIntervals.merge(second, first))
        assertNull(VisibilityIntervals.append("bad", VisibilityInterval(5000, 7000, 2000, VisibilityRegion.MAIN)))
    }
}
