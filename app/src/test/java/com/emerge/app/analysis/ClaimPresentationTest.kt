package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorInputs
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ClaimPresentationTest {
    private val quote = "岗位要求相近时，应届生需要结合岗位要求准备项目"
    private fun event(id: Long = 1, at: Long = 1_000, key: String = "work-$id") = BrowseEvent(id, "reader", at, 0, quote,
        endTimestamp = at + 60_000, durationMillis = 60_000, sampleCount = 2, workKey = key)
    private fun presentation(level: ImpactLevel = ImpactLevel.LOW) = ClaimPresentation("求职", quote,
        ImpactSignal(level, "仅评价当前内容的表达。", quote), ImpactSignal(ImpactLevel.LOW, "保留了条件。", quote),
        ImpactSignal(ImpactLevel.LOW, "需要核对具体岗位。", quote), listOf("比较不同岗位要求和适用条件。"))
    private fun unit(value: ClaimPresentation? = presentation()) = ArgumentUnit(quote, "求职", "应届生", "准备", "支持",
        uncertain = false, presentation = value)
    private fun claim(row: BrowseEvent = event(), value: ClaimPresentation? = presentation(), at: Long = 1) =
        ViewpointClaim(quote, "求职", "应届生", "准备", "支持", uncertain = false, analysisSource = "structured_model",
            record = row, presentation = value, analyzedAt = at)
    private fun item(row: BrowseEvent, value: ClaimPresentation? = presentation(), at: Long = 1): ViewpointItem {
        val claim = claim(row, value, at)
        return ViewpointItem(row, claim.label, quote, false, listOf(claim), "structured_model")
    }
    private val window = ObservationWindow(0, 300_000, ZoneId.of("UTC"))

    @Test fun legacyAndExtendedJsonRemainCompatibleWithoutChangingRoomRows() {
        for (annotation in listOf(null, presentation())) {
            val semantics = EventSemantics(1, quote, 4, listOf(unit(annotation)))
            val saved = SemanticsCodec.encode(semantics)
            assertEquals(semantics, SemanticsCodec.decode(saved))
            assertEquals(annotation != null, JSONArray(saved.claimsJson).getJSONObject(0).has("presentation"))
        }
    }
    @Test fun foreignEvidenceAndMalformedRatingsCannotBecomePersistedAnalysis() {
        val semantics = EventSemantics(1, quote, 4, listOf(unit()))
        val saved = SemanticsCodec.encode(semantics)
        for ((field, replacement) in listOf("evidence" to "另一作品中出现的文字", "level" to "certain")) {
            val payload = JSONArray(saved.claimsJson)
            payload.getJSONObject(0).getJSONObject("presentation").getJSONObject("anxiety").put(field, replacement)
            assertNull(SemanticsCodec.decode(saved.copy(claimsJson = payload.toString())))
        }
        assertFalse(presentation().copy(viewpoint = "教育").validFor(quote))
        assertFalse(presentation().copy(category = "某作者").validFor(quote))
    }
    @Test fun colorsCombineEvidenceWithUnionDurationAndKeepUnknownDistinctFromLow() {
        assertEquals(ImpactLevel.LOW, WorkTopicPresentation.impact(listOf(claim()), 600_000))
        assertEquals(ImpactLevel.UNKNOWN, WorkTopicPresentation.impact(listOf(claim(value = null)), 600_000))
        assertEquals(ImpactLevel.UNKNOWN, WorkTopicPresentation.impact(listOf(claim()), null))
        assertEquals(ImpactLevel.UNKNOWN, WorkTopicPresentation.impact(listOf(claim(value = presentation(ImpactLevel.UNKNOWN))), 90_000))
        val medium = listOf(claim(value = presentation(ImpactLevel.MEDIUM)))
        assertEquals(ImpactLevel.MEDIUM, WorkTopicPresentation.impact(medium, 179_999))
        assertEquals(ImpactLevel.HIGH, WorkTopicPresentation.impact(medium, 180_000))
        val high = listOf(claim(value = presentation(ImpactLevel.HIGH)))
        assertEquals(ImpactLevel.MEDIUM, WorkTopicPresentation.impact(high, 59_999))
        assertEquals(ImpactLevel.HIGH, WorkTopicPresentation.impact(high, 60_000))
    }
    @Test fun topicsKeepChronologicalWorksAndDoNotMergeSharedViewpointsOrRevisits() {
        val first = item(event(1, 10_000), presentation(ImpactLevel.LOW))
        val other = item(event(2, 90_000), presentation(ImpactLevel.HIGH))
        val returnVisit = item(event(3, 180_000, "work-1"))
        val result = WorkTopicPresentation.topics(listOf(other, returnVisit, first), window)
        assertEquals(listOf("求职"), result.map { it.title })
        assertEquals(listOf(first.workIdentity, other.workIdentity), result.single().works.map { it.workIdentity })
        assertEquals(120_000L, result.single().works.first().durationMillis(window))
        assertEquals(2, result.single().works.first().records.size)
    }
    @Test fun latestAnalysisOfSameClaimReplacesOldColorWithoutDuplicatingWork() {
        val row = event()
        val merged = ViewpointTimeline.details(listOf(item(row, presentation(ImpactLevel.HIGH), 2), item(row, presentation(), 9))).single()
        assertEquals(1, merged.claims.size)
        assertEquals(ImpactLevel.LOW, merged.claims.single().presentation!!.anxiety.level)
    }
    @Test fun aBatchAnnotationCannotAttachEvidenceFromAnotherWork() {
        val otherText = "另一作品表达了焦虑"
        val annotated = unit(presentation().copy(anxiety = ImpactSignal(ImpactLevel.MEDIUM, "引述了另一条担忧。", otherText)))
        val result = MonitorInputs.allocate(listOf(event(), event(2).copy(visibleText = otherText)), listOf(annotated), 2, "text")
        assertEquals(listOf(1L), result.map { it.eventId })
        assertNull(result.single().claims.single().presentation)
    }
}
