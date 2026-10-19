package com.emerge.app.analysis

import org.junit.Assert.*
import org.junit.Test

class EvidenceMetricsTest {
    private fun claim(id: Long, increment: Increment, vararg fragments: EvidenceFragment) = ClaimObservation(
        id, id * 1000, "reader", "就业", "岗位增加", fragments.map { it.text }, "", false, increment, null,
        evidenceDetails = fragments.toList())
    private fun fact(text: String = "岗位数量增加10%", fresh: Boolean = false, repeated: Boolean = false) =
        EvidenceFragment(text, EvidenceType.FACT, fresh,
            if (repeated) listOf(EvidenceHistoryMatch(1, text)) else emptyList())

    @Test fun factsAreDeduplicatedAndAdviceDoesNotInflateTheCount() {
        val rows = listOf(claim(1, Increment.NEW, fact(fresh = true)),
            claim(2, Increment.REPEATED, fact(repeated = true)),
            claim(3, Increment.NEW, fact(fresh = true), EvidenceFragment("建议学习", EvidenceType.ADVICE, true)))
        assertEquals(1, newFactMetric(rows).known)
        assertEquals("1", newFactMetric(rows).display)
    }

    @Test fun zeroChangeUnknownAndNoDataRemainDifferent() {
        assertEquals("0", newFactMetric(listOf(claim(2, Increment.REPEATED, fact(repeated = true)))).display)
        assertEquals("未核实", newFactMetric(listOf(claim(1, Increment.FIRST, fact()))).display)
        assertEquals("—", newFactMetric(emptyList()).display)
        assertEquals("1+", newFactMetric(listOf(claim(2, Increment.NEW, fact(fresh = true)),
            claim(3, Increment.UNKNOWN, fact("招聘人数增加20%")))).display)
    }

    @Test fun uncertainAttributionAndTruncatedRecallCannotCreateFacts() {
        val fresh = claim(2, Increment.NEW, fact(fresh = true))
        for (row in listOf(fresh.copy(uncertainAttribution = true), fresh.copy(historyComparisonTruncated = true),
            fresh.copy(evidenceDetails = listOf(fact(fresh = true).copy(identityUnresolved = true))))) {
            assertEquals("未核实", newFactMetric(listOf(row)).display)
        }
    }

    @Test fun repeatingAnUnassessedFirstOccurrenceCannotTurnUnknownIntoZero() {
        assertEquals("未核实", newFactMetric(listOf(claim(1, Increment.FIRST, fact()),
            claim(2, Increment.REPEATED, fact(repeated = true)))).display)
    }

    @Test fun differentOriginalMaterialsAndRelativeDatesRemainDistinct() {
        val a = fact(fresh = true).copy(identity = EvidenceIdentity(origins = listOf("报告甲")))
        val b = a.copy(identity = EvidenceIdentity(origins = listOf("报告乙")))
        val c = a.copy(relativeTimeScope = "2026")
        assertEquals(3, newFactMetric(listOf(claim(2, Increment.NEW, a, b, c))).known)
    }

    private fun primary(producer: String, material: String): EvidenceSource {
        val basis = "${producer}独立调查300家企业，形成${material}，岗位增加10%"
        return EvidenceSource(producer, material, "primary", basis)
    }
    private fun secondary(material: String, vararg upstream: String): EvidenceSource {
        val basis = "汇编机构的${material}引用${upstream.joinToString("和")}中的数据"
        return EvidenceSource("汇编机构", material, "secondary", basis, upstream.toList())
    }
    private fun record(id: Long, vararg sources: EvidenceSource, complete: Boolean = true) = SourceRecord(
        id, sources.joinToString("；") { it.basis }, SourceProvenance(complete, sources.toList()))

    @Test fun independentOriginalCollectionsCountAndRepostsDoNotMultiplyThem() {
        val a = primary("青禾研究所", "《甲样本》")
        val b = primary("远山研究院", "《乙样本》")
        val result = independentSourceMetric(listOf(record(1, a), record(2, a), record(3, b),
            record(4, secondary("《转述》", "《甲样本》"))))
        assertEquals("2", result.display)
    }

