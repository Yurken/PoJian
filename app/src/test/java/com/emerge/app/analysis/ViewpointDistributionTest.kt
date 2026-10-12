package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ViewpointDistributionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 12, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun topicUnionsWorksWhilePreservingOpposingClaimsAndConditions() {
        val row = event(1, "制动存在不足；制动满足要求；18款未配置制动")
        val unknown = event(2, "菜单")
        val risk = argument("制动存在不足", "儿童骑行箱制动性能", "制动", "存在不足", "斜坡", "18款未配置制动")
        val positive = argument("制动满足要求", "儿童骑行箱制动试验", "制动", "满足要求", "平地")
        val shares = groups(listOf(row, unknown), listOf(semantic(row, risk, positive)))
        assertEquals(3, shares.size)
        val topics = ViewpointDistribution.topics(shares)
        assertEquals(2, topics.size)
        val topic = topics.single { it.title == "儿童骑行箱" }
        assertEquals(1, topic.workCount)
        assertEquals(2, topic.total)
        assertEquals(50.0, topic.percent, .001)
        assertEquals(2, topic.groups.size)
        assertEquals(setOf("存在不足", "满足要求"), topic.groups.flatMap { it.items }.flatMap { it.claims }.map { it.stance }.toSet())
        assertTrue(topic.groups.all { it.percent == 50.0 })
        assertEquals("18款未配置制动", topic.groups.flatMap { it.items }.flatMap { it.claims }.single { it.stance == "存在不足" }.evidence)
    }

    @Test fun matchingClaimsMergeSourcesAndSubjectGroupingDoesNotInventSynonymEquivalence() {
        val rows = listOf(event(1, "普通行李箱加装把手"), event(2, "普通行李箱加装把手"), event(3, "有轮子和坐垫"))
        val first = argument(rows[0].visibleText, "产品形态", "产品定义", "普通行李箱加装把手", "代步")
        val last = argument(rows[2].visibleText, "产品构成", "产品构成与用途", "有轮子和坐垫", "代步")
        val topic = ViewpointDistribution.topics(groups(rows,
            listOf(semantic(rows[0], first), semantic(rows[1], first), semantic(rows[2], last)))).single()
        assertEquals(3, topic.workCount)
        assertEquals(100.0, topic.percent, .001)
        assertEquals(listOf(2, 1), topic.groups.map { it.items.size })
        assertEquals(setOf(1L, 2L), topic.groups.first().items.map { it.record.id }.toSet())
    }

    @Test fun filtersIntersectWithoutRenormalizingDailyDenominatorOrDiscardingOtherSources() {
        val rows = listOf(event(1, "制动不足；18款未配置制动"), event(2, "制动不足；19款未配置制动"),
            event(3, "传言需要核实"), event(4, "菜单"))
        val first = argument("制动不足", "制动性能", "制动", "不足", "斜坡", "18款未配置制动")
        val doubtful = argument("传言需要核实", "信息可信度", "可信度", "不轻信", "语境未知")
            .copy(subject = "第三方说法", uncertain = true)
        val topics = ViewpointDistribution.topics(groups(rows, listOf(semantic(rows[0], first),
            semantic(rows[1], first.copy(evidence = "19款未配置制动")), semantic(rows[2], doubtful))))
        val stroller = topics.single { it.title == "儿童骑行箱" }
        val selected = ViewpointDistribution.filter(topics, stroller.key, ViewpointFilter.MODEL, "  18款 ").single()
        assertEquals(2, selected.workCount)
        assertEquals(4, selected.total)
        assertEquals(50.0, selected.percent, .001)
        assertEquals(setOf("18款未配置制动", "19款未配置制动"), ViewpointTimeline.details(selected.groups.single().items)
            .single().claims.map { it.evidence }.toSet())
        assertTrue(ViewpointDistribution.filter(topics, stroller.key, ViewpointFilter.UNCERTAIN).isEmpty())
        assertTrue(ViewpointDistribution.filter(topics, query = "不存在的词语").isEmpty())
        assertEquals("第三方说法", ViewpointDistribution.filter(topics, state = ViewpointFilter.UNCERTAIN).single().title)
        assertEquals("未识别", ViewpointDistribution.filter(topics, state = ViewpointFilter.UNKNOWN).single().title)
        assertEquals(3, ViewpointDistribution.filter(topics, state = ViewpointFilter.MODEL).sumOf { it.workCount })
        assertEquals(1, ViewpointDistribution.filter(topics, state = ViewpointFilter.LOCAL).single().workCount)
    }

    @Test fun missingAttributionStaysUncertainAndLocalLabelsStaySeparate() {
        val rows = listOf(event(1, "某款产品应该重新检验"), event(2, "女性应该经济独立并自主生活"),
            event(3, "女性不应该独立生活并自主选择"))
        val claim = argument(rows[0].visibleText, "产品检验", "", "需要检验", "").copy(subject = "")
        val topics = ViewpointDistribution.topics(groups(rows, listOf(semantic(rows[0], claim))))
        assertEquals(3, topics.size)
        val uncertain = ViewpointDistribution.filter(topics, state = ViewpointFilter.UNCERTAIN).single()
        assertEquals("产品检验", uncertain.title)
        assertFalse(uncertain.groups.single().items.single().claims.single().confirmed)
        assertEquals(setOf("女性独立", "反对女性独立"),
            ViewpointDistribution.filter(topics, state = ViewpointFilter.LOCAL).map { it.title }.toSet())
    }

    @Test fun subjectGroupingKeepsUnrelatedSubjectsAndUnattributedClaimsSeparate() {
        val row = event(1, "产品甲需检验；产品乙需检验；另有建议")
        val first = argument("产品甲需检验", "产品安全", "性能", "需要检验", "").copy(subject = "产品甲")
        val second = first.copy(quote = "产品乙需检验", subject = "产品乙")
        val third = first.copy(quote = "另有建议", subject = "", topic = "", uncertain = true)
        val topics = ViewpointDistribution.topics(groups(listOf(row), listOf(semantic(row, first, second, third))))
        assertEquals(3, topics.size)
        assertTrue(topics.all { it.workCount == 1 && it.total == 1 && it.percent == 100.0 })
        assertTrue(ViewpointDistribution.topics(emptyList()).isEmpty())
        assertTrue(ViewpointDistribution.filter(emptyList(), query = "内容").isEmpty())
    }

    @Test fun unknownSourceFilterKeepsOnlyMatchingWorksInCountsAndDetails() {
        val rows = listOf(event(1, "菜单"), event(2, "设置"))
        val topics = ViewpointDistribution.topics(groups(rows, listOf(semantic(rows[0]))))
        assertEquals(2, topics.single().workCount)
        val model = ViewpointDistribution.filter(topics, state = ViewpointFilter.MODEL).single()
        assertEquals(1, model.workCount)
        assertEquals(50.0, model.percent, .001)
        assertEquals(1L, model.groups.single().items.single().record.id)
        val local = ViewpointDistribution.filter(topics, state = ViewpointFilter.LOCAL).single()
        assertEquals(1, local.workCount)
        assertEquals(2L, local.groups.single().items.single().record.id)
    }

    private fun groups(rows: List<BrowseEvent>, semantics: List<EventSemantics>) = ViewpointTimeline.groups(
        ViewpointTimeline.build(rows, StatisticsPeriod.DAY, now, zone, semantics = semantics).flatMap { it.items })
    private fun event(id: Long, text: String) = BrowseEvent(id, "reader", now - 60_000, 4096, text,
        workKey = "post:$id", sampleCount = 1, endTimestamp = now - 60_000)
    private fun argument(quote: String, topic: String, dimension: String, stance: String, conditions: String, evidence: String = "") =
        ArgumentUnit(quote, topic, "儿童骑行箱", dimension, stance, evidence, conditions, uncertain = false)
    private fun semantic(row: BrowseEvent, vararg claims: ArgumentUnit) = EventSemantics(row.id, row.visibleText, now, claims.toList())
}
