package com.emerge.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EvidenceTextCanonicalizerTest {
    private fun key(text: String) = EvidenceTextCanonicalizer.canonicalize(text)
    private fun equal(a: String, b: String) = assertEquals(key(a), key(b))
    private fun different(a: String, b: String) = assertNotEquals(key(a), key(b))

    @Test fun recognizesArbitraryCalendarRecruitmentCapacityParaphrases() {
        for (company in listOf("云帆公司", "远川集团")) for (count in listOf("四十三" to "43", "三百一十八" to "318")) {
            equal("${company}本月提供${count.second}个招聘名额", "${company}本月仅招聘${count.first}人")
            equal("${company}本月提供${count.second}个招聘名额", "${company}这个月只能招${count.second}人")
        }
        equal("溪源公司本季度提供十二个招聘名额", "溪源公司本季度只能招聘12人")
    }

    @Test fun changedCalendarQuantityOrJobRoleRemainsDifferent() {
        different("海松公司本月提供18个招聘名额", "海松公司本月仅招聘十九人")
        different("海松公司本月仅招聘十八人", "海松公司上月仅招聘18人")
        different("海松公司研发部门本月仅招聘十八人", "海松公司销售部门本月仅招聘十八人")
        different("海松公司本月仅招聘18人", "海松公司本月仅招聘+18人")
    }

    @Test fun hiringOffersAndApplicationsNeverBecomeCapacity() {
        val capacity = "云帆公司本月提供18个招聘名额"
        for (other in listOf("云帆公司本月录用十八人", "云帆公司本月招聘了十八人", "云帆公司本月收到十八份申请",
            "云帆公司本月发出十八份录用通知", "云帆公司本月十八人入职", "云帆公司本月有十八人应聘")) different(capacity, other)
        different("云帆公司已本月提供18个招聘名额", "云帆公司已本月仅招聘十八人")
        different("云帆公司实际本月提供18个招聘名额", "云帆公司实际本月仅招聘十八人")
        different("云帆公司如果本月提供18个招聘名额", "云帆公司如果本月只能招十八人")
    }

    @Test fun preservesRestrictiveWordsOutsideTheNarrowCapacityConstruction() {
        different("门诊仅接诊二十人", "门诊接诊20人")
        different("门诊不超过二十人", "门诊超过20人")
        different("门诊至少二十人", "门诊至多20人")
        different("公司本月不提供二十个招聘名额", "公司本月不只能招20人")
        different("公司预计本月提供二十个招聘名额", "公司本月只能招20人")
    }

    @Test fun chineseIntegersLargeUnitsAndFinancialNumbersAreExact() {
        equal("共有两万三千四百五十六人", "共有23456人")
        equal("预算为壹仟贰佰叁拾肆元", "预算为1234元")
        equal("费用为一亿零二百万零三元", "费用为102000003元")
        equal("库存为一万亿件", "库存为1000000000000件")
        equal("接诊十人", "接诊10人")
        equal("收到零份回复", "收到0份回复")
    }

    @Test fun chineseDecimalsAndExplicitPercentageFormsAreExact() {
        equal("增长百分之二十点零五", "增长20.05%")
        equal("预算为二点五万元", "预算为2.5万元")
        equal("预算为2.5万元", "预算为25000元")
        equal("检测值为二十点五公斤", "检测值为20.5公斤")
        equal("比例上升二十个百分点", "比例上升20个百分点")
        equal("增幅为＋２０．５％", "增幅为+20.5%")
        equal("变动为负二十点五％", "变动为-20.5%")
    }

    @Test fun signsUnitsInequalitiesAndDirectionRemainDifferent() {
        for ((a, b) in listOf("增幅为+20%" to "增幅为-20%", "增幅为20%" to "增幅为+20%",
            "变动为≤二十元" to "变动为≥20元", "体积为二十升" to "体积为20毫升",
            "上升二十个百分点" to "上升20%", "接诊二十人" to "接诊20次", "增加二十元" to "减少20元")) different(a, b)
    }

    @Test fun numberNormalizationPreservesPredicateToNumberAssociation() {
        equal("门诊接诊二十人，转诊三十人", "门诊接诊20人，转诊30人")
        different("门诊接诊二十人，转诊三十人", "门诊接诊30人，转诊20人")
        different("门诊接诊二十人，转诊三十人", "门诊转诊20人，接诊30人")
    }

    @Test fun ambiguousEllipticAndMalformedNumbersAreNotInterpreted() {
        for (text in listOf("预算为一百二元", "预算为一万二元", "预算为一百零二十元", "预算为二十十元",
            "接诊二三人", "接诊一二三人", "预算为一万点五元", "接诊二十至三十人", "接诊二十、三十人",
            "接诊二十±二人", "接诊二十/三十人", "预算为一百百元")) assertEquals(text, key(text))
        different("预算为一百二元", "预算为120元")
        different("接诊二三人", "接诊23人")
    }

    @Test fun titlesSourceNamesAndPersonalRecordActorsKeepTheirOriginalCharacters() {
        for (text in listOf("《二十人招聘报告》显示岗位减少", "调查二十人显示岗位减少", "《这个月》记录就业情况",
            "根据受访者二十人的本人记录，等待消息", "依据二十人调查，岗位减少", "受访者二十人投递简历",
            "二十人公司本月仅招聘18人", "编号A二十人", "编号二十人")) {
            val expected = if (text == "二十人公司本月仅招聘18人") "二十人公司本月招聘名额18人" else text
            assertEquals(expected, key(text))
        }
        different("《二十人招聘报告》显示岗位减少", "《20人招聘报告》显示岗位减少")
        different("根据受访者二十人的本人记录，等待消息", "根据受访者20人的本人记录，等待消息")
        different("二十人公司本月提供18个招聘名额", "20人公司本月提供18个招聘名额")
    }

    @Test fun explicitUnknownStructuresAndRangeSyntaxStayDistinct() {
        different("本月只招二十名工程师", "本月只能招20人")
        different("本月提供20份录用通知", "本月提供20个招聘名额")
        different("本月提供20个招聘名额", "本月提供20个实习名额")
        assertEquals("最近招聘二十多人", key("最近招聘二十多人"))
        assertEquals("编号二十人", key("编号二十人"))
        assertEquals("本月不能招二十至三十人", key("本月不能招二十至三十人"))
    }

    @Test fun comparisonKeysAreStableAndOriginalEvidenceRemainsAvailable() {
        val evidence = "来自《远川二十人调查》，远川公司这个月只能招二十人；预算为二点五万元"
        val result = key(evidence)
        assertEquals(result, key(result))
        assertEquals("来自《远川二十人调查》，远川公司本月招聘名额20人；预算为25000元", result)
        assertEquals("来自《远川二十人调查》，远川公司这个月只能招二十人；预算为二点五万元", evidence)
    }
}
