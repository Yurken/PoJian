package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.RecordsScreen
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordsGroupingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun ocrMaterialShowsSourceAndRemainsSearchable() {
        val rows = listOf(BrowseEvent(10, "com.reader", 1000, 32, "页面标题"),
            BrowseEvent(11, "com.reader", 1100, 0, "招聘岗位正在减少，因为岗位同比减少20%",
                sourceType = "screen_ocr", parentEventId = 10))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            RecordsScreen(rows, 2, mapOf("com.reader" to "阅读器"))
        } } }
        compose.onNodeWithText("搜索原文或应用").performTextInput("招聘岗位")
        compose.onNodeWithText("画面文字识别 · 关联事件 #10").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("招聘岗位正在减少，因为岗位同比减少20%").assertIsDisplayed()
        compose.onNodeWithText("页面标题").assertDoesNotExist()
    }
    @Test fun appGroupsCollapseAndSequenceFollowsTimeInsteadOfDatabaseId() {
        val rows = listOf(BrowseEvent(99, "com.reader", 1000, 32, "先看到的作品原文"),
            BrowseEvent(4, "com.reader", 3000, 32, "后看到的作品原文"))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            RecordsScreen(rows, 2, mapOf("com.reader" to "阅读器"))
        } } }
        compose.onNodeWithText("先看到的作品原文").assertDoesNotExist()
        compose.onNodeWithTag("record-group-com.reader").performClick()
        compose.onNodeWithText("后看到的作品原文").assertIsDisplayed()
        compose.onNodeWithText("#2 · 阅读器").assertIsDisplayed()
        compose.onNodeWithText("#1 · 阅读器").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("record-group-com.reader").performScrollTo().performClick()
        compose.onNodeWithText("后看到的作品原文").assertDoesNotExist()
        compose.onNodeWithText("搜索原文或应用").performTextInput("先看到")
        compose.onNodeWithText("先看到的作品原文").assertIsDisplayed()
        compose.onNodeWithText("后看到的作品原文").assertDoesNotExist()
    }
}
