package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class RecentContentWordsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun event(id: Long, at: Long = now - 1000) = BrowseEvent(id, "reader", at, 32,
        "先把岗位要求和自己的项目经历逐项对上。点赞 评论 收藏")
    private fun result(event: BrowseEvent, topic: String = "职业发展", subject: String = "求职准备",
        at: Long = now) = EventSemantics(event.id, event.visibleText, at,
        listOf(ArgumentUnit("先把岗位要求和自己的项目经历逐项对上。", topic, subject, "", "")))
    private fun words(events: List<BrowseEvent>, results: List<EventSemantics>, period: StatisticsPeriod = StatisticsPeriod.DAY) =
        recentContentWords(events, period, now, results, zone)

    @Test fun usesModelConceptsEvenWhenAbsentFromOriginalAndCountsOncePerRecord() {
        val a = event(1)
        val b = event(2)
        val model = result(a)
        val words = words(listOf(a, a, b), listOf(model.copy(claims = model.claims + model.claims), result(b)))
        assertEquals(setOf(ContentWord("职业发展", 2), ContentWord("求职准备", 2)), words.toSet())
    }

    @Test fun missingInvalidOrDeletedResultsNeverFallBackToRawText() {
        val a = event(1)
        assertTrue(words(listOf(a), emptyList()).isEmpty())
        assertTrue(words(listOf(a.copy(visibleText = "修订后的内容")), listOf(result(a))).isEmpty())
        assertTrue(words(emptyList(), listOf(result(a))).isEmpty())
        assertTrue(words(listOf(a), listOf(result(a).copy(claims = emptyList()))).isEmpty())
    }

    @Test fun newestVersionWinsAndInvalidNewestDoesNotResurrectOlderResult() {
        val a = event(1)
        val old = result(a, "旧主题", "旧对象", now - 1)
        val latest = result(a, "新主题", "新对象")
        assertEquals(setOf("新主题", "新对象"), words(listOf(a), listOf(latest, old)).map { it.text }.toSet())
        assertTrue(words(listOf(a), listOf(old, latest.copy(inputText = "过期输入"))).isEmpty())
    }

    @Test fun filtersByBrowsingWindowRatherThanAnalysisTimeAndSkipsMissingLabels() {
        val yesterday = event(1, now - 86_400_000)
        val future = event(2, now + 1000)
        val current = event(3)
        val events = listOf(yesterday, future, current)
        val results = listOf(result(yesterday), result(future), result(current, "未知", ""))
        assertTrue(words(events, results).isEmpty())
        assertEquals(2, words(events, results, StatisticsPeriod.WEEK).size)
        assertTrue(words(events, results, StatisticsPeriod.WEEK).all { it.recordCount == 1 })
    }
}
