package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class SwissTopicTimelineDataTest {
    private fun work(id: Long, topic: String, stance: String): ViewpointItem {
        val record = BrowseEvent(id, "reader", id * 1000, 32, "这是一段完整的原文$id", workKey = "work:$id")
        val claim = ViewpointClaim(record.visibleText, topic, "讨论对象", "评价", stance,
            record = record, analysisSource = "structured_model")
        return ViewpointItem(record, claim.label, claim.quote, false, listOf(claim), "structured_model", workIdentity = "work:$id")
    }
    private fun bucket(index: Int, groups: List<TopicTimeGroup>) = TopicTimeBucket("$index", index * 1000L,
        (index + 1) * 1000L, groups.flatMap { it.works }.distinctBy { it.workIdentity }, groups)

    @Test fun revisitsCountOnceForThePeriodButRemainInEveryTimeCell() {
        val first = work(1, "教育", "支持")
        val second = work(2, "教育", "反对")
        val rows = swissTopicTimelineData(listOf(
            bucket(0, listOf(TopicTimeGroup("教育", listOf(first)))),
            bucket(1, listOf(TopicTimeGroup("教育", listOf(first, second)))))).rows
        val row = rows.single()
        assertEquals(2, row.workCount)
        assertEquals(listOf(1, 2), row.counts)
        assertEquals(1, row.stances.single { it.stance == ViewpointStance.SUPPORT }.count)
        assertEquals(1, row.stances.single { it.stance == ViewpointStance.QUESTION }.count)
    }

    @Test fun multiTopicWorksDoNotLeakStancesBetweenTopicRowsOrLoseEmptyCells() {
        val education = work(1, "教育", "支持")
        val tech = work(1, "科技", "反对")
        val combined = education.copy(claims = education.claims + tech.claims)
        val data = swissTopicTimelineData(listOf(bucket(0, listOf(
            TopicTimeGroup("教育", listOf(combined)), TopicTimeGroup("科技", listOf(combined)))), bucket(1, emptyList())))
        assertEquals(2, data.rows.size)
        data.rows.forEach { assertEquals(1, it.workCount); assertEquals(listOf(1, 0), it.counts) }
        assertEquals(ViewpointStance.SUPPORT, data.rows.single { it.title == "教育" }.stances.single().stance)
        assertEquals(ViewpointStance.QUESTION, data.rows.single { it.title == "科技" }.stances.single().stance)
        assertTrue(swissTopicTimelineData(emptyList()).rows.isEmpty())
    }
}
