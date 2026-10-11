package com.emerge.app.monitoring

import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class MonitorInputsTest {
    private fun event(id: Long, text: String, at: Long = 1000, kind: String = "accessibility") =
        BrowseEvent(id = id, packageName = "app", timestamp = at, eventType = 0,
            visibleText = text, endTimestamp = at + 2000, sampleCount = 2, sourceType = kind, workKey = "work:$id")

    @Test fun backlogUsesOnlyRecentAuthorizedUnanalyzedCaptureText() {
        val now = 100_000_000L
        val recent = event(1, "已保存但尚未分析的文字", now - 10_000)
        val analyzed = recent.copy(id = 2)
        val revised = recent.copy(id = 3, visibleText = "修订后的文字")
        val results = listOf(EventSemantics(2, analyzed.visibleText, now, emptyList()),
            EventSemantics(3, recent.visibleText, now, emptyList()))
        val excluded = listOf(recent.copy(id = 4, packageName = "not-allowed"),
            recent.copy(id = 5, sourceType = "visual_model"), recent.copy(id = 6, sourceType = "screen_ocr"),
            recent.copy(id = 7, sourceType = "audio_import"), recent.copy(id = 8, sourceType = "recommendation_list"),
            recent.copy(id = 9, timestamp = now - 86_400_000, endTimestamp = now - 86_400_000),
            recent.copy(id = 10, endTimestamp = now + 1), recent.copy(id = 11, visibleText = " "))
        assertEquals(setOf(1L, 3L), MonitorInputs.pendingTextBatches(listOf(recent, analyzed, revised) + excluded,
            results, setOf("app"), now).flatten().map { it.id }.toSet())
        assertTrue(MonitorInputs.pendingTextBatches(listOf(recent), emptyList(), emptySet(), now).isEmpty())
    }

    @Test fun audioBacklogStaysWithinSessionWindowAndPayloadBounds() {
        val rows = (1L..9L).map { id -> event(id, "片段$id", id * 1000, "audio_playback").copy(audioSessionId = "session") }
        val distant = event(10, "稍后的片段", 100_000, "audio_playback").copy(audioSessionId = "session")
        val other = event(11, "其他应用片段", 102_000, "audio_playback").copy(audioSessionId = "session", packageName = "other")
        val long = (12L..14L).map { id -> event(id, "长".repeat(3000), 104_000 + id, "audio_playback").copy(audioSessionId = "long") }
        val all = rows + distant + other + long
        val batches = MonitorInputs.pendingTextBatches(all, emptyList(), setOf("app", "other"), 110_000)
        assertEquals(all.map { it.id }.toSet(), batches.flatten().map { it.id }.toSet())
        assertTrue(batches.all { it.size <= 4 && it.last().timestamp - it.first().timestamp <= 30_000 })
        assertTrue(batches.all { it.map { row -> row.packageName }.distinct().size == 1 })
        assertTrue(batches.all { it.joinToString("\n") { row -> row.visibleText }.length <= 6000 })
        assertEquals(14L, batches.first().single().id)
    }

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

    @Test fun allocatedEvidenceKeepsItsCaseAndMaterialIdentityOnItsOriginalAudioSegment() {
        val earlier = event(1, "早期判断。小林引用《调查报告》发现依据甲。", kind = "audio_playback")
        val later = event(2, "后续判断。小王引用《另一报告》发现依据乙。", 5000, "audio_playback")
        val claim = ArgumentUnit("早期判断", "议题", "对象", "维度", "立场", "小林引用《调查报告》发现依据甲",
            uncertain = false, evidenceActors = listOf("小林"), evidenceOrigins = listOf("《调查报告》"))
        val result = MonitorInputs.allocate(listOf(earlier, later), listOf(claim), 10_000, "audio").single()
        assertEquals(earlier.id, result.eventId)
        assertEquals(claim, result.claims.single())
        assertEquals(listOf("小林"), result.claims.single().evidenceActors)
        assertEquals(listOf("《调查报告》"), result.claims.single().evidenceOrigins)
    }

    @Test fun allocationCannotBorrowCaseOrMaterialNamesFromOtherEvidenceOrContext() {
        val original = event(1, "当前观点。甲公司发布《原报告》，招聘数量减少。旁注：乙公司发布《另一报告》。")
        val claim = ArgumentUnit("当前观点", "议题", "对象", "维度", "立场", "甲公司发布《原报告》，招聘数量减少",
            uncertain = false, evidenceActors = listOf("甲公司"), evidenceOrigins = listOf("《原报告》"))
        assertEquals(claim, MonitorInputs.allocate(listOf(original), listOf(claim), 10_000, "text")
            .single().claims.single())
        assertTrue(MonitorInputs.allocate(listOf(original), listOf(claim.copy(evidenceActors = listOf("乙公司"))),
            10_000, "text").isEmpty())
        assertTrue(MonitorInputs.allocate(listOf(original), listOf(claim.copy(evidenceOrigins = listOf("《另一报告》"))),
            10_000, "text").isEmpty())
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
