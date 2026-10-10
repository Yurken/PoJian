package com.emerge.app.monitoring

import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class MonitorInputsTest {
    private fun event(id: Long, text: String, at: Long = 1000, kind: String = "accessibility") =
        BrowseEvent(id = id, packageName = "app", timestamp = at, eventType = 0,
            visibleText = text, endTimestamp = at + 2000, sampleCount = 2, sourceType = kind, workKey = "work:$id")

    @Test fun completedCaptureAppendPreservesUnrelatedResultsButCorrectionDoesNot() {
        val old = event(1, "原文观点\n已有依据")
        assertTrue(MonitorInputs.isAppend(old, old.copy(visibleText = old.visibleText + "\n新条件", sampleCount = 3, endTimestamp = 4000)))
        assertFalse(MonitorInputs.isAppend(old, old.copy(visibleText = "已更正观点\n已有依据")))
        assertFalse(MonitorInputs.isAppend(old, old.copy(workKey = "different")))
        assertFalse(MonitorInputs.isAppend(old.copy(sourceType = "audio_playback"), old.copy(sourceType = "audio_playback")))
    }

    @Test fun manualResultBindsOnlyToRecentRetainedMatchingScreen() {
        val old = event(1, "其他内容", 1000)
        val current = event(2, "作者\n当前屏幕观点\n当前依据", 6000)
        assertEquals(current, MonitorInputs.screenSnapshot(listOf(old, current), "app", "当前屏幕观点\n当前依据", 8500))
        assertNull(MonitorInputs.screenSnapshot(listOf(current), "app", "别的屏幕", 8500))
        assertNull(MonitorInputs.screenSnapshot(listOf(current), "app", "当前依据", 15000))
        assertNull(MonitorInputs.screenSnapshot(listOf(current), "different", "当前依据", 8500))
    }

    @Test fun longVisitUsesLatestObservationForQueueAge() {
        assertEquals(600_000L, MonitorInputs.observationTime(event(1, "原文").copy(endTimestamp = 600_000)))
    }

    @Test fun audioContextDoesNotMoveEarlierQuoteIntoLastSegment() {
        val earlier = event(1, "早期判断。依据甲。", kind = "audio_playback")
        val later = event(2, "后续判断。依据乙。", 5000, "audio_playback")
        val claim = ArgumentUnit("早期判断", "议题", "对象", "维度", "立场", "依据甲", uncertain = false)
        val results = MonitorInputs.allocate(listOf(earlier, later), listOf(claim), 10_000, "audio")
        assertEquals(listOf(earlier.id), results.map { it.eventId })
        assertEquals(earlier.visibleText, results.single().inputText)
        assertTrue(MonitorInputs.allocate(listOf(earlier, later), listOf(claim.copy(evidence = "依据乙")), 10_000, "audio").isEmpty())
    }

    @Test fun matchingVisibleTextWinsOverDuplicatedVisualTranscription() {
        val text = event(1, "当前观点。依据甲。")
        val visual = event(2, text.visibleText, 5000, "visual_model")
        val claim = ArgumentUnit("当前观点", "议题", "对象", "维度", "立场", "依据甲", uncertain = false)
        val results = MonitorInputs.allocate(listOf(text, visual), listOf(claim), 10_000, "frame")
        assertEquals(listOf(text.id), results.map { it.eventId })
    }

    @Test fun completedEmptyResponseIsRetainedWithoutInventingClaims() {
        val snapshot = event(1, "没有论点的文字")
        val result = MonitorInputs.allocate(listOf(snapshot), emptyList(), 10_000, "text").single()
        assertTrue(result.validFor(snapshot))
        assertTrue(result.claims.isEmpty())
    }

    @Test fun emptyPartialSamplesCannotRetractUnsentTextOrEarlierAudioSegments() {
        val long = event(1, "背景".repeat(3000) + "\n未发送的后半段观点")
        assertTrue(MonitorInputs.allocate(listOf(long), emptyList(), 10000, "text",
            long.visibleText.take(6000)).isEmpty())
        val earlier = event(2, "早期片段的原文", kind = "audio_playback")
        val later = event(3, "后续片段的原文", 5000, "audio_playback")
        val result = MonitorInputs.allocate(listOf(earlier, later), emptyList(), 10000, "audio", later.visibleText)
        assertEquals(listOf(later.id), result.map { it.eventId })
    }
}
