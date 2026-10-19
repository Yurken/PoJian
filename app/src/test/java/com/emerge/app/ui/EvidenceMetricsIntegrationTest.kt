package com.emerge.app.ui

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class EvidenceMetricsIntegrationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_792_000_000_000L
    private fun event(id: Long, topic: String, producer: String, material: String, work: String = "work-$id") =
        BrowseEvent(id, "reader", now - (10 - id) * 1000, 1,
            "$topic 的数量增加，因为${producer}独立调查300个样本，形成${material}，数量增加10%", workKey = work)
    private fun semantics(event: BrowseEvent, topic: String, producer: String, material: String): EventSemantics {
        val evidence = event.visibleText.substringAfter("因为")
        return EventSemantics(event.id, event.visibleText, now, listOf(ArgumentUnit(
            event.visibleText.substringBefore("，"), topic, topic, "数量", "增加", evidence, uncertain = false,
            sourceProvenance = SourceProvenance(true, listOf(EvidenceSource(producer, material, "primary", evidence))))))
    }
    private val a = event(1, "就业", "青禾研究所", "《甲报告》")
    private val b = event(2, "教育", "远山研究院", "《乙报告》")
    private val models = listOf(semantics(a, "就业", "青禾研究所", "《甲报告》"), semantics(b, "教育", "远山研究院", "《乙报告》"))

    @Test fun reportAndJsonCarrySourceCountsAndOriginalAnchors() {
        val report = CognitiveAnalyzer().analyze(listOf(a, b), StatisticsPeriod.HOUR, now, zone, semantics = models)
        val exported = JSONObject(swissReportJson(report))
        assertFalse("Report still exports a fixed unknown", exported.isNull("independentSources"))
        assertEquals(2, exported.getInt("independentSources"))
        val source = exported.getJSONArray("claims").getJSONObject(0).getJSONObject("sourceProvenance")
            .getJSONArray("sources").getJSONObject(0)
        assertEquals("青禾研究所", source.getString("producer"))
        assertEquals(models.first().claims.first().evidence, source.getString("basis"))
    }

    @Test fun factualIncrementUsesEarlierHistoryAndCountsRepeatedNewMaterialOnlyOnce() {
        fun row(id: Long, at: Long, count: Int) = BrowseEvent(id, "reader", at, 1,
            "招聘岗位增加，因为报告甲记录招聘数量${count}个", workKey = "w$id")
        val history = row(1, now - 3_600_001, 10)
        val changed = row(2, now - 2000, 20)
        val repeated = row(3, now - 1000, 20)
        val events = listOf(history, changed, repeated)
        val semantics = events.map { event -> EventSemantics(event.id, event.visibleText, now,
            listOf(ArgumentUnit("招聘岗位增加", "就业", "岗位", "数量", "增加",
                event.visibleText.substringAfter("因为"), uncertain = false))) }
        val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.HOUR, now, zone, semantics = semantics)
        assertEquals(listOf(Increment.NEW, Increment.REPEATED), report.claims.map { it.increment })
        assertEquals("1", newFactMetric(report.claims).display)
        assertEquals(1, JSONObject(swissReportJson(report)).getInt("newFacts"))
    }

    @Test fun sourceCountsFollowTopicAndPeriodRatherThanAllStoredRecords() {
        val old = a.copy(id = 3, timestamp = now - 86_400_000, visibleText = a.visibleText.replace("青禾", "湖畔").replace("甲报告", "丙报告"))
        val index = prepareSwissViewpoints(listOf(a, b, old), models + semantics(old, "就业", "湖畔研究所", "《丙报告》"), null,
            StatisticsPeriod.HOUR, now, zone)
        assertEquals("2", filterSwissViewpoints(index, null, zone).independentSources.display)
        assertEquals("1", filterSwissViewpoints(index, "就业", zone).independentSources.display)
        assertEquals("1", filterSwissViewpoints(index, "教育", zone).independentSources.display)
    }

    @Test fun revisionsDeletionAndNewestEmptyResultCannotKeepOldSourceCounts() {
        fun count(events: List<BrowseEvent>, results: List<EventSemantics>) = filterSwissViewpoints(
            prepareSwissViewpoints(events, results, null, StatisticsPeriod.HOUR, now, zone), null, zone).independentSources.display
        assertEquals("1", count(listOf(a), models))
        assertEquals("未核实", count(listOf(a.copy(visibleText = "应该增加招聘岗位")), models))
        assertEquals("—", count(emptyList(), models))
        assertEquals("—", count(listOf(a), models + models.first().copy(analyzedAt = now + 1, claims = emptyList())))
    }

    @Test fun attachedMaterialUsesItsParentAndRepeatedVisitsCannotMultiplySources() {
        val parent = a.copy(visibleText = "作品标题")
        val child = a.copy(id = 4, sourceType = "screen_ocr", parentEventId = parent.id)
        val revisit = a.copy(id = 5)
        val results = listOf(semantics(child, "就业", "青禾研究所", "《甲报告》"), semantics(revisit, "就业", "青禾研究所", "《甲报告》"))
        fun count(rows: List<BrowseEvent>) = filterSwissViewpoints(prepareSwissViewpoints(rows, results, null,
            StatisticsPeriod.HOUR, now, zone), null, zone).independentSources
        assertEquals(1, count(listOf(parent, child, revisit)).known)
        assertEquals("未核实", count(listOf(parent, child.copy(workKey = "another"))).display)
        val invalid = CognitiveAnalyzer().analyze(listOf(parent, child.copy(workKey = "another")), StatisticsPeriod.HOUR,
            now, zone, semantics = results)
        assertTrue(JSONObject(swissReportJson(invalid)).isNull("independentSources"))
    }
}
