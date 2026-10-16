package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class EmotionTimelineUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val start = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
    private val event = BrowseEvent(1, "reader", start, 0, "岗位减少让我焦虑")
    private val post = EmotionPost("work-1", event, "求职", EmotionTone.NEGATIVE, "岗位减少与求职压力",
        listOf(EmotionEvidence(1, EmotionCategory.ANXIETY, ImpactLevel.MEDIUM, "让我焦虑", "岗位减少", "求职")), emptyList())
    private fun hour(index: Int, posts: List<EmotionPost> = listOf(post)) = EmotionHour(start + index * 3_600_000L,
        start + (index + 1) * 3_600_000L, mapOf(EmotionTone.NEGATIVE to 90_000L), posts, 0)

    @Test fun tappingBarShowsTopicCountSummaryAndVerbatimEmotionAndOpensPost() {
        var opened: Long? = null
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var selected by remember { mutableStateOf<Long?>(null) }
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                SwissEmotionTimelineContent(listOf(hour(0), hour(1, emptyList())), selected, { selected = it },
                    mapOf("reader" to "阅读器")) { opened = it.id }
            }
        } } }
        compose.onNodeWithTag("swiss-emotion-details").assertDoesNotExist()
        compose.onNodeWithTag("swiss-emotion-plot").performTouchInput { click(Offset(width / 4f, height / 2f)) }
        compose.onNodeWithTag("swiss-emotion-date").assertTextEquals(emotionInterval(hour(0)))
        compose.onNodeWithText("求职").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("焦虑 · “让我焦虑”", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("触发语句 · “岗位减少”", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("岗位减少与求职压力", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1L, opened) }
        compose.onNodeWithTag("swiss-emotion-next").performScrollTo().performClick()
        compose.onNodeWithText("该时段暂无内容").assertIsDisplayed()
    }

    @Test fun selectionUsesTimeAcrossRefreshAndChangedCounts() {
        var hours by mutableStateOf(listOf(hour(0), hour(1)))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var selected by remember { mutableStateOf<Long?>(null) }
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                SwissEmotionTimelineContent(hours, selected, { selected = it })
            }
        } } }
        compose.onNodeWithTag("swiss-emotion-plot").performTouchInput { click(Offset(width * .75f, height / 2f)) }
        compose.runOnIdle { hours = listOf(hour(1, listOf(post, post.copy(workIdentity = "work-2", event = event.copy(id = 2)))), hour(2)) }
        compose.onNodeWithTag("swiss-emotion-date").performScrollTo().assertTextEquals(emotionInterval(hour(1)))
        compose.onNodeWithTag("swiss-emotion-previous").assertIsNotEnabled()
        compose.onAllNodesWithText("2篇").assertCountEquals(3)
    }

    @Test fun largeTextKeepsEvidenceAndConcentrationAccessible() {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    SwissEmotionTimelineContent(listOf(hour(0)), start + 1, {})
                    SwissTopicConcentration(TopicConcentration("求职", 40, 40,
                        ConcentrationScope("岗位", "数量", "今年", "减少", 40, 40, .8, true)))
                }
            }
        } } }
        compose.onNodeWithText("触发语句 · “岗位减少”", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("80%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("主题内容 40篇 · 已识别观点 40篇").performScrollTo().assertIsDisplayed()
    }

    @Test fun topicFilterShowsConcentrationForTheSelectedTopic() {
        val events = listOf(event, event.copy(id = 2, workKey = "second"))
        val semantics = events.mapIndexed { index, record -> EventSemantics(record.id, record.visibleText, 1,
            listOf(ArgumentUnit(record.visibleText, if (index == 0) "求职" else "教育", "岗位", "数量", "减少", uncertain = false))) }
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var selected by remember { mutableStateOf<String?>(null) }
            SwissViewpointsScreen(events, semantics, null, StatisticsPeriod.HOURS24, start + 3_600_000,
                emptyMap(), selected, { selected = it }, {})
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewpoints-topic-教育").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("viewpoints-topic-教育").performClick()
        compose.onNodeWithTag("swiss-viewpoints-screen").performScrollToNode(hasTestTag("topic-concentration-教育"))
        compose.onNodeWithTag("topic-concentration-教育").assertExists()
        compose.onNodeWithText("主题内容 1篇 · 已识别观点 1篇").assertExists()
    }
}
