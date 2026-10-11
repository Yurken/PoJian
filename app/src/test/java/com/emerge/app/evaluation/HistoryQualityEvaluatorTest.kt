package com.emerge.app.evaluation

import org.junit.Assert.*
import org.junit.Test

class HistoryQualityEvaluatorTest {
    @Test fun confusionMetricsIncludeAbstentionsAndUseIndependentDenominators() {
        val score = HistoryQualityEvaluator.score(HistoryQualityEvaluator.incrementLabels, listOf(
            "FIRST" to "FIRST", "NEW" to "REPEATED", "REPEATED" to "REPEATED",
            "UNKNOWN" to "UNKNOWN", "NEW" to "UNKNOWN", "NO_CLAIM" to "NO_CLAIM"))
        assertEquals(6, score.total)
        assertEquals(4, score.correct)
        val repeated = score.classes.single { it["label"] == "REPEATED" }
        assertEquals(0.5, repeated["precision"] as Double, 0.000001)
        assertEquals(1.0, repeated["recall"] as Double, 0.000001)
        assertEquals(2.0 / 3, repeated["f1"] as Double, 0.000001)
        val added = score.classes.single { it["label"] == "NEW" }
        assertNull(added["precision"])
        assertEquals(0.0, added["recall"] as Double, 0.000001)
        assertEquals(0.0, added["f1"] as Double, 0.000001)
        assertEquals(1, score.matrix[1][4])
    }

    @Test fun unsupportedLabelsCannotSilentlyDropFromTheConfusionMatrix() {
        try {
            HistoryQualityEvaluator.score(HistoryQualityEvaluator.incrementLabels, listOf("REPEATED" to "MISSING"))
            fail("Expected an invalid prediction label to be rejected")
        } catch (_: IllegalStateException) { }
    }

    @Test fun escapedAnnotationTextIsLosslessAndUnknownEscapesAreRejected() {
        assertEquals("正文\n依据\t引用\\名称", HistoryQualityEvaluator.decodeCell("正文\\n依据\\t引用\\\\名称"))
        try {
            HistoryQualityEvaluator.decodeCell("不应静默处理\\x")
            fail("Expected an unknown escape to be rejected")
        } catch (_: IllegalStateException) { }
    }

    @Test fun localSentenceMatchingDoesNotTreatAnInlineReasonAsAClaimExtractionFailure() {
        assertTrue(HistoryQualityEvaluator.quoteMatches("local", "当前就业形势很差",
            "当前就业形势很差，因为招聘岗位同比减少20%"))
        assertFalse(HistoryQualityEvaluator.quoteMatches("structured", "当前就业形势很差",
            "当前就业形势很差，因为招聘岗位同比减少20%"))
        assertFalse(HistoryQualityEvaluator.quoteMatches("local", "", "任意观点"))
        assertFalse(HistoryQualityEvaluator.quoteMatches("local", "本河段水质持续改善", "当前就业形势很差"))
    }
}
