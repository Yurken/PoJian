package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SwissBrowsePresentationTest {
    private fun row(id: Long, text: String, pkg: String = "reader") =
        BrowseEvent(id, pkg, id * 1000, 32, text, workKey = "work:$id")
    private fun report() = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.DAY, 50_000, ZoneId.of("UTC"))

    @Test fun appAndWordFiltersIntersectWithoutHidingUnanalyzedRows() {
        val a = row(1, "招聘岗位增加")
        val b = row(2, "招聘岗位减少", "other")
        val c = row(3, "人工智能新技术")
        assertEquals(listOf(a), swissHistoryRecords(listOf(c, b, a), emptyList(), null, "reader", "招聘"))
        assertEquals(listOf(c, b, a), swissHistoryRecords(listOf(a, b, c), emptyList(), null, null, null))
    }

    @Test fun staleModelCannotMakeAnUnrelatedRecordMatchAWord() {
        val current = row(1, "天气晴朗")
        val old = EventSemantics(1, "招聘岗位增加", 2000,
            listOf(ArgumentUnit("招聘岗位增加", "就业", "企业", "岗位", "支持")))
        assertTrue(swissHistoryRecords(listOf(current), listOf(old), null, null, "就业").isEmpty())
    }

    @Test fun validModelTopicCanFindItsRawRecord() {
        val current = row(1, "招聘岗位增加")
        val model = EventSemantics(1, current.visibleText, 2000,
            listOf(ArgumentUnit(current.visibleText, "就业", "企业", "岗位", "支持")))
        assertEquals(listOf(current), swissHistoryRecords(listOf(current), listOf(model), null, null, "就业"))
    }

    @Test fun repetitionRequiresCurrentAndHistoricalRetainedQuotes() {
        val old = row(1, "岗位新增100个")
        val current = row(2, "岗位新增100个")
        val observation = ClaimObservation(2, 2000, "reader", "就业", current.visibleText, listOf(current.visibleText),
            "", false, Increment.REPEATED, 1, evidenceDetails = listOf(EvidenceFragment(current.visibleText,
                EvidenceType.FACT, historyMatches = listOf(EvidenceHistoryMatch(1, old.visibleText)))))
        val report = report().copy(claims = listOf(observation))
        assertEquals(1, swissHistoryMatches(current, report, listOf(old, current)).size)
        assertTrue(swissHistoryMatches(current, report, listOf(current)).isEmpty())
        assertTrue(swissHistoryMatches(current, report, listOf(old.copy(visibleText = "内容已变化"), current)).isEmpty())
        assertTrue(swissHistoryMatches(current.copy(visibleText = "新内容"), report, listOf(old, current)).isEmpty())
    }

    private fun item(id: Long, stance: String, topic: String = "就业", structured: Boolean = true): ViewpointItem {
        val record = row(id, "支持岗位调整")
        val claim = ViewpointClaim(record.visibleText, topic, "企业", "岗位", stance, record = record,
            analysisSource = if (structured) "structured_model" else "local_rules")
        return ViewpointItem(record, claim.label, claim.quote, false, listOf(claim), claim.analysisSource)
    }

    @Test fun unknownAndLocalClaimsNeverBecomeNeutralTrendPoints() {
        assertNull(swissStanceTrend(emptyList()))
        assertNull(swissStanceTrend(listOf(item(1, "尚待判断"))))
        assertNull(swissStanceTrend(listOf(item(1, "中立", structured = false))))
        assertEquals(0f, swissStanceTrend(listOf(item(1, "中立")))!!, .001f)
        assertEquals(1f, swissStanceTrend(listOf(item(1, "支持"), item(2, "尚待判断")))!!, .001f)
    }

    @Test fun conflictingClaimsInOneWorkAreNotInventedAsBalancedNeutrality() {
        assertNull(swissStanceTrend(listOf(item(1, "支持"), item(1, "反对"))))
        assertEquals(0f, swissStanceTrend(listOf(item(1, "支持"), item(2, "反对")))!!, .001f)
    }

    @Test fun topicFilterKeepsOnlySelectedClaimsEvenInMultiTopicWork() {
        val first = item(1, "支持")
        val second = item(2, "反对", "科技")
        val combined = first.copy(claims = first.claims + second.claims)
        assertEquals(listOf(first.claims.single()), swissTopicItems(listOf(combined), "就业").single().claims)
        assertTrue(swissTopicItems(listOf(first), "科技").isEmpty())
    }
}
