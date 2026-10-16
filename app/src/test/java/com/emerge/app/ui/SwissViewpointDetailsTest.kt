package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class SwissViewpointDetailsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_792_000_000_000L
    private val quote = "招聘岗位增加"
    private fun event(id: Long, work: String, evidence: String) = BrowseEvent(id, "reader", now - (10 - id) * 60_000,
        1, "$quote，因为$evidence", workKey = work)
    private fun semantic(event: BrowseEvent, evidence: String, at: Long = now) = EventSemantics(event.id,
        event.visibleText, at, listOf(ArgumentUnit(quote, "就业", "招聘岗位", "数量", "增加", evidence, "", false)))
    private fun group(events: List<BrowseEvent>, semantics: List<EventSemantics>) = ViewpointTimeline.groups(
        ViewpointTimeline.build(events, StatisticsPeriod.HOUR, now, zone, semantics = semantics).flatMap { it.items }).single()

    @Test fun allSupportingWorksAndEarlierEvidenceOfTheSameWorkRemainReachable() {
        val a = event(1, "one", "报告甲记录岗位增加10%")
        val b = event(2, "one", "报告乙记录岗位增加20%")
        val c = event(3, "two", "报告丙记录岗位增加30%")
        val events = listOf(a, b, c)
        val semantics = listOf(semantic(a, "报告甲记录岗位增加10%"), semantic(b, "报告乙记录岗位增加20%"), semantic(c, "报告丙记录岗位增加30%"))
        val result = swissViewpointDetails(group(events, semantics), events, semantics, zone)
        assertEquals(listOf("报告甲记录岗位增加10%", "报告乙记录岗位增加20%", "报告丙记录岗位增加30%"), result.evidence.map { it.text })
        assertEquals(listOf(1L, 2L, 3L), result.sourceIds)
        assertEquals(2, result.workCount)
    }

    @Test fun revisedDeletedAndSupersededMaterialCannotRemainInAnOpenDetail() {
        val a = event(1, "one", "报告甲记录岗位增加10%")
        val b = event(2, "two", "报告乙记录岗位增加20%")
        val initial = listOf(semantic(a, "报告甲记录岗位增加10%"), semantic(b, "报告乙记录岗位增加20%"))
        val selected = group(listOf(a, b), initial)
        assertTrue(swissViewpointDetails(selected, listOf(a.copy(visibleText = "已修改")), initial, zone).sourceIds.isEmpty())
        val superseded = initial + EventSemantics(a.id, a.visibleText, now + 1, emptyList())
        val result = swissViewpointDetails(selected, listOf(a), superseded, zone)
        assertTrue(result.evidence.isEmpty())
        assertEquals(0, result.workCount)
    }

    @Test fun anotherStanceInTheSameWorkCannotDonateEvidenceToTheSelectedViewpoint() {
        val a = event(1, "one", "报告甲记录岗位增加10%")
        val other = "部分岗位减少"
        val event = a.copy(visibleText = a.visibleText + "\n$other，因为报告乙记录岗位减少20%")
        val base = semantic(event, "报告甲记录岗位增加10%")
        val semantics = listOf(base.copy(claims = base.claims + ArgumentUnit(other, "就业", "招聘岗位", "数量", "减少", "报告乙记录岗位减少20%", "", false)))
        val selected = ViewpointTimeline.groups(ViewpointTimeline.build(listOf(event), StatisticsPeriod.HOUR,
            now, zone, semantics = semantics).flatMap { it.items }).first { it.items.first().claims.first().stance == "增加" }
        assertEquals(listOf("报告甲记录岗位增加10%"), swissViewpointDetails(selected, listOf(event), semantics, zone).evidence.map { it.text })
    }

    @Test fun currentLocalCandidateRemainsReachableWhenAnOlderModelSnapshotIsInvalid() {
        val old = event(1, "one", "报告甲记录岗位增加10%")
        val current = old.copy(visibleText = "应该提高招聘岗位数量，为求职者提供更多选择")
        val models = listOf(semantic(old, "报告甲记录岗位增加10%"))
        val selected = group(listOf(current), models)
        assertEquals("local_rules", selected.analysisSource)
        val details = swissViewpointDetails(selected, listOf(current), models, zone)
        assertEquals(listOf(current.id), details.sourceIds)
        assertEquals(1, details.workCount)
        assertTrue(details.evidence.isEmpty())
    }

    @Test fun attachedEvidenceOpensItsBoundWorkAndRejectsAChangedParentBinding() {
        val parent = event(1, "one", "报告甲记录岗位增加10%").copy(visibleText = "作品标题")
        val child = event(2, "one", "报告乙记录岗位增加20%").copy(sourceType = "screen_ocr", parentEventId = parent.id)
        val models = listOf(semantic(child, "报告乙记录岗位增加20%"))
        val selected = group(listOf(parent, child), models)
        val details = swissViewpointDetails(selected, listOf(parent, child), models, zone)
        assertEquals(listOf(child.id), details.sourceIds)
        assertEquals(1, details.workCount)
        assertEquals(parent, swissSourceEvent(child.id, listOf(parent, child)))
        assertTrue(swissViewpointDetails(selected, listOf(parent, child.copy(workKey = "other")), models, zone).sourceIds.isEmpty())
    }

    @Test fun localReportKeepsAllRetainedEvidenceAndHistoricalSources() {
        val localQuote = "应该提高招聘岗位数量"
        val texts = listOf("报告甲记录岗位增加10%", "报告乙记录岗位增加20%", "报告甲记录岗位增加10%")
        val events = texts.mapIndexed { index, text -> event(index + 1L, if (index < 2) "one" else "two", text)
            .copy(visibleText = "$localQuote。$text") }
        val claims = events.mapIndexed { index, event -> ClaimObservation(event.id, event.timestamp, event.packageName,
            "就业", localQuote, listOf(texts[index]), "", false, Increment.FIRST, null,
            evidenceDetails = listOf(EvidenceFragment(texts[index], EvidenceType.FACT, historyMatches =
                if (index == 2) listOf(EvidenceHistoryMatch(1, texts[0])) else emptyList()))) }
        val report = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.HOUR, now, zone).copy(claims = claims)
        val selected = ViewpointTimeline.groups(ViewpointTimeline.build(events, StatisticsPeriod.HOUR, now, zone, report).flatMap { it.items }).single()
        val result = swissViewpointDetails(selected, events, emptyList(), zone, report)
        assertEquals(listOf(1L, 2L, 3L), result.sourceIds)
        assertEquals(2, result.workCount)
        assertEquals(texts.distinct(), result.evidence.map { it.text })
        assertEquals(listOf(EvidenceHistoryMatch(1, texts[0])), result.evidence.first().historyMatches)
        val removed = swissViewpointDetails(selected, events.drop(1), emptyList(), zone, report)
        assertTrue(removed.evidence.all { it.historyMatches.isEmpty() })
    }

    @Test fun unmodeledEarlierVisitsToTheSameWorkRemainReachableWithoutAReport() {
        val a = event(1, "one", "").copy(visibleText = "应该提高招聘岗位数量，为求职者提供更多选择")
        val b = a.copy(id = 2, timestamp = a.timestamp + 60_000, visibleText = a.visibleText + "\n后续内容")
        val selected = group(listOf(a, b), emptyList())
        assertEquals(listOf(1L, 2L), swissViewpointDetails(selected, listOf(a, b), emptyList(), zone).sourceIds)
    }

    @Test fun staleReportCannotReintroduceEvidenceRemovedByTheCurrentModel() {
        val oldEvidence = "报告甲记录岗位增加10%"
        val newEvidence = "报告乙记录岗位增加20%"
        val event = event(1, "one", "$oldEvidence，$newEvidence")
        val oldModel = semantic(event, oldEvidence)
        val oldReport = CognitiveAnalyzer().analyze(listOf(event), StatisticsPeriod.HOUR, now, zone, semantics = listOf(oldModel))
        val currentModel = semantic(event, newEvidence, now + 1)
        val selected = group(listOf(event), listOf(currentModel))
        val details = swissViewpointDetails(selected, listOf(event), listOf(currentModel), zone, oldReport)
        assertEquals(listOf(newEvidence), details.evidence.map { it.text })
    }
}
