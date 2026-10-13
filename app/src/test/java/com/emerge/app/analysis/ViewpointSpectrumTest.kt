package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class ViewpointSpectrumTest {
    private fun item(id: Long, stance: String, confirmed: Boolean = true, source: String = "structured_model"): ViewpointItem {
        val event = BrowseEvent(id, "reader", 1_000L + id, 32, "原文$id", workKey = "work:$id")
        val claim = ViewpointClaim(event.visibleText, "科技", "端侧模型", "应用", stance,
            uncertain = !confirmed, analysisSource = source, record = event)
        return ViewpointItem(event, claim.label, claim.quote, false, listOf(claim), source, workIdentity = "work:$id")
    }
    private fun topics(vararg items: ViewpointItem) = ViewpointDistribution.topics(ViewpointTimeline.groups(items.toList()))

    @Test fun spectrumPartitionsDistinctWorksAndKeepsMultiStanceWorksSeparate() {
        val support = item(1, "支持")
        val topics = topics(support, support.copy(record = support.record.copy(id = 99)), item(2, "中立"),
            item(3, "反对"), item(4, "支持"), item(4, "反对"), item(5, "下降"))
        val shares = viewpointSpectrum(topics)
        assertEquals(ViewpointStance.entries.toSet(), shares.map { it.stance }.toSet())
        assertTrue(shares.all { it.total == 5 && it.count == 1 && it.percent == 20.0 })
        assertEquals(5, shares.flatMap { it.workKeys }.distinct().size)
        assertEquals(100.0, shares.sumOf { it.percent }, .001)
        val mixed = filterViewpointStance(topics, shares.single { it.stance == ViewpointStance.MIXED })
        assertEquals(setOf("支持", "反对"), mixed.flatMap { it.groups }.flatMap { it.items }.flatMap { it.claims }.map { it.stance }.toSet())
        assertTrue(mixed.flatMap { it.groups }.all { it.total == 5 })
    }

    @Test fun unknownAndUncertainStatementsAreNeverInventedAsNeutralOrPositive() {
        val topics = topics(item(1, "支持", confirmed = false), item(2, "支持", source = "local_rules"),
            item(3, "不反对"), item(4, "如果满足条件则支持"), item(5, ""), item(6, "支持"), item(6, "尚待验证"))
        val share = viewpointSpectrum(topics).single()
        assertEquals(ViewpointStance.UNKNOWN, share.stance)
        assertEquals(6, share.count)
        assertEquals(100.0, share.percent, .001)
    }

    @Test fun searchAndTopicFiltersChangeScopeWithoutRewritingOriginalWorkPercentages() {
        val topics = topics(item(1, "支持"), item(2, "反对"))
        val scoped = ViewpointDistribution.filter(topics, query = "原文1")
        val share = viewpointSpectrum(scoped).single()
        assertEquals(ViewpointStance.SUPPORT, share.stance)
        assertEquals(1, share.total)
        assertEquals(100.0, share.percent, .001)
        assertEquals(50.0, filterViewpointStance(scoped, share).single().groups.single().percent, .001)
        assertEquals(topics, filterViewpointStance(topics, null))
        assertTrue(viewpointSpectrum(emptyList()).isEmpty())
    }
}
