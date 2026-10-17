package com.emerge.app.analysis

import androidx.activity.compose.setContent
import com.emerge.app.MainActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwissNavigationReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun headerSelectsTheActualWindowAndTogglesRecording() {
        var selected = StatisticsPeriod.HOURS24
        var clicks = 0
        compose.runOnUiThread { compose.activity.setContent {
            var period by remember { mutableStateOf(selected) }
            EmergeTheme { SwissAppHeader("信息概览", period, { selected = it; period = it }, false, "已暂停",
                { clicks++ }, Modifier.width(360.dp)) }
        } }
        val title = compose.onNodeWithText("信息概览").getUnclippedBoundsInRoot()
        val periodBounds = compose.onNodeWithTag("header-period-HOURS24").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(periodBounds.left < title.left)
        compose.onNodeWithTag("header-period-HOURS24").assertTextEquals("24H").assertIsSelected()
        compose.onNodeWithTag("header-period-WEEK").assertTextEquals("7D").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(StatisticsPeriod.WEEK, selected) }
        compose.onNodeWithTag("header-period-MONTH").assertTextEquals("30D").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(StatisticsPeriod.MONTH, selected) }
        compose.onNodeWithTag("header-period-HOURS24").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(StatisticsPeriod.HOURS24, selected) }
        compose.onNodeWithText("已暂停").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
    @Test fun headerKeepsOnlyFactCheckOnViewpoints() {
        var factChecks = 0
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissAppHeader("观点分布", StatisticsPeriod.HOURS24, {}, false, "待授权", {},
                Modifier.width(360.dp), { factChecks++ }, 73, showRecording = false)
        } } }
        compose.onNodeWithText("待授权").assertDoesNotExist()
        val title = compose.onNodeWithText("观点分布").getUnclippedBoundsInRoot()
        val period = compose.onNodeWithTag("header-period-HOURS24").getUnclippedBoundsInRoot()
        val factCheck = compose.onNodeWithTag("viewpoints-fact-check-entry").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(period.left < title.left)
        assertEquals(period.top.value, factCheck.top.value, 0.5f)
        compose.onNodeWithTag("viewpoints-fact-check-entry").performClick()
        compose.runOnIdle { assertEquals(1, factChecks) }
    }
    @Test fun headerOmitsPeriodsOnSettings() {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissAppHeader("系统设置", StatisticsPeriod.HOURS24, {}, false, "待授权", {},
                Modifier.width(360.dp), showPeriods = false)
        } } }
        listOf("HOURS24", "WEEK", "MONTH").forEach {
            compose.onNodeWithTag("header-period-$it").assertDoesNotExist()
        }
        compose.onNodeWithText("待授权").assertIsDisplayed()
    }
    @Test fun reportsSwitchRealWindowsAndKeepExportActions() {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme { SwissReportDialog(emptyList(), emptyList(), 1_792_000_000_000L, {}) } } }
        compose.onNodeWithText("小时报告").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("过去 1 小时").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("月度报告").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("最近 30 天").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存 PDF").assertIsEnabled()
        compose.onNodeWithText("导出 JSON").assertIsEnabled()
        compose.onNodeWithContentDescription("关闭").assertHasClickAction()
    }
}
