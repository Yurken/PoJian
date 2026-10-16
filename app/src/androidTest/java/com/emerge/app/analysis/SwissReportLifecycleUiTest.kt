package com.emerge.app.analysis

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.SwissReportDialog
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwissReportLifecycleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openedReportKeepsItsWindowAcrossClockRefreshAndStateRestoration() {
        val now = mutableLongStateOf(1_792_000_000_000L)
        val event = BrowseEvent(7, "app", now.longValue - 30 * 60_000, 1, "用于核对报告时窗的原文")
        val restore = StateRestorationTester(compose)
        restore.setContent { EmergeTheme { SwissReportDialog(listOf(event), emptyList(), now.longValue, {}) } }
        compose.onNodeWithText("小时报告").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("过去 1 小时").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("时长未统计").assertExists()

        // ON_RESUME changes the root clock when the system picker returns.
        compose.runOnIdle { now.longValue += 2 * 60 * 60_000 }
        compose.onNodeWithText("1").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("过去 1 小时").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("保存 PDF").assertIsEnabled()
    }
}
