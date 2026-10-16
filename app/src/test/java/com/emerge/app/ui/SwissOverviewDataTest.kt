package com.emerge.app.ui

import com.emerge.app.analysis.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SwissOverviewDataTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-16T15:30:00Z").toEpochMilli()
    private fun claim(at: Long, details: List<EvidenceFragment>) = ClaimObservation(1, at, "app", "话题", "观点",
        details.map { it.text }, "", false, Increment.NEW, null, evidenceDetails = details)
    @Test fun unknownEvidenceDoesNotBecomeNew() {
        val buckets = swissEvidenceTrend(listOf(claim(now, listOf(EvidenceFragment("未知", EvidenceType.FACT)))), now, zone)
        assertEquals(0, buckets.sumOf { it.fresh })
        assertEquals(1, buckets.sumOf { it.unknown })
    }
    @Test fun retainedMatchesCountAsRepeatedEvenWhenNewFlagExists() {
        val rows = listOf(claim(now, listOf(EvidenceFragment("数据", EvidenceType.FACT, true,
            historyMatches = listOf(EvidenceHistoryMatch(9, "数据"))))))
        val buckets = swissEvidenceTrend(rows, now, zone)
        assertEquals(1, buckets.sumOf { it.repeated })
        assertEquals(0, buckets.sumOf { it.fresh })
    }
    @Test fun rollingWindowExcludesOldAndFutureMaterial() {
        val detail = listOf(EvidenceFragment("数据", EvidenceType.FACT, true))
        val rows = listOf(claim(now - 86_400_001, detail), claim(now, detail), claim(now + 1, detail))
        assertEquals(1, swissEvidenceTrend(rows, now, zone).sumOf { it.fresh })
    }

    @Test fun selectedHourRetainsBothPartialIntervalsAndDateAcrossMidnight() {
        val buckets = swissEvidenceTrend(emptyList(), now, zone)
        assertEquals(25, buckets.size)
        assertEquals(now - 86_400_000L, buckets.first().start)
        assertEquals(Instant.parse("2026-10-15T16:00:00Z").toEpochMilli(), buckets.first().end)
        assertEquals(Instant.parse("2026-10-16T15:00:00Z").toEpochMilli(), buckets.last().start)
        assertEquals(now, buckets.last().end)
        assertEquals("10-15 23:30 – 10-16 00:00", swissEvidenceInterval(buckets.first(), zone))
        assertEquals("10-16 23:00 – 10-16 23:30", swissEvidenceInterval(buckets.last(), zone))
    }

    @Test fun adjacentHoursDoNotDoubleCountBoundaryFragments() {
        val midnight = Instant.parse("2026-10-15T16:00:00Z").toEpochMilli()
        val rows = listOf(claim(midnight, listOf(EvidenceFragment("边界", EvidenceType.FACT, true))))
        val buckets = swissEvidenceTrend(rows, now, zone)
        assertEquals(0, buckets.first().total)
        assertEquals(1, buckets[1].fresh)
        assertEquals(1, buckets.sumOf { it.total })
    }
}
