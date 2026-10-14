package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorDiscoverScreen
import com.emerge.app.monitoring.MonitorFinding
import com.emerge.app.monitoring.MonitorState
import com.emerge.app.monitoring.WebEvidence
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repeated = MonitorFinding("repeat-1", "event:41", "reader", 1791695100000,
        "cocoon", "repetition", "阅读需要保留原文和适用条件。", "三篇材料反复提到回查原文，其中一篇补充了版本日期。", topic = "阅读与学习")
    private val unchecked = repeated.copy(id = "fact-1", kind = "fact", verdict = "not_checked",
        claim = "这项功能支持所有设备。", explanation = "尚未取得对应版本的资料。")

    @Test fun repetitionAndFactResultsStaySeparateAndClaimsAreQuoted() {
        render(MonitorState(findings = listOf(repeated, unchecked)))
        compose.onNodeWithText("“${repeated.claim}”").assertIsDisplayed()
        compose.onNodeWithText(repeated.explanation).assertDoesNotExist()
        compose.onNodeWithText("已核查 0 条", substring = true).assertDoesNotExist()
        compose.onNodeWithText("观点与依据重复线索").assertDoesNotExist()
        compose.onNodeWithText("“${unchecked.claim}”").assertDoesNotExist()
        saveScreenshot("discovery-repetition-list-optimized.png")
        compose.onNodeWithText("事实核查").performClick()
        compose.onNodeWithText("尚未核查").assertIsDisplayed()
        compose.onNodeWithText("“${unchecked.claim}”").assertIsDisplayed()
        compose.onNodeWithText("“${repeated.claim}”").assertDoesNotExist()
        compose.onNodeWithText("清空发现").assertDoesNotExist()
        saveScreenshot("discovery-fact-list-optimized.png")
    }

    @Test fun detailShowsMatchingOriginalsAndDisappearsWhenFindingIsRemoved() {
        val state = mutableStateOf(MonitorState(findings = listOf(repeated)))
        val events = listOf(
            BrowseEvent(42, "video", 1791694000000, 32, repeated.claim + "\n第二篇材料补充了日期。"),
            BrowseEvent(41, "reader", 1791695000000, 32, repeated.claim + "\n第一篇材料保留了页码。"),
            BrowseEvent(43, "reader", 1791693000000, 32, "无关的天气记录"))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme { Surface(Modifier.fillMaxSize()) {
            MonitorDiscoverScreen(state.value, mapOf("reader" to "阅读器", "video" to "视频"),
                onRecords = {}, events = events)
        } } } }
        compose.onNodeWithText("查看原文与对比").performClick()
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasTestTag("discovery-original-42"))
        compose.onNodeWithTag("discovery-original-42").assertIsDisplayed()
        assertTrue("Matching originals are ordered from newest to oldest",
            compose.onNodeWithTag("discovery-original-41").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("discovery-original-42").fetchSemanticsNode().boundsInRoot.top)
        saveScreenshot("discovery-repetition-detail-optimized.png")
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasText("为什么提醒你"))
        compose.onNodeWithText("为什么提醒你").assertIsDisplayed()
        assertTrue("Matching originals appear before the reminder explanation",
            compose.onNodeWithTag("discovery-original-42").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("为什么提醒你").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasText(repeated.explanation))
        compose.onNodeWithText(repeated.explanation).assertIsDisplayed()
        compose.onNodeWithText("无关的天气记录").assertDoesNotExist()
        compose.runOnIdle { state.value = MonitorState() }
        compose.onNodeWithTag("discovery-detail").assertDoesNotExist()
        compose.onNodeWithText("暂未发现重复观点").assertIsDisplayed()
    }

    @Test fun repetitionDetailClosesWithAnAccessibleIcon() {
        assertDetailClosesWithIcon(repeated)
    }

    @Test fun factDetailClosesWithAnAccessibleIcon() {
        assertDetailClosesWithIcon(unchecked)
    }

    @Test fun repetitionOriginalCanExpandToTheLastLineAndCollapseAgain() {
        assertOriginalCanExpandAndCollapse(repeated)
    }

    @Test fun factOriginalCanExpandToTheLastLineAndCollapseAgain() {
        assertOriginalCanExpandAndCollapse(unchecked)
    }

    @Test fun factDetailPresentsTheConclusionBeforeTheClaim() {
        render(MonitorState(findings = listOf(unchecked)))
        openDetail(unchecked)
        val detail = hasAnyAncestor(hasTestTag("discovery-detail"))
        val verdict = compose.onNode(hasText("尚未核查") and detail)
        val explanation = compose.onNode(hasText(unchecked.explanation) and detail)
        val claim = compose.onNode(hasText(unchecked.claim) and detail)
        verdict.assertIsDisplayed()
        explanation.assertIsDisplayed()
        assertTrue("The verdict comes before the original claim",
            verdict.fetchSemanticsNode().boundsInRoot.top < claim.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun validSourceOpensItsExactUrlFromTheIcon() {
        val source = source("https://example.org/evidence")
        val opened = mutableListOf<String>()
        render(MonitorState(findings = listOf(unchecked.copy(sources = listOf(source)))),
            uriHandler = object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            })
        openDetail(unchecked)
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasContentDescription("打开来源"))
        compose.onNodeWithText("打开来源").assertDoesNotExist()
        saveScreenshot("discovery-fact-detail-optimized.png")
        compose.onNodeWithContentDescription("打开来源").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.org/evidence"), opened) }
        compose.onNodeWithText("无法打开链接，请稍后重试").assertDoesNotExist()
    }

    @Test fun invalidSourceCannotBeOpened() {
        val opened = mutableListOf<String>()
        render(MonitorState(findings = listOf(unchecked.copy(sources = listOf(source("http://example.org/evidence"))))),
            uriHandler = object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            })
        openDetail(unchecked)
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasContentDescription("打开来源"))
        compose.onNodeWithContentDescription("打开来源").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun sourceOpenFailureKeepsTheDetailAndShowsTheExistingError() {
        render(MonitorState(findings = listOf(unchecked.copy(sources = listOf(source("https://example.org/evidence"))))),
            uriHandler = object : UriHandler {
                override fun openUri(uri: String) { throw IllegalArgumentException("No handler installed") }
            })
        openDetail(unchecked)
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasContentDescription("打开来源"))
        compose.onNodeWithContentDescription("打开来源").performClick()
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasText("无法打开链接，请稍后重试"))
        compose.onNodeWithText("无法打开链接，请稍后重试").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
    }

    @Test fun redundantHeaderAndClearingAreRemovedAndEmptyFactTabIsSpecific() {
        render(MonitorState(findings = listOf(repeated)))
        compose.onNodeWithText("发现").assertDoesNotExist()
        compose.onNodeWithContentDescription("发现更多操作").assertDoesNotExist()
        compose.onNodeWithText("清空发现").assertDoesNotExist()
        compose.onNodeWithText("清空全部发现？").assertDoesNotExist()
        compose.onNodeWithText("事实核查").performClick()
        compose.onNodeWithText("暂无核查结果").assertIsDisplayed()
    }

    private fun assertDetailClosesWithIcon(finding: MonitorFinding) {
        render(MonitorState(findings = listOf(finding)))
        openDetail(finding)
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭").assertHasClickAction().performClick()
        compose.onNodeWithTag("discovery-detail").assertDoesNotExist()
        compose.onNodeWithTag("discovery-${finding.id}").assertIsDisplayed()
    }

    private fun assertOriginalCanExpandAndCollapse(finding: MonitorFinding) {
        val original = listOf(finding.claim, "第一段保留作者的条件。", "第二段说明材料的日期。",
            "第三段交代记录的来源。", "第四段列出限定的范围。", "第五段保留不同的意见。",
            "第六段说明相关的背景。", "最后一段原文仍然可查。").joinToString("\n")
        val tailOffset = original.indexOf("最后一段")
        render(MonitorState(findings = listOf(finding)),
            events = listOf(BrowseEvent(41, "reader", 1791695000000, 32, original)))
        openDetail(finding)
        val originalScope = hasAnyAncestor(hasTestTag("discovery-original-41"))
        val expand = hasContentDescription("展开原文") and originalScope
        val collapse = hasContentDescription("收起原文") and originalScope
        val text = hasText(original) and originalScope
        compose.onNodeWithTag("discovery-detail").performScrollToNode(expand)
        compose.onNodeWithText("展开原文").assertDoesNotExist()
        compose.onNode(expand).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        val folded = textLayout(text)
        assertTrue("The last paragraph is clipped before expansion",
            folded.getLineEnd(folded.lineCount - 1, visibleEnd = true) < tailOffset)
        compose.onNode(expand).performClick()
        compose.onNodeWithTag("discovery-detail").performScrollToNode(collapse)
        compose.onNode(collapse).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        compose.onNodeWithText("收起原文").assertDoesNotExist()
        val expanded = textLayout(text)
        assertTrue("Expansion lays out the final paragraph", expanded.lineCount > folded.lineCount)
        assertEquals(original.length, expanded.getLineEnd(expanded.lineCount - 1, visibleEnd = true))
        compose.onNode(collapse).performClick()
        compose.onNodeWithTag("discovery-detail").performScrollToNode(expand)
        compose.onNode(expand).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        val foldedAgain = textLayout(text)
        assertTrue("Collapsing hides the last paragraph again",
            foldedAgain.getLineEnd(foldedAgain.lineCount - 1, visibleEnd = true) < tailOffset)
    }

    private fun textLayout(matcher: SemanticsMatcher): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun openDetail(finding: MonitorFinding) {
        if (finding.kind == "fact") compose.onNodeWithText("事实核查").performClick()
        compose.onNodeWithText(if (finding.kind == "fact") "查看核查依据" else "查看原文与对比").performClick()
    }

    private fun source(url: String) = WebEvidence(url, "功能版本说明", "仅支持部分设备。", "当前版本仅支持部分设备。")

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun render(state: MonitorState, events: List<BrowseEvent> = emptyList(), uriHandler: UriHandler? = null) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            CompositionLocalProvider(LocalUriHandler provides (uriHandler ?: LocalUriHandler.current)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MonitorDiscoverScreen(state, mapOf("reader" to "阅读器"), onRecords = {}, events = events)
                }
            }
        } } }
    }
}
