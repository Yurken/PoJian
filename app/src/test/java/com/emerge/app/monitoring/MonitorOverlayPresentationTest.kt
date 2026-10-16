package com.emerge.app.monitoring

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import org.junit.Assert.*
import org.junit.Test

class MonitorOverlayPresentationTest {
    private val now = 1_792_150_000_000L
    private fun report() = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.WEEK, now)

    @Test fun durationTicksReuseTheDisplayButChangedContentInvalidatesIt() {
        val original = BrowseEvent(1, "reader", now - 1000, 32, "当前招聘岗位减少", sampleCount = 1)
        assertTrue(sameOverlayDisplayEvents(listOf(original), listOf(original.copy(endTimestamp = now,
            durationMillis = 1000, sampleCount = 2))))
        assertFalse(sameOverlayDisplayEvents(listOf(original), listOf(original.copy(visibleText = "岗位已经增加"))))
        assertFalse(sameOverlayDisplayEvents(listOf(original), emptyList()))
    }

    @Test fun insufficientConcentrationIsUnknownInsteadOfZero() {
        assertNull(monitorOverlayPresentation(report(), emptyList()).concentration)
    }

    @Test fun sharesTheSufficientExposureValueAndKeepsZeroMeasured() {
        val claim = ClaimObservation(1, now, "reader", "就业", "岗位减少", emptyList(), "", false, Increment.NEW, null)
        val distribution = ExposureDistribution(30, 1, 30, mapOf("就业" to 1.0), mapOf("reader" to 30))
        val exposure = ViewpointExposure("test", "就业", "岗位", "数量", "减少", distribution,
            0.0, null, null, 1, false, claim, listOf(1))
        val snapshot = monitorOverlayPresentation(report().copy(exposure = ExposureAssessment(
            now - 604_800_000, now, distribution, "就业", null, listOf(exposure), listOf(claim))), emptyList())
        assertEquals(0f, snapshot.concentration)
    }

    @Test fun currentDetailsRequireTheSameAppAndCompleteRetainedText() {
        val text = "当前招聘岗位减少，因为本季度岗位同比减少24%。"
        val event = BrowseEvent(1, "reader", now - 1000, 32, text)
        val proof = "本季度岗位同比减少24%"
        val previous = BrowseEvent(2, "reader", now - 2000, 32, proof)
        val fragment = EvidenceFragment(proof, EvidenceType.FACT, historyMatches = listOf(EvidenceHistoryMatch(2, proof)))
        val claim = ClaimObservation(1, now - 1000, "reader", "就业", "当前招聘岗位减少", listOf(proof), "", false,
            Increment.REPEATED, 2, evidenceDetails = listOf(fragment))
        val emotion = EmotionEvidence(1, EmotionCategory.ANXIETY, ImpactLevel.HIGH, "招聘岗位减少", topic = "就业")
        val snapshot = monitorOverlayPresentation(report().copy(claims = listOf(claim), emotionExposure =
            EmotionExposureSummary(works = listOf(EmotionWorkExposure("record:1", listOf(1), emptyList(), listOf("就业"), listOf(emotion))))), listOf(previous, event))
        assertEquals("就业", snapshot.current("reader", text)?.topic)
        assertEquals(1, snapshot.current("reader", text)?.repeatedMaterials)
        assertEquals(listOf("焦虑"), snapshot.current("reader", text)?.emotions)
        assertNull(snapshot.current("other.reader", text))
        assertNull(snapshot.current("reader", "当前招聘岗位减少"))
        assertNull(monitorOverlayPresentation(report().copy(claims = listOf(claim)), emptyList()).current("reader", text))
    }
}
