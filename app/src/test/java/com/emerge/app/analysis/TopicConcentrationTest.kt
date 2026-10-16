package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class TopicConcentrationTest {
    private fun work(id: Int, stance: String = "减少", subject: String = "岗位", dimension: String = "数量",
        conditions: String = "今年", uncertain: Boolean = false, key: String = "work-$id"): ViewpointItem {
        val event = BrowseEvent(id.toLong(), "reader", 1_790_000_000_000L, 0, "岗位数量减少", workKey = key)
        val claim = ViewpointClaim(event.visibleText, "求职", subject, dimension, stance, conditions,
            uncertain, "structured_model", event, "UTC")
        return ViewpointItem(event, claim.label, claim.quote, false, listOf(claim), "structured_model")
    }

    @Test fun primaryShareUsesDistinctWorksAndSplitsMultipleStances() {
        val items = (1..30).map { work(it) }.toMutableList()
        val one = items[0]
        items[0] = one.copy(claims = one.claims + one.claims.single().copy(stance = "增加"))
        items += work(31, key = "work-1")
        val topic = TopicConcentrations.analyze(items).single()
        assertEquals(30, topic.works)
        assertTrue(topic.scope!!.sufficient)
        assertEquals(29.5 / 30, topic.scope.share, .00001)
    }

    @Test fun unrelatedDimensionsAndConditionsDoNotCreateOneHighConfidenceScope() {
        val dimensions = (1..20).map { work(it) } + (21..40).map { work(it, dimension = "薪酬") }
        assertFalse(TopicConcentrations.analyze(dimensions).single().scope!!.sufficient)
        val conditions = (1..20).map { work(it) } + (21..40).map { work(it, conditions = "去年") }
        assertEquals(20, TopicConcentrations.analyze(conditions).single().scope!!.classified)
    }

    @Test fun smallOrLowCoverageSamplesCannotClaimConcentration() {
        assertFalse(TopicConcentrations.analyze((1..29).map { work(it) }).single().scope!!.sufficient)
        val items = (1..30).map { work(it) } + (31..60).map { work(it, uncertain = true) }
        val topic = TopicConcentrations.analyze(items).single()
        assertEquals(30, topic.classified)
        assertFalse(topic.scope!!.sufficient)
    }

    @Test fun eachTopicHasOwnModuleAndUnconfirmedTopicsRemainVisible() {
        val first = work(1)
        val other = work(2).let { it.copy(claims = it.claims.map { claim -> claim.copy(topic = "教育", uncertain = true) }) }
        val topics = TopicConcentrations.analyze(listOf(first, other))
        assertEquals(setOf("求职", "教育"), topics.map { it.topic }.toSet())
        assertEquals(0, topics.single { it.topic == "教育" }.classified)
    }
}
