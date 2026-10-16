package com.emerge.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalStorageRetentionPolicyTest {
    @Test fun capacityOnlyHasNoTimeCutoffAndPeriodsUseRollingDays() {
        assertNull(storageRetentionCutoff(0, 10_000_000_000L))
        assertEquals(9_395_200_000L, storageRetentionCutoff(7, 10_000_000_000L))
        assertEquals(7_408_000_000L, storageRetentionCutoff(30, 10_000_000_000L))
        assertEquals(2_224_000_000L, storageRetentionCutoff(90, 10_000_000_000L))
    }

    @Test fun invalidOrExtremeClocksCannotBecomeAFutureDestructiveCutoff() {
        assertEquals(Long.MIN_VALUE, storageRetentionCutoff(90, Long.MIN_VALUE))
        assertThrows(IllegalArgumentException::class.java) { storageRetentionCutoff(-1, 1000) }
        assertThrows(IllegalArgumentException::class.java) { storageRetentionCutoff(1, 1000) }
    }

    @Test fun onlyShorteningRetentionRequiresCleanupConfirmation() {
        assertTrue(shortensStorageRetention(0, 7))
        assertTrue(shortensStorageRetention(90, 30))
        assertTrue(shortensStorageRetention(30, 7))
        assertFalse(shortensStorageRetention(7, 30))
        assertFalse(shortensStorageRetention(7, 0))
        assertFalse(shortensStorageRetention(30, 30))
        assertFalse(shortensStorageRetention(0, 0))
    }
}
