package com.emerge.app.ui

import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class SwissHistoryPageTest {
    private fun records(count: Int) = (1..count).map {
        BrowseEvent(it.toLong(), if (it % 2 == 0) "reader" else "other", it * 1000L, 1, "原文 $it")
    }

    @Test fun pagesCoverEveryRecordOnceInNewestFirstOrder() {
        val ordered = swissHistoryRecords(records(45), emptyList(), null, null, null)
        val pages = (0..2).map { swissHistoryPage(ordered, it) }
        assertEquals(listOf(20, 20, 5), pages.map { it.records.size })
        assertTrue(pages.all { it.count == 3 })
        assertEquals(ordered, pages.flatMap { it.records })
        assertEquals((45L downTo 26L).toList(), pages.first().records.map { it.id })
    }

    @Test fun paginationUsesTheFilteredTotal() {
        val filtered = swissHistoryRecords(records(45), emptyList(), null, "reader", null)
        assertEquals(22, filtered.size)
        assertEquals(2, swissHistoryPage(filtered, 0).count)
        assertEquals(listOf(4L, 2L), swissHistoryPage(filtered, 1).records.map { it.id })
    }

    @Test fun deletingTheOnlyRecordOnTheLastPageClampsToThePreviousPage() {
        val ordered = records(41)
        assertEquals(2, swissHistoryPage(ordered, 2).index)
        val afterDeletion = swissHistoryPage(ordered.dropLast(1), 2)
        assertEquals(1, afterDeletion.index)
        assertEquals(2, afterDeletion.count)
        assertEquals(ordered.subList(20, 40), afterDeletion.records)
    }

    @Test fun emptyExactBoundaryAndOutOfRangePagesRemainValid() {
        val empty = swissHistoryPage(emptyList(), 8)
        assertEquals(0, empty.index)
        assertEquals(1, empty.count)
        assertTrue(empty.records.isEmpty())
        assertEquals(1, swissHistoryPage(records(20), 0).count)
        assertEquals(0, swissHistoryPage(records(20), -1).index)
        assertEquals(1, swissHistoryPage(records(21), Int.MAX_VALUE).index)
    }
}
