package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SwissBrowseUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val rows = listOf(
        BrowseEvent(1, "com.ss.android.ugc.aweme", 1000, 32, "招聘岗位增加"),
        BrowseEvent(2, "com.xingin.xhs", 2000, 32, "学习新的技术"))

    @Test fun applicationFilterCanReturnToAllRecords() {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissHistoryScreen(rows, emptyList(), null, emptyMap(), null, {}, {})
        } } }
        compose.onNodeWithTag("history-app-com.ss.android.ugc.aweme").performClick()
        awaitTag("history-record-1")
        compose.onNodeWithTag("history-record-1").assertExists()
        compose.onNodeWithTag("history-record-2").assertDoesNotExist()
        compose.onNodeWithTag("history-app-all").performClick()
        awaitTag("history-record-2")
        compose.onNodeWithTag("history-record-2").assertExists()
    }

    @Test fun wordFilterClearsAndUnanalyzedRecordStillOpensAndCloses() {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var word by remember { mutableStateOf<String?>("招聘") }
            SwissHistoryScreen(rows, emptyList(), null, emptyMap(), word, { word = null }, {})
        } } }
        awaitTag("history-record-1")
        compose.onNodeWithTag("history-record-2").assertDoesNotExist()
        compose.onNodeWithTag("history-keyword-clear").performClick()
        awaitTag("history-record-2")
        compose.onNodeWithTag("history-record-2").performClick()
        compose.onNodeWithTag("swiss-work-detail").assertIsDisplayed()
        compose.onNode(hasText("来源未核实") and hasAnyAncestor(hasTestTag("swiss-work-detail"))).assertIsDisplayed()
        compose.onNodeWithTag("history-detail-close").performClick()
        compose.onNodeWithTag("swiss-work-detail").assertDoesNotExist()
    }

    @Test fun detailDeleteUsesSelectedRecordWithoutDeletingOtherRows() {
        var requested: Long? = null
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissHistoryScreen(rows, emptyList(), null, emptyMap(), null, {}, { requested = it.id })
        } } }
        awaitTag("history-record-1")
        compose.onNodeWithTag("history-record-1").performClick()
        compose.onNodeWithTag("history-detail-delete").performClick()
        compose.runOnIdle { assertEquals(1L, requested) }
        compose.onNodeWithTag("history-record-2").assertExists()
    }

    @Test fun viewpointTopicPillsUpdateSelectionAndUnknownSourcesStayUnknown() {
        val text = rows.first().visibleText
        val semantics = listOf(EventSemantics(1, text, 1001,
            listOf(ArgumentUnit(text, "就业", "企业", "岗位", "支持", uncertain = false))))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var topic by remember { mutableStateOf<String?>(null) }
            SwissViewpointsScreen(rows, semantics, null, StatisticsPeriod.HOURS24, 3000,
                emptyMap(), topic, { topic = it }, {})
        } } }
        awaitTag("viewpoints-topic-就业")
        compose.onNodeWithTag("viewpoints-topic-就业").performClick().assertIsSelected()
        compose.onNodeWithText("未核实").assertIsDisplayed()
        compose.onNodeWithTag("viewpoints-topic-all").performClick().assertIsSelected()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
