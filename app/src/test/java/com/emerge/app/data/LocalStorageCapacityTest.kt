package com.emerge.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalStorageCapacityTest {
    @Test fun customDecimalGigabytesPreserveTheirActualByteLimit() {
        assertEquals(5_000_000_000L, parseStorageLimitGb("5"))
        assertEquals(100_000_000L, parseStorageLimitGb("0.1"))
        assertEquals(750_000_000L, parseStorageLimitGb(" .75 "))
        assertEquals(1_234_567_891L, parseStorageLimitGb("1.234567891"))
        assertEquals("1.234567891", storageLimitGbText(1_234_567_891L))
        assertEquals("5", storageLimitGbText(5_000_000_000L))
    }

    @Test fun invalidAndOverflowingLimitsNeverBecomeDestructiveSmallValues() {
        listOf("", "0", "-5", "0.099", "NaN", "Infinity", "1e10", "9223372036.854775808", "1.0000000001")
            .forEach { assertNull(it, parseStorageLimitGb(it)) }
        assertEquals(Long.MAX_VALUE, parseStorageLimitGb("9223372036.854775807"))
    }

    @Test fun reusableDatabasePagesDoNotCountAsRetainedData() {
        assertEquals(41_460L, retainedStorageBytes(100, 90, 4096, 500))
        assertEquals(500L, retainedStorageBytes(100, 100, 4096, 500))
        assertEquals(Long.MAX_VALUE, retainedStorageBytes(Long.MAX_VALUE, 0, 4096, 500))
    }

    @Test fun displayUsesTheSameDecimalUnitsAsTheCapacitySetting() {
        assertEquals("5 GB", formatStorageBytes(5_000_000_000L))
        assertEquals("100 MB", formatStorageBytes(100_000_000L))
        assertEquals("1.5 KB", formatStorageBytes(1500L))
        assertEquals("0 B", formatStorageBytes(0L))
    }
}