    @Test fun sharedProducerOrOriginalMaterialCannotIncreaseIndependence() {
        assertEquals("1", independentSourceMetric(listOf(record(1, primary("青禾研究所", "《甲样本》")),
            record(2, primary("青禾研究所", "《乙样本》")))).display)
        assertEquals("1", independentSourceMetric(listOf(record(1, primary("青禾研究所", "《甲样本》")),
            record(2, primary("远山研究院", "甲样本")))).display)
    }

    @Test fun twoPublishersSharingOneUpstreamCountOnlyTheOriginalRoot() {
        val rows = listOf(record(1, primary("原始研究所", "《共同调查》")),
            record(2, secondary("《甲报告》", "《共同调查》")),
            record(3, secondary("《乙报告》", "《共同调查》")),
            record(4, secondary("《三次转载》", "《甲报告》")))
        assertEquals("1", independentSourceMetric(rows).display)
        assertEquals("未核实", independentSourceMetric(rows.drop(1)).display)
    }

    @Test fun citingTwoIndependentMaterialsDoesNotMergeTheirRoots() {
        assertEquals("2", independentSourceMetric(listOf(record(1, primary("青禾研究所", "《甲样本》")),
            record(2, primary("远山研究院", "《乙样本》")),
            record(3, secondary("《汇编》", "《甲样本》", "《乙样本》")))).display)
    }

    @Test fun unresolvedLegacyRecordsAndIncompleteCoverageRemainVisible() {
        val known = record(1, primary("青禾研究所", "《甲样本》"))
        assertEquals("1+", independentSourceMetric(listOf(known, SourceRecord(2, "原文", null))).display)
        assertEquals("1+", independentSourceMetric(listOf(known.copy(provenance = known.provenance!!.copy(complete = false)))).display)
        assertEquals("未核实", independentSourceMetric(listOf(SourceRecord(1, "原文", null))).display)
        assertEquals("—", independentSourceMetric(emptyList()).display)
    }

    @Test fun cyclicOrConflictingProvenanceCannotBecomeIndependentEvidence() {
        val cycle = listOf(record(1, secondary("《甲报告》", "《乙报告》")),
            record(2, secondary("《乙报告》", "《甲报告》")))
        assertEquals("未核实", independentSourceMetric(cycle).display)
        val conflict = record(3, primary("青禾研究所", "《甲报告》"))
        assertEquals("未核实", independentSourceMetric(cycle + conflict).display)
    }

    @Test fun conflictingPrimaryAndSecondaryDeclarationsRemainUnknownEvenWithAKnownParent() {
        val rows = listOf(record(1, primary("原始研究所", "《共同调查》")),
            record(2, primary("青禾研究所", "《甲报告》")),
            record(3, secondary("《甲报告》", "《共同调查》")))
        assertEquals("1+", independentSourceMetric(rows).display)
    }

    @Test fun invalidAnchorsAndInvalidPrimaryDeclarationsCannotCount() {
        val source = primary("青禾研究所", "《甲样本》")
        assertEquals("未核实", independentSourceMetric(listOf(record(1, source).copy(evidence = "修改后原文"))).display)
        assertEquals("未核实", independentSourceMetric(listOf(record(1,
            source.copy(basis = "青禾研究所发布《甲样本》")))).display)
    }

    @Test fun longCitationChainsAreOrderIndependentAndDoNotNeedRecursiveTraversal() {
        val rows = listOf(record(1, primary("原始研究所", "《材料0》"))) + (1..800).map { index ->
            record(index + 1L, secondary("《材料$index》", "《材料${index - 1}》"))
        }
        assertEquals("1", independentSourceMetric(rows).display)
        assertEquals("1", independentSourceMetric(rows.reversed()).display)
    }
}
