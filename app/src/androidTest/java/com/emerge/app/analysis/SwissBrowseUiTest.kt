package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.onAllNodesWithText("来源未核实").assertCountEquals(0)
        compose.onNodeWithTag("history-record-2").performClick()
        compose.onNodeWithTag("swiss-work-detail").assertIsDisplayed()
        compose.onNode(hasText("来源未核实") and hasAnyAncestor(hasTestTag("swiss-work-detail"))).assertDoesNotExist()
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

    @Test fun historyPagesKeepDetailsOnTheSamePageAndFiltersResetToTheFirstPage() {
        val many = (1L..45).map { BrowseEvent(it, "com.ss.android.ugc.aweme", it * 1000, 1, "原文 $it") }
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissHistoryScreen(many, emptyList(), null, emptyMap(), null, {}, {})
        } } }
        awaitTag("history-pages-top-next")
        compose.onNodeWithTag("history-pages-top-previous").assertIsNotEnabled()
        compose.onNodeWithTag("history-pages-top-next").performClick()
        awaitTag("history-record-25")
        compose.onNodeWithTag("history-record-25").performClick()
        compose.onNodeWithTag("history-detail-close").performClick()
        compose.onNodeWithTag("history-record-25").assertIsDisplayed()
        compose.onNodeWithTag("swiss-history-screen").performScrollToNode(hasTestTag("history-pages-bottom-next"))
        compose.onNodeWithTag("history-pages-bottom-next").performClick()
        awaitTag("history-record-5")
        compose.onNodeWithTag("history-pages-top-next").assertIsNotEnabled()
        compose.onNodeWithTag("history-app-com.ss.android.ugc.aweme").performClick()
        awaitTag("history-record-45")
        compose.onNodeWithTag("history-pages-top-previous").assertIsNotEnabled()
        compose.onNodeWithTag("history-search").performTextInput("原文 1")
        awaitTag("history-record-19")
        compose.onNodeWithTag("history-pages-top-next").assertDoesNotExist()
    }

    @Test fun deletingTheLastHistoryPageReturnsToAValidPage() {
        val many = mutableStateOf((1L..21).map { BrowseEvent(it, "reader", it * 1000, 1, "原文 $it") })
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissHistoryScreen(many.value, emptyList(), null, emptyMap(), null, {}, { selected ->
                many.value = many.value.filterNot { it.id == selected.id }
            })
        } } }
        awaitTag("history-pages-top-next")
        compose.onNodeWithTag("history-pages-top-next").performClick()
        awaitTag("history-record-1")
        compose.onNodeWithTag("history-record-1").performClick()
        compose.onNodeWithTag("history-detail-delete").performClick()
        awaitTag("history-record-21")
        compose.onNodeWithTag("history-record-1").assertDoesNotExist()
        compose.onNodeWithTag("history-pages-top-next").assertDoesNotExist()
    }

    @Test fun selectingALateTopicKeepsItsHorizontalScrollPosition() {
        val many = (1L..24).map { BrowseEvent(it, "reader", it * 1000, 1, "观点原文 $it", workKey = "work:$it") }
        val models = many.map { EventSemantics(it.id, it.visibleText, 25_000, listOf(
            ArgumentUnit(it.visibleText, "话题${it.id}", "对象${it.id}", "效果", "支持", uncertain = false))) }
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var topic by remember { mutableStateOf<String?>(null) }
            SwissViewpointsScreen(many, models, null, StatisticsPeriod.HOURS24, 30_000,
                emptyMap(), topic, { topic = it }, {})
        } } }
        awaitTag("viewpoints-topic-话题23")
        // Topics follow first-encounter order; 话题23 is near the far end of the row.
        compose.onNodeWithTag("viewpoints-topic-话题23").performScrollTo()
        fun offset() = compose.onNodeWithTag("viewpoints-topics").fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val before = offset()
        assertTrue(before > 0f)
        compose.onNodeWithTag("viewpoints-topic-话题23").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("1 篇") and hasAnyAncestor(hasTestTag("viewpoints-work-count")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("viewpoints-topic-话题23").assertIsSelected().assertIsDisplayed()
        assertEquals(before, offset(), 1f)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
