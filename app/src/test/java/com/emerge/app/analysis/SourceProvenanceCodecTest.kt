package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceProvenanceCodecTest {
    private val evidence = "青禾研究所独立调查300家企业，形成《就业样本甲》，岗位增加10%"
    private fun row() = SemanticsCodec.encode(EventSemantics(1, "岗位增加，因为$evidence", 100,
        listOf(ArgumentUnit("岗位增加", "就业", "岗位", "数量", "增加", evidence, uncertain = false))))
    private fun provenance() = JSONObject().put("complete", true).put("sources", JSONArray().put(JSONObject()
        .put("producer", "青禾研究所").put("material", "《就业样本甲》").put("kind", "primary")
        .put("basis", evidence).put("upstream", JSONArray())))
    private fun stored(value: Any) = row().let { it.copy(claimsJson = JSONArray(it.claimsJson).apply {
        getJSONObject(0).put("source_provenance", value)
    }.toString()) }

    @Test fun sourceRelationshipsSurviveStoredJsonRoundTrip() {
        val decoded = SemanticsCodec.decode(stored(provenance()))!!
        val output = JSONArray(SemanticsCodec.encode(decoded).claimsJson).getJSONObject(0)
        assertTrue("Source relationships were discarded", output.has("source_provenance"))
        assertEquals("青禾研究所", output.getJSONObject("source_provenance")
            .getJSONArray("sources").getJSONObject(0).getString("producer"))
        assertFalse(decoded.validFor(BrowseEvent(1, "reader", 0, 0, "已经修改的原文")))
    }

    @Test fun missingFieldKeepsOldRowsReadable() {
        assertNotNull(SemanticsCodec.decode(row()))
        assertFalse(JSONArray(SemanticsCodec.encode(SemanticsCodec.decode(row())!!).claimsJson)
            .getJSONObject(0).has("source_provenance"))
    }

    @Test fun corruptOrInventedProvenanceInvalidatesStoredResult() {
        val values = listOf(JSONObject.NULL, JSONArray(), provenance().put("complete", "true"),
            provenance().put("sources", JSONArray()), provenance().apply {
                getJSONArray("sources").getJSONObject(0).put("producer", "虚构机构")
            }, provenance().apply { getJSONArray("sources").getJSONObject(0).put("kind", "verified") })
        values.forEach { assertNull(it.toString(), SemanticsCodec.decode(stored(it))) }
    }

    @Test fun negatedOrUncertainCollectionCannotBePromotedByCroppingTheBasis() {
        for (prefix in listOf("据说", "网传", "有人声称", "如果")) {
            val original = row().copy(inputText = "岗位增加，因为$prefix$evidence")
            val claims = JSONArray(original.claimsJson).apply { getJSONObject(0)
                .put("evidence", "$prefix$evidence").put("source_provenance", provenance()) }
            assertNull(prefix, SemanticsCodec.decode(original.copy(claimsJson = claims.toString())))
        }
    }

    @Test fun croppedEvidenceCannotHideQualifiersInTheFullStoredInput() {
        for (prefix in listOf("据说", "并非", "如果")) {
            val original = stored(provenance()).copy(inputText = "岗位增加，因为$prefix$evidence")
            assertNull(prefix, SemanticsCodec.decode(original))
        }
    }

    @Test fun aReporterCannotBorrowTheOriginalProducersCollectionVerb() {
        val text = "青禾研究所报道远山研究院独立调查300家企业，形成《就业样本甲》"
        val stored = stored(provenance().apply { getJSONArray("sources").getJSONObject(0).put("basis", text) })
        val json = JSONArray(stored.claimsJson).apply { getJSONObject(0).put("evidence", text) }
        assertNull(SemanticsCodec.decode(stored.copy(inputText = "岗位增加，因为$text", claimsJson = json.toString())))
    }
}
