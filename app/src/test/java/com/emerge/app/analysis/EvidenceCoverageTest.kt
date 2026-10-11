package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorPolicy
import com.emerge.app.monitoring.ObservedArgument
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Consumer-level cases: partial coverage must never be presented as coverage of a complete argument. */
class EvidenceCoverageTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val quote = "当前互联网招聘需求下降"
    private val first = "报告松岚记录岗位数量同比减少24%"
    private val second = "报告海峰记录培训预算同比减少36%"
    private fun report(values: List<String>, actors: List<List<String>> = List(values.size) { emptyList() },
        stances: List<String> = List(values.size) { "下降" }, conditions: List<String> = List(values.size) { "当前" }): CognitiveReport {
        val events = values.mapIndexed { i, value -> BrowseEvent(i + 1L, "fixture.reader", now - 100_000 + i * 10_000,
            32, "$quote，因为$value", sampleCount = 1, workKey = "independent-$i") }
        val semantics = events.mapIndexed { i, event -> EventSemantics(event.id, event.visibleText, now,
            listOf(ArgumentUnit(quote, "就业与职场", "互联网招聘需求", "岗位数量", stances[i], values[i], conditions[i],
                uncertain = false, evidenceActors = actors[i]))) }
        return CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics)
    }

    @Test fun mergedFactsHaveSeparateActualHistoricalQuotesAndMaterialReferences() {
        val result = report(listOf(first, second, "$first；$second")).claims.last()
        assertEquals(Increment.REPEATED, result.increment)
        assertEquals(2, result.evidenceDetails.size)
        assertEquals(listOf(1L, 2L), result.evidenceDetails.map { it.historyMatches.single().eventId })
        assertEquals(listOf(first, second), result.evidenceDetails.map { it.historyMatches.single().quote })
        assertEquals(listOf(listOf("报告松岚"), listOf("报告海峰")), result.evidenceDetails.map { it.identity.origins })
        assertTrue(result.evidenceDetails.none { it.isNew })
    }

    @Test fun partiallyCoveredMergedArgumentMarksOnlyTheAddedFact() {
        val result = report(listOf(first, "$first；$second")).claims.last()
        assertEquals(Increment.NEW, result.increment)
        assertFalse(result.evidenceDetails.first().isNew)
        assertTrue(result.evidenceDetails.last().isNew)
        assertEquals(1L, result.evidenceDetails.first().historyMatches.single().eventId)
        assertTrue(result.evidenceDetails.last().historyMatches.isEmpty())
    }

    @Test fun removingAnEarlierFactRebuildsCoverageRatherThanRetainingAnOldMatch() {
        val covered = report(listOf(first, second, "$first；$second"))
        val rebuilt = report(listOf(second, "$first；$second"))
        assertEquals(Increment.REPEATED, covered.claims.last().increment)
        assertEquals(Increment.NEW, rebuilt.claims.last().increment)
        assertTrue(rebuilt.claims.last().evidenceDetails.first().historyMatches.isEmpty())
    }

    @Test fun reorderedEnumerationKeepsNumbersBoundToTheirCitiesAndMetric() {
        val a = "报告远峰列出临安市招聘岗位104个，桐庐县招聘岗位57个"
        val reordered = "报告远峰列出桐庐县招聘岗位57个，临安市招聘岗位104个"
        assertEquals(Increment.REPEATED, report(listOf(a, reordered)).claims.last().increment)
        for (changed in listOf(reordered.replace("57个", "104个").replace("临安市招聘岗位104个", "临安市招聘岗位57个"),
            reordered.replace("临安市", "富阳区"), reordered.replace("招聘岗位", "新增岗位"))) {
            assertNotEquals(changed, Increment.REPEATED, report(listOf(a, changed)).claims.last().increment)
        }
    }

    @Test fun swappingValuesAcrossSourcesDoesNotReuseTheNumbersAsAnUnorderedBag() {
        val swapped = first.replace("24%", "36%") + "；" + second.replace("36%", "24%")
        assertNotEquals(Increment.REPEATED, report(listOf(first, second, swapped)).claims.last().increment)
    }

    @Test fun actorsAreAssignedToTheirOwnClauses() {
        val a = "林羽投递104份简历"
        val b = "周宁收到57封回复"
        val result = report(listOf(a, b, "$b；$a"), listOf(listOf("林羽"), listOf("周宁"), listOf("林羽", "周宁")))
        assertEquals(Increment.REPEATED, result.claims.last().increment)
        assertEquals(listOf(listOf("周宁"), listOf("林羽")), result.claims.last().evidenceDetails.map { it.identity.actors })
        assertEquals(listOf(2L, 1L), result.claims.last().evidenceDetails.map { it.historyMatches.single().eventId })
    }

    @Test fun dependentOrQuotedClausesRemainWhole() {
        val values = listOf("如果岗位减少24%，培训预算就减少36%", "岗位减少24%或者培训预算减少36%",
            "并非岗位减少24%，而是培训预算减少36%", "培训预算减少36%，因为岗位减少24%",
            "招聘104个岗位，其中57个面向应届生", "杭州比宁波多57个岗位；培训预算减少36%",
            "引用“岗位减少24%；培训预算减少36%”", "岗位数量104个，57个是实习岗位",
            "岗位数量1,004个，培训预算57元", "岗位数量104个；", "岗位数量104个；其余名额57个",
            "没有发现以下变化：岗位减少24%；培训预算减少36%",
            "在补贴结束的情况下，岗位减少24%；培训预算减少36%",
            "今年岗位减少24%；培训预算减少36%", "在上海岗位减少24%；培训预算减少36%")
        for (value in values + listOf("即使", "即便", "虽然", "尽管", "若", "一旦", "但").map { "${it}岗位减少24%；培训预算减少36%" })
            assertEquals(value, listOf(value), EvidenceAtoms.split(value).map { it.text })
    }

    @Test fun overBudgetFactsAreNotSilentlyDiscarded() {
        val value = (1..9).joinToString("；") { "报告松岚记录地区${('A'.code + it).toChar()}岗位数量${it * 10}个" }
        val result = report(listOf(value, value)).claims.last()
        assertEquals(Increment.UNKNOWN, result.increment)
        assertEquals(listOf(value), result.evidenceDetails.map { it.text })
        assertTrue(result.evidenceDetails.single().identityUnresolved)
    }

    @Test fun aliasesStayInOneReportAndChartGroupButKeepTheirOriginalFieldText() {
        val values = listOf(first, first)
        val stances = listOf("岗位需求下降", "招聘需求减少")
        val conditions = listOf("本季度", "当前季度")
        val result = report(values, stances = stances, conditions = conditions)
        assertEquals(Increment.REPEATED, result.claims.last().increment)
        assertEquals(1, result.groups.size)
        assertEquals(stances, result.claims.map { it.stance })
        val items = result.claims.map { claim ->
            val event = BrowseEvent(claim.eventId, "fixture", claim.timestamp, 32, quote, workKey = "work-${claim.eventId}")
            val view = ViewpointClaim(quote, claim.topic, claim.subject, claim.dimension, claim.stance, claim.conditions,
                analysisSource = "structured_model", record = event, zoneId = zone.id)
            ViewpointItem(event, view.label, quote, false, listOf(view), "structured_model")
        }
        assertEquals(1, ViewpointTimeline.groups(items).size)
    }

    @Test fun liveMonitoringUsesUnionCoverageAndNeverCountsOnlyOneCoveredAtom() {
        fun history(withNew: Boolean) = (0 until 12).map { i -> ObservedArgument("record-$i", "fixture",
            now - 4 * 86_400_000L + i * 30_000_000L, "就业与职场", "互联网招聘需求", "岗位数量",
            if (i % 2 == 0) "岗位需求下降" else "招聘需求减少", when (i) { 0 -> first; 1 -> second
                else -> if (withNew) "$first；报告海峰记录培训预算同比减少${40 + i}%" else "$second；$first" },
            if (i % 2 == 0) "本季度" else "当前季度", encounterKey = "work-$i") }
        assertNotNull(MonitorPolicy.repetition(history(false), now, zone))
        assertNull(MonitorPolicy.repetition(history(true), now, zone))
    }

    @Test fun localEvidenceDoesNotLoseTheDependentTailAndWaterQualityCanBeExtracted() {
        val text = "程序员求职竞争越来越激烈，因为如果岗位减少24%，培训预算就减少36%"
        val event = BrowseEvent(1, "fixture", now - 1, 32, text, sampleCount = 1)
        val claim = CognitiveAnalyzer().analyze(listOf(event), StatisticsPeriod.DAY, now, zone).claims.single()
        assertTrue(claim.evidenceDetails.any { it.text.contains("培训预算就减少36%") && it.text.contains("如果") })
        val water = event.copy(visibleText = "本河段水质持续改善，因为监测显示水体透明度达到86厘米")
        val waterReport = CognitiveAnalyzer().analyze(listOf(water), StatisticsPeriod.DAY, now, zone)
        assertEquals("环境与生态", waterReport.claims.single().topic)
        assertTrue(waterReport.claims.single().quote.contains("水质"))
    }

    @Test fun localNumericMarkersRetainTheirSubjectAndNegation() {
        val events = listOf("就业很差，检测到招聘岗位减少24%", "就业很差，未检测到招聘岗位减少24%").mapIndexed { i, value ->
            BrowseEvent(i + 1L, "fixture", now - 100_000 + i * 60_000, 32, value, sourceType = "audio_transcript") }
        val result = CognitiveAnalyzer().analyze(events, StatisticsPeriod.DAY, now, zone)
        assertEquals(Increment.NEW, result.claims.last().increment)
        assertTrue(result.claims.last().evidenceDetails.single().text.startsWith("未检测到"))
    }

    @Test fun genericAttributionDoesNotConsumeAndDiscardTheFirstFact() {
        val text = "报告显示杭州招聘岗位104个，宁波招聘岗位57个，嘉兴招聘岗位19个"
        assertTrue(EvidenceIdentityComparator.identity(text).origins.isEmpty())
        assertEquals(listOf(text), EvidenceAtoms.split(text).map { it.text })
        val named = "报告松岚列出杭州招聘岗位104个，宁波招聘岗位57个"
        val atoms = EvidenceAtoms.split(named)
        assertTrue(atoms.first().text.startsWith("报告松岚列出"))
        assertTrue(atoms.any { it.text.contains("104个") })
        assertTrue(atoms.any { it.text.contains("57个") })
    }

    @Test fun uncertaintyOperatorsAndQuantifiersAreNotErased() {
        val base = "同一调查的岗位数量变化为24%"
        for (modified in listOf(base.replace("24%", "±24%"), base.replace("24%", "约24%"), base.replace("24%", "≈24%")))
            assertNotEquals(EvidenceRelation.REUSED, EvidenceIdentityComparator.compare(modified, base))
        val unit = "报告松岚记录监测水体的透明度在完整观察周期内达到86厘米"
        assertNotEquals(EvidenceRelation.REUSED, EvidenceIdentityComparator.compare(unit.replace("86厘米", "86米"), unit))
    }

    @Test fun quantityParaphrasesKeepOriginalQuotesAndCompletedHiringStaysDistinct() {
        val a = "报告松岚说明远峰公司本月提供三十六个招聘名额"
        val b = "报告松岚说明远峰公司本月仅招聘36人"
        val c = "依据报告松岚，远峰公司这个月只能招三十六人"
        val result = report(listOf(a, b, c))
        assertEquals(listOf(Increment.FIRST, Increment.REPEATED, Increment.REPEATED), result.claims.map { it.increment })
        assertEquals(listOf(a, b, c), result.claims.map { it.evidence.single() })
        assertNotEquals(Increment.REPEATED, report(listOf(a, b.replace("仅招聘36人", "已经录用36人"))).claims.last().increment)
        assertNotEquals(Increment.REPEATED, report(listOf(a, b.replace("36人", "37人"))).claims.last().increment)
    }

    @Test fun relativeEvidenceTimeUsesObservationCalendarEvenWithBlankConditions() {
        for ((earlier, later, word) in listOf(Triple("2026-12-31T10:00:00Z", "2027-01-01T10:00:00Z", "今年"),
            Triple("2026-10-31T10:00:00Z", "2026-11-01T10:00:00Z", "本月"),
            Triple("2026-09-30T10:00:00Z", "2026-10-01T10:00:00Z", "本季度"))) {
            val evidence = "报告松岚记录${word}岗位数量同比减少24%"
            val events = listOf(earlier, later).mapIndexed { i, value -> BrowseEvent(i + 1L, "fixture", Instant.parse(value).toEpochMilli(),
                32, "$quote，因为$evidence", sampleCount = 1) }
            val model = events.map { EventSemantics(it.id, it.visibleText, it.timestamp,
                listOf(ArgumentUnit(quote, "就业与职场", "互联网招聘需求", "岗位数量", "下降", evidence, uncertain = false))) }
            val result = CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, events.last().timestamp + 1, zone, semantics = model)
            assertEquals(word, Increment.NEW, result.claims.last().increment)
        }
    }
}
