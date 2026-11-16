package com.emerge.app.capture

import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.monitoring.MonitorPolicy
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptureTextCleanerTest {
    private val config = TextServiceConfig(true, "https://example.org", "token")
    private fun selection(text: String, drops: String = "[]", trims: String = "[]") =
        JSONObject("""{"version":1,"input_hash":"${MonitorPolicy.hash(text)}","drop_lines":$drops,"trim_suffixes":$trims}""")

    @Test fun modelRemovesUiLinesBeforeReturningTextAndCachesExactInput() = runBlocking {
        var calls = 0
        val cleaner = CaptureTextCleaner(request = { text, _, _ -> calls++; selection(text, "[1,2]") })
        val raw = "2026年增长20%，仅限本地。\n热搜榜\n某产品促销入口"
        repeat(2) { assertEquals("2026年增长20%，仅限本地。", cleaner.clean(raw, config, "scope") { true }) }
        assertEquals(1, calls)
        cleaner.clean(raw, config, "new-authorization") { true }
        assertEquals(2, calls)
    }

    @Test fun malformedOrRewrittenResultFallsBackWithoutLosingBody() = runBlocking {
        val raw = "2026年增长20%，仅限本地。"
        val bad = listOf(selection(raw, "[99]"), selection(raw, "[true]"), selection(raw, "[0,0]"),
            selection(raw, trims = """[{"line":0,"suffix":"仅限本地。"}]"""),
            selection("different"), selection(raw).put("text", "改写的正文"))
        for (reply in bad) {
            val cleaner = CaptureTextCleaner(request = { _, _, _ -> reply })
            assertEquals(raw, cleaner.clean(raw, config, "scope") { true })
        }
    }

    @Test fun offlineDisabledAndShortDeadlinePreserveLocallyCleanedText() = runBlocking {
        val raw = "真实正文\n未点赞，喜欢16.2万，按钮\n16.2万\n3023"
        var calls = 0
        val cleaner = CaptureTextCleaner(timeoutMillis = 25, request = { _, _, _ -> calls++; delay(500); error("late") })
        assertEquals("真实正文", cleaner.clean(raw, config.copy(enabled = false), "scope") { true })
        assertEquals(0, calls)
        assertEquals("真实正文", cleaner.clean(raw, config, "scope") { true })
        assertEquals(1, calls)
        assertEquals("真实正文", cleaner.clean(raw, config, "scope") { true })
        assertEquals(1, calls)
    }

    @Test fun revocationDuringRequestDiscardsModelResultAndParentCancellationPropagates() = runBlocking {
        var current = true
        val cleaner = CaptureTextCleaner(request = { text, _, _ -> current = false; selection(text, "[0]") })
        assertEquals("真实正文", cleaner.clean("真实正文", config, "scope") { current })
        val cancelled = CaptureTextCleaner(request = { _, _, _ -> throw CancellationException("cancelled") })
        try { cancelled.clean("真实正文", config, "scope") { true }; fail("must cancel") }
        catch (_: CancellationException) { }
    }

    @Test fun cleanedSamplesKeepIdentityIntervalsAndRawContinuityWithoutPersistingNoise() {
        val tracker = CaptureSessionTracker()
        val first = tracker.sample("app", "正文\n热搜榜", 1000, 1000, 32,
            WorkHint(title = "相同作品的稳定标题", author = "作者"))
        val cleaned = first.withCleanedText("正文")
        tracker.saved(cleaned, 7)
        val next = tracker.sample("app", "正文\n新句子\n热搜榜", 3000, 3000, 32,
            WorkHint(title = "相同作品的稳定标题", author = "作者"))
        val result = next.withCleanedText("正文\n新句子")
        assertTrue(result.continuing)
        assertEquals(7, result.event.id)
        assertEquals("正文\n新句子", result.event.visibleText)
        assertEquals(2000, result.event.durationMillis)
        assertEquals(next.event.visibilityIntervals, result.event.visibilityIntervals)
        assertEquals(next.event.workKey, result.event.workKey)
        assertEquals(next.rawText, result.rawText)
    }

    @Test fun pauseWhileModelIsPendingDoesNotBecomeMeasuredExposure() {
        val tracker = CaptureSessionTracker()
        val hint = WorkHint(title = "相同作品的稳定标题", author = "作者")
        val sample = tracker.sample("app", "正文", 1000, 1000, 32, hint)
        tracker.pause()
        tracker.saved(sample.withCleanedText("正文"), 7)
        val next = tracker.sample("app", "正文", 4000, 4000, 32, hint)
        assertEquals(0L, next.event.durationMillis)
        tracker.saved(next.withCleanedText("正文"), 7)
        assertEquals(2000L, tracker.sample("app", "正文", 6000, 6000, 32, hint).event.durationMillis)
    }

    @Test fun cloudOffAndOversizedInputNeverReachProvider() = runBlocking {
        val cleaner = CaptureTextCleaner(request = { _, _, _ -> error("must not upload") })
        assertEquals("正文", cleaner.clean("正文", config, "scope") { false })
        val raw = (0..256).joinToString("\n") { "正文$it" }
        assertEquals(raw, cleaner.clean(raw, config, "scope") { true })
    }

    @Test fun privacyMaskingPreventsCleanupPreflightFromLeakingTheOriginal() = runBlocking {
        val raw = "联系方式13800138000，正文观点保留。"
        val cleaner = CaptureTextCleaner(request = { _, _, _ -> error("Sensitive preflight must stay local") })
        assertEquals(raw, cleaner.clean(raw, config, "scope") { true })
    }

    @Test fun emojiAndSuffixEditsPreserveExactQuotedWords() {
        val raw = "😀正文：仅限2026年 广告 展开\n相关搜索"
        val result = CaptureCleanupProtocol.decode(raw, selection(raw, "[1]",
            """[{"line":0,"suffix":" 广告 展开"}]"""))
        assertEquals("😀正文：仅限2026年", result)
    }
}
