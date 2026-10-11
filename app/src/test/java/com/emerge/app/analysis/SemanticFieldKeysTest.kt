package com.emerge.app.analysis

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SemanticFieldKeysTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val utc = ZoneId.of("UTC")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private val firstQuarter = at("2026-01-20T08:00:00Z")
    private val subject = "医疗行业招聘需求"
    private val dimension = "岗位数量"
    private fun stance(value: String, objectName: String = subject, property: String = dimension) =
        SemanticFieldKeys.stance(value, objectName, property)
    private fun conditions(value: String, time: Long = firstQuarter, zone: ZoneId = shanghai) =
        SemanticFieldKeys.conditions(value, time, zone)

    @Test fun completeQuantityAndRecruitmentDemandPredicatesHaveFiniteEquivalentKeys() {
        assertEquals(stance("下降"), stance("减少"))
        assertEquals(stance("岗位需求下降"), stance("招聘需求减少"))
        assertEquals(stance("岗位需求下降"), stance("下降"))
        assertEquals(stance("招聘需求增加"), stance("岗位需求上升"))
        assertEquals(stance("增长"), stance("增加"))
        assertNotEquals(stance("招聘需求增加"), stance("招聘需求减少"))
    }

    @Test fun modalityNegationCertaintyRateAndMagnitudeRemainDifferent() {
        val decreasing = stance("招聘需求减少")
        listOf("招聘需求可能减少", "招聘需求必然减少", "招聘需求不会减少", "招聘需求未减少",
            "招聘需求没有减少", "招聘需求不再减少", "招聘需求减少20%", "招聘需求减少的速度下降",
            "招聘需求降幅减少", "招聘需求降速放缓", "招聘需求减少或增加", "招聘需求减少但薪酬增加")
            .forEach { assertNotEquals(it, decreasing, stance(it)) }
        assertNotEquals(stance("可能下降"), stance("一定下降"))
        assertNotEquals(stance("下降"), stance("下降", property = "下降速度"))
        assertNotEquals(stance("减少"), stance("减少", property = "招聘增速"))
    }

    @Test fun subjectRolesDimensionsAndIncompleteContextArePreserved() {
        assertNotEquals(stance("招聘需求减少"), stance("求职需求减少"))
        assertNotEquals(stance("招聘需求减少"), stance("岗位薪酬下降"))
        assertNotEquals(stance("岗位需求下降", objectName = "应聘者岗位需求"),
            stance("招聘需求减少", objectName = "应聘者岗位需求"))
        assertNotEquals(stance("减少"), stance("减少", objectName = "毕业生求职需求"))
        assertNotEquals(stance("岗位需求下降", objectName = "", property = ""),
            stance("招聘需求减少", objectName = "", property = ""))
        assertNotEquals(stance("下降", objectName = "", property = ""),
            stance("减少", objectName = "", property = ""))
    }

    @Test fun largerStanceSentencesAndUnsupportedPredicatesAreNotRewritten() {
        assertNotEquals(stance("观察显示岗位需求下降"), stance("观察显示招聘需求减少"))
        assertNotEquals(stance("岗位需求下降是好事"), stance("招聘需求减少是坏事"))
        assertNotEquals(stance("招聘需求下降"), stance("招聘需求趋稳"))
        assertNotEquals(stance("招聘需求下降"), stance("招聘需求下降?"))
        assertNotEquals(stance("招聘需求下降"), stance("招聘需求下降。"))
        assertEquals(stance("未来需求取决于培训"), stance("未来需求取决于培训"))
    }

    @Test fun sameObservationCalendarQuarterSharesAliasesButOtherQuartersDoNot() {
        assertEquals(conditions("本季度"), conditions("当前季度"))
        assertEquals(conditions("本季度"), conditions("这个季度", at("2026-03-31T15:59:59Z")))
        assertNotEquals(conditions("本季度"), conditions("当前季度", at("2026-03-31T16:00:00Z")))
        assertEquals(conditions("本季度"), conditions("上季度", at("2026-04-10T08:00:00Z")))
        assertEquals(conditions("下季度"), conditions("当前季度", at("2026-04-10T08:00:00Z")))
    }

    @Test fun monthAndYearLabelsAnchorEachObservationRatherThanRemainingLiteralScopes() {
        assertEquals(conditions("本月"), conditions("这个月"))
        assertEquals(conditions("本月"), conditions("当前月份"))
        assertNotEquals(conditions("本月"), conditions("本月", at("2026-02-01T00:00:00Z")))
        assertEquals(conditions("本月"), conditions("上个月", at("2026-02-01T00:00:00Z")))
        assertEquals(conditions("今年"), conditions("本年"))
        assertNotEquals(conditions("今年"), conditions("今年", at("2027-01-01T00:00:00Z")))
        assertEquals(conditions("今年"), conditions("去年", at("2027-01-01T00:00:00Z")))
        assertNotEquals(conditions("这一年"), conditions("今年"))
        assertNotEquals(conditions("最近一个月"), conditions("本月"))
        assertNotEquals(conditions("当月"), conditions("本月"))
    }

    @Test fun timezoneAndYearQuarterRolloverBoundariesUseCalendarIntervals() {
        val quarterBoundary = at("2026-03-31T16:00:00Z")
        assertNotEquals(conditions("本季度", quarterBoundary, utc), conditions("本季度", quarterBoundary, shanghai))
        assertNotEquals(conditions("本季度", firstQuarter, utc), conditions("本季度", firstQuarter, shanghai))
        assertEquals(conditions("本季度", firstQuarter, utc),
            conditions("当前季度", firstQuarter, ZoneId.of("Etc/UTC")))
        assertEquals(conditions("本季度", at("2026-12-31T15:59:59Z")),
            conditions("上季度", at("2026-12-31T16:00:00Z")))
        assertEquals(conditions("本月", at("2026-12-31T15:59:59Z")),
            conditions("上月", at("2026-12-31T16:00:00Z")))
        assertEquals(conditions("今年", at("2026-12-31T15:59:59Z")),
            conditions("去年", at("2026-12-31T16:00:00Z")))
    }

    @Test fun multipleConditionsRetainPopulationPlaceConnectorsOrderAndAllPeriods() {
        assertEquals(conditions("上海且本季度"), conditions("上海且当前季度"))
        assertEquals(conditions("毕业生，本季度；本月"), conditions("毕业生，当前季度；这个月"))
        assertNotEquals(conditions("上海且本季度"), conditions("北京且当前季度"))
        assertNotEquals(conditions("毕业生，本季度"), conditions("有经验者，当前季度"))
        assertNotEquals(conditions("上海且本季度"), conditions("上海或当前季度"))
        assertNotEquals(conditions("上海，本季度"), conditions("本季度，上海"))
        assertNotEquals(conditions("本季度；本月"), conditions("当前季度；本月", at("2026-02-01T00:00:00Z")))
        assertNotEquals(conditions("本季度以及本月"), conditions("本季度或者本月"))
    }

    @Test fun temporalWordsEmbeddedInLargerSentencesKeepWordingButStillChangePeriod() {
        assertNotEquals(conditions("本季度岗位持续减少"), conditions("当前季度岗位持续减少"))
        assertNotEquals(conditions("本季度岗位持续减少"),
            conditions("本季度岗位持续减少", at("2026-04-10T08:00:00Z")))
        assertNotEquals(conditions("仅在本月完成培训的人"),
            conditions("仅在本月完成培训的人", at("2026-02-01T00:00:00Z")))
        assertEquals(conditions("上海的毕业生且需要培训"),
            conditions("上海的毕业生且需要培训", at("2026-04-10T08:00:00Z")))
        assertNotEquals(conditions("不在本季度"), conditions("本季度"))
        assertNotEquals(conditions("本季度或下季度"), conditions("本季度且下季度"))
    }

    @Test fun literalKeysDoNotCollideWithCanonicalInternalLabelsOrErasePunctuation() {
        assertNotEquals(stance("quantity-decrease"), stance("下降"))
        assertNotEquals(conditions("Q:1767196800000:1774972800000"), conditions("本季度"))
        assertNotEquals(conditions("对象A-12"), conditions("对象A12"))
        assertNotEquals(conditions("人口少于100人"), conditions("人口不少于100人"))
        assertNotEquals(conditions("上海,本季度"), conditions("上海，本季度"))
    }
}
