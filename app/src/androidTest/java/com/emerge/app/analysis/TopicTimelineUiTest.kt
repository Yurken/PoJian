package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TopicTimelineUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val start = Instant.parse("2026-10-17T01:00:00Z").toEpochMilli()
    private fun work(id: Long) = ViewpointItem(BrowseEvent(id, "reader", start, 0, "原文$id"),
        null, null, false, workIdentity = "work:$id")
    private fun show(data: SwissTopicTimelineData, onTopic: (String) -> Unit = {}, onWork: (BrowseEvent) -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                SwissTopicTimelineContent(data, StatisticsPeriod.DAY, onTopic = onTopic, onWork = onWork)
            }
        } } }
    }

    @Test fun selectingHourChangesWorksAndOpeningAWorkRetainsItsOriginalRecord() {
        val first = work(1)
        val second = work(2)
        val buckets = listOf(first, second).mapIndexed { i, work ->
            TopicTimeBucket(if (i == 0) "09" else "10", start + i * 3_600_000, start + (i + 1) * 3_600_000,
                listOf(work), listOf(TopicTimeGroup("教育", listOf(work))))
        }
        var opened: Long? = null
        show(SwissTopicTimelineData(buckets, listOf(SwissTopicRow("教育", 2, listOf(1, 1))))) { opened = it.id }
        compose.onNodeWithText("关注话题").assertExists()
        compose.onNodeWithText("分时主题分布").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-time-next").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-work-2").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-plot-教育").performTouchInput { click(Offset(width / 4f, height / 2f)) }
        compose.onNodeWithTag("swiss-topic-work-1").assertIsDisplayed()
        compose.onNodeWithTag("swiss-topic-work-2").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-time-next").performClick()
        compose.onNodeWithTag("swiss-topic-work-2").assertIsDisplayed()
        compose.onNodeWithTag("swiss-topic-time-previous").performClick()
        compose.onNodeWithTag("swiss-topic-work-1").performClick()
        compose.runOnIdle { assertEquals(1L, opened) }
        compose.onNodeWithTag("swiss-topic-close").assertDoesNotExist()
    }

    @Test fun pagingKeepsLastTopicAndLastWorkReachableWithOneTopicControl() {
        val works = (1L..37L).map(::work)
        val groups = (1..13).map { TopicTimeGroup("主题$it", works) }
        val bucket = TopicTimeBucket("09", start, start + 3_600_000, works, groups)
        var opened: Long? = null
        var topic: String? = null
        show(SwissTopicTimelineData(listOf(bucket), groups.map { SwissTopicRow(it.title, 37, listOf(37)) }),
            onTopic = { topic = it }) { opened = it.id }
        repeat(3) { compose.onNodeWithTag("swiss-topic-pages-next").performScrollTo().performClick() }
        compose.onNodeWithTag("overview-topic-主题13").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("主题13", topic) }
        compose.onNodeWithTag("swiss-topic-plot-主题13").performScrollTo().performClick()
        compose.onNodeWithTag("swiss-topic-selection-date").assertTextEquals("主题13")
        compose.onNodeWithTag("swiss-topic-work-count").assertTextEquals("37篇")
        compose.onNodeWithTag("swiss-topic-selection-next").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-works-next").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-works").performScrollToNode(hasTestTag("swiss-topic-work-37"))
        compose.onNodeWithTag("swiss-topic-work-37").performClick()
        compose.runOnIdle { assertEquals(37L, opened) }
    }

    @Test fun selectingEmptyTopicCellDoesNotSubstituteAnotherTopicsWorks() {
        val first = work(1)
        val second = work(2)
        val buckets = listOf(TopicTimeBucket("09", start, start + 3_600_000, listOf(first),
            listOf(TopicTimeGroup("教育", listOf(first)))),
            TopicTimeBucket("10", start + 3_600_000, start + 7_200_000, listOf(second),
                listOf(TopicTimeGroup("科技", listOf(second)))))
        show(SwissTopicTimelineData(buckets, listOf(SwissTopicRow("教育", 1, listOf(1, 0)),
            SwissTopicRow("科技", 1, listOf(0, 1))))) {}
        compose.onNodeWithTag("swiss-topic-plot-教育").performTouchInput { click(Offset(width * .75f, height / 2f)) }
        compose.onNodeWithTag("swiss-topic-selection-date").assertTextEquals("教育")
        compose.onNodeWithTag("swiss-topic-work-count").assertTextEquals("0篇")
        compose.onNodeWithText("该时段暂无内容").assertIsDisplayed()
        compose.onNodeWithTag("swiss-topic-work-2").assertDoesNotExist()
        compose.onNodeWithTag("swiss-topic-close").performClick()
        compose.onNodeWithTag("swiss-topic-plot-科技").performScrollTo().performClick()
        compose.onNodeWithTag("swiss-topic-selection-date").assertTextEquals("科技")
        compose.onNodeWithTag("swiss-topic-work-count").assertTextEquals("1篇")
        compose.onNodeWithTag("swiss-topic-work-2").assertIsDisplayed()
    }
}
