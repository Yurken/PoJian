package com.emerge.app.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureSessionTrackerTest {
    private val tracker = CaptureSessionTracker()
    private fun sample(text: String = "A visible news story", elapsed: Long = 1000L, packageName: String = "com.reader") =
        tracker.sample(packageName, text, elapsed + 100_000, elapsed, 32)

    @Test fun consecutiveSamplesUpdateOneRowAndAccumulateVisibleTime() {
        val first = sample()
        assertFalse(first.continuing)
        tracker.saved(first, 7L)
        val next = sample(elapsed = 3000)
        assertTrue(next.continuing)
        assertEquals(7L, next.event.id)
        assertEquals(2000L, next.event.durationMillis)
        assertEquals(2, next.event.sampleCount)
    }

    @Test fun deletedSessionDoesNotBecomeANewInsertOnPeriodicRefresh() {
        tracker.saved(sample(), 7L)
        val missing = sample(elapsed = 3000)
        tracker.saved(missing, null)
        val next = sample(elapsed = 20_000)
        assertTrue(next.continuing)
        assertEquals(7L, next.event.id)
        assertEquals(2000L, next.event.durationMillis)
        assertFalse(sample(text = "A different article", elapsed = 21_000).continuing)
    }

    @Test fun invalidatedNewSampleAlsoStaysDiscardedUntilContentChanges() {
        tracker.saved(sample(), null)
        val next = sample(elapsed = 3000)
        assertTrue(next.continuing)
        assertEquals(-1L, next.event.id)
    }

    @Test fun resetInvalidatesInFlightSampleAndReleasesPreviousBody() {
        val old = sample()
        tracker.reset()
        assertFalse(tracker.isCurrent(old))
        tracker.saved(old, 7L)
        assertNull(tracker.packageName)
        assertFalse(sample(elapsed = 3000).continuing)
    }

    @Test fun timeGapAndApplicationChangesStartNewSessions() {
        tracker.saved(sample(), 7L)
        assertFalse(sample(elapsed = 8000).continuing)
        assertFalse(sample(elapsed = 3000, packageName = "com.other").continuing)
        assertFalse(sample(elapsed = 900).continuing)
    }
}
