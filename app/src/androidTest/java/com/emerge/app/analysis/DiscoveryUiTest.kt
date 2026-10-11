package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorDiscoverScreen
import com.emerge.app.monitoring.MonitorFinding
import com.emerge.app.monitoring.MonitorState
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        compose.onNodeWithText("已核查 0 条", substring = true).assertDoesNotExist()
        compose.onNodeWithText("观点与依据重复线索").assertDoesNotExist()
        compose.onNodeWithText("“${unchecked.claim}”").assertDoesNotExist()
        compose.onNodeWithText("事实核查").performClick()
        compose.onNodeWithText("尚未核查").assertIsDisplayed()
        compose.onNodeWithText("“${unchecked.claim}”").assertIsDisplayed()
        compose.onNodeWithText("“${repeated.claim}”").assertDoesNotExist()
        compose.onNodeWithText("清空发现").assertDoesNotExist()
    }

    @Test fun detailShowsMatchingOriginalsAndDisappearsWhenFindingIsRemoved() {
        val state = mutableStateOf(MonitorState(findings = listOf(repeated)))
        val events = listOf(
            BrowseEvent(41, "reader", 1791695000000, 32, repeated.claim + "\n第一篇材料保留了页码。"),
            BrowseEvent(42, "video", 1791694000000, 32, repeated.claim + "\n第二篇材料补充了日期。"),
            BrowseEvent(43, "reader", 1791693000000, 32, "无关的天气记录"))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme { Surface(Modifier.fillMaxSize()) {
            MonitorDiscoverScreen(state.value, mapOf("reader" to "阅读器", "video" to "视频"),
                onRecords = {}, onClear = {}, events = events)
        } } } }
        compose.onNodeWithText("查看原文与对比").performClick()
        compose.onNodeWithText("为什么提醒你").assertIsDisplayed()
        compose.onNodeWithTag("discovery-detail").performScrollToNode(hasTestTag("discovery-original-42"))
        compose.onNodeWithTag("discovery-original-42").assertIsDisplayed()
        compose.onNodeWithText("无关的天气记录").assertDoesNotExist()
        compose.runOnIdle { state.value = MonitorState() }
        compose.onNodeWithTag("discovery-detail").assertDoesNotExist()
        compose.onNodeWithText("暂未发现重复观点").assertIsDisplayed()
    }

    @Test fun clearingIsSecondaryAndRequiresConfirmationAndEmptyFactTabIsSpecific() {
        var clears = 0
        render(MonitorState(findings = listOf(repeated))) { clears++ }
        compose.onNodeWithText("事实核查").performClick()
        compose.onNodeWithText("暂无核查结果").assertIsDisplayed()
        compose.onNodeWithContentDescription("发现更多操作").performClick()
        compose.onNodeWithText("清空发现").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithContentDescription("发现更多操作").performClick()
        compose.onNodeWithText("清空发现").performClick()
        compose.onNodeWithText("清空").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    private fun render(state: MonitorState, onClear: () -> Unit = {}) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                MonitorDiscoverScreen(state, mapOf("reader" to "阅读器"), onRecords = {}, onClear = onClear)
            }
        } } }
    }
}
