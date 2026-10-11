package com.emerge.app.monitoring

import org.junit.Assert.*
import org.junit.Test

class FrameSamplingPolicyTest {
    @Test fun waitsForStableContextAndHonorsGlobalIntervalAcrossScreens() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("screen-a", 0)
        assertFalse(policy.shouldCapture(799, 30, false, false))
        assertTrue(policy.shouldCapture(800, 30, false, false))
        policy.started(800)
        policy.observeContext("screen-b", 1000)
        assertFalse(policy.shouldCapture(30_799, 30, false, false))
        assertTrue(policy.shouldCapture(30_800, 30, false, false))
    }

    @Test fun repeatedStaticFramesBackOffButNeverBeyondTwoMinutes() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("screen", 0)
        policy.started(1000)
        policy.completed("screen", "pixels", true, 1000)
        policy.sampled("screen", true)
        assertFalse(policy.shouldCapture(60_999, 30, false, false))
        assertTrue(policy.shouldCapture(61_000, 30, false, false))
        policy.started(61_000)
        repeat(10) { policy.sampled("screen", true) }
        assertFalse(policy.shouldCapture(180_999, 30, false, false))
        assertTrue(policy.shouldCapture(181_000, 30, false, false))
        assertTrue(policy.shouldCapture(181_000, 120, false, false))
    }

    @Test fun changedPixelsAndNewContextResetBackoff() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("a", 0)
        policy.started(1000)
        repeat(2) { policy.sampled("a", true) }
        policy.sampled("a", false)
        assertTrue(policy.shouldCapture(31_000, 30, false, false))
        repeat(2) { policy.sampled("a", true) }
        policy.observeContext("subtitle-change", 31_000)
        assertFalse(policy.shouldCapture(31_799, 30, false, false))
        assertTrue(policy.shouldCapture(31_800, 30, false, false))
    }

    @Test fun manualBypassesTimingButNotCapacityOrEmptyContext() {
        val policy = FrameSamplingPolicy()
        assertFalse(policy.shouldCapture(0, 30, true, false))
        policy.observeContext("a", 0)
        policy.started(0)
        assertTrue(policy.shouldCapture(1, 30, true, false))
        assertFalse(policy.shouldCapture(1, 30, true, true))
        assertFalse(policy.shouldCapture(100_000, 30, false, true))
    }

    @Test fun onlySuccessfulCurrentFramesAreDeduplicatedWithFiniteLifetime() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("a", 0)
        assertNull(policy.previousFingerprint(1000))
        policy.completed("a", "pixels", false, 1000)
        assertNull(policy.previousFingerprint(2000))
        policy.completed("a", "pixels", true, 2000)
        assertEquals("pixels", policy.previousFingerprint(301_999))
        assertNull(policy.previousFingerprint(302_000))
        assertNull(policy.previousFingerprint(1999))
        policy.observeContext("b", 302_000)
        policy.completed("a", "old", true, 302_001)
        assertNull(policy.previousFingerprint(302_002))
    }

    @Test fun failedManualRetryClearsPreviousSuccessAndBackoff() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("a", 0)
        policy.completed("a", "pixels", true, 1000)
        repeat(2) { policy.sampled("a", true) }
        policy.started(2000)
        policy.completed("a", "pixels", false, 2000)
        assertNull(policy.previousFingerprint(2001))
        assertTrue(policy.shouldCapture(32_000, 30, false, false))
    }

    @Test fun backlogDeferralDoesNotConsumeTheNextSamplingSlot() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("a", 0)
        assertFalse(policy.shouldCapture(1000, 30, false, true))
        assertTrue(policy.shouldCapture(1001, 30, false, false))
        policy.started(1001)
        assertFalse(policy.shouldCapture(16_000, 15, false, false))
        assertTrue(policy.shouldCapture(16_001, 15, false, false))
    }

    @Test fun leavingAnAppOrChangingAuthorizationClearsDeduplication() {
        val policy = FrameSamplingPolicy()
        policy.observeContext("screen:epoch1", 0)
        policy.completed("screen:epoch1", "pixels", true, 1000)
        policy.observeContext("", 2000)
        policy.observeContext("screen:epoch1", 3000)
        assertNull(policy.previousFingerprint(4000))
        policy.completed("screen:epoch1", "pixels", true, 4000)
        policy.observeContext("screen:epoch2", 5000)
        assertNull(policy.previousFingerprint(6000))
    }
}
