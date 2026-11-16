package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticsV2Test {
    private val source = "某企业秋招岗位减少。所有毕业生都没有出路。"
    private val event = BrowseEvent(7, "fixture.reader", 1000, 1, source, workKey = "work7")
    private val fact = SemanticEvidence("某企业秋招岗位减少", "某企业秋招岗位减少", false)
    private val result = SemanticAnalysisV2("work7", "就业", false,
        listOf(SemanticViewpoint("就业困难", "支持", "所有毕业生都没有出路", listOf(fact))),
        SemanticGuidance(true, fact.factSpan, "所有毕业生都没有出路", "个别企业推及所有毕业生", "绝对化", "无助化", true),
        listOf(SemanticBlindSpot("行业分布", "缺少其他行业招聘数据", "制造业 招聘 数据")), 1, 1, 1.0, 20,
        SemanticExpressionSpans(listOf(fact.factSpan), listOf("所有毕业生都没有出路")))

    @Test fun wireStorageAndLegacyRowsRoundTripWithoutLosingFields() {
        val semantics = SemanticsCodec.decodeV2(SemanticV2Codec.encode(result), event, 2000)
        assertEquals(semantics, SemanticsCodec.decode(SemanticsCodec.encode(semantics)))
        assertEquals(result, semantics.semanticV2)
        val legacy = semantics.copy(semanticV2 = null)
        assertTrue(SemanticsCodec.encode(legacy).claimsJson.startsWith("["))
        assertEquals(legacy, SemanticsCodec.decode(SemanticsCodec.encode(legacy)))
    }

    @Test fun rejectsHallucinatedSpansWrongWorkAndFabricatedStatistics() {
        listOf(result.copy(workId = "other"), result.copy(dedupNewFactCount = 2), result.copy(noveltyScore = .3),
            result.copy(emotionalGuidance = result.emotionalGuidance.copy(factSpan = "不存在的事实")),
            result.copy(viewpoints = listOf(result.viewpoints.single().copy(evidences = listOf(fact.copy(factSpan = "某企业岗位减少"))))))
            .forEach { invalid -> assertTrue(runCatching { SemanticsCodec.decodeV2(SemanticV2Codec.encode(invalid), event) }.isFailure) }
    }

    @Test fun coverageRequiresHistoryReferenceAndRepeatedFactsCannotInflateCounts() {
        val repeated = result.copy(viewpoints = listOf(result.viewpoints.single().copy(evidences = listOf(fact, fact))))
        assertTrue(repeated.validFor(source))
        assertFalse(repeated.copy(dedupTotalFactCount = 2).validFor(source))
        assertFalse(result.copy(viewpoints = listOf(result.viewpoints.single().copy(
            evidences = listOf(fact.copy(isCoveredByHistory = true))))).validFor(source))
        assertEquals("a b", factKey("  A\n\tB  "))
    }

    @Test fun masksCanBeStoredWithoutInventingOriginalQuotations() {
        val privateEvent = event.copy(visibleText = "联系13812345678了解招聘")
        val masked = result.copy(viewpoints = listOf(SemanticViewpoint("联系招聘", "中立", "联系[端侧已脱敏]了解招聘",
            listOf(fact.copy(factPoint = "联系招聘", factSpan = "联系[端侧已脱敏]了解招聘")))),
            emotionalGuidance = SemanticGuidance(false), expressionSpans = null)
        val semantic = SemanticsCodec.decodeV2(SemanticV2Codec.encode(masked), privateEvent)
        assertTrue(semantic.claims.isEmpty())
        assertTrue(semantic.validFor(privateEvent))
        assertEquals(masked, SemanticsCodec.decode(SemanticsCodec.encode(semantic))?.semanticV2)
    }

    @Test fun strictTypesAndCorruptStorageAreRejected() {
        val wire = SemanticV2Codec.encode(result).put("dedup_total_fact_count", "1")
        assertTrue(runCatching { SemanticsCodec.decodeV2(wire, event) }.isFailure)
        val row = SemanticsCodec.encode(SemanticsCodec.decodeV2(SemanticV2Codec.encode(result), event))
        val corrupted = JSONObject(row.claimsJson).put("version", 3)
        assertNull(SemanticsCodec.decode(row.copy(claimsJson = corrupted.toString())))
    }

    @Test fun expressionPercentagesUseAnnotationsAndNeverTreatMissingAsNeutral() {
        assertEquals(.5f to .5f, expressionFractions(listOf(result, result)))
        assertNull(expressionFractions(listOf(result.copy(expressionSpans = null))))
        assertEquals(.5f to .5f, expressionFractions(listOf(result.copy(expressionSpans = null), result)))
        assertNull(expressionFractions(listOf(result, result.copy(expressionSpans = null))))
        assertEquals(0f to 1f, expressionFractions(listOf(result.copy(expressionSpans =
            SemanticExpressionSpans(listOf("同一原文"), listOf("同一原文"))))))
    }
}
