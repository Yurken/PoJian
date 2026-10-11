package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecentContentCloudTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val midnight = LocalDate.of(2026, 10, 11).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val now = midnight + 12 * 3_600_000L
    private fun event(id: Long, text: String, time: Long = now - 60_000L) = BrowseEvent(id, "reader", time, 32, text)

    @Test fun firstCardChangesWithPeriodAndClearsWhenThereAreNoKeywords() {
        val period = mutableStateOf(StatisticsPeriod.DAY)
        val events = listOf(event(1, "教育 科技 教育"), event(2, "教育 健康"), event(3, "历史", midnight - 1000))
        render(events, period, semantics = listOf(
            concepts(events[0], "教育", "科技"), concepts(events[1], "教育", "健康"),
            concepts(events[2], "历史", "文化")))
        awaitWord("教育，2 条记录")
        compose.onNodeWithTag("recent-content-card").assertIsDisplayed()
        compose.onNodeWithContentDescription("教育，2 条记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("历史，1 条记录").assertDoesNotExist()
        compose.onNodeWithText("7 天").performClick()
        awaitWord("历史，1 条记录")
        compose.onNodeWithContentDescription("历史，1 条记录").assertIsDisplayed()
        compose.onNode(hasText("今天") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("历史，1 条记录").fetchSemanticsNodes().isEmpty() }
        screenshot("pojian-word-cloud-light.png")
        render(listOf(event(4, "点赞 评论 123")), period)
        compose.waitUntil(5000) { compose.onAllNodesWithText("暂无关键词").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recent-content-cloud").assertDoesNotExist()
    }

    @Test fun cloudSupportsDarkThemeAndLargeText() {
        val texts = listOf("教育 科技 人工智能 阅读 音乐", "教育 科技 健康 运动 旅行", "教育 科技 科学 文化 历史", "教育 经济 城市 环境 电影", "科技 学习 工作 摄影 设计")
        val events = texts.mapIndexed { index, text -> event(index.toLong(), text) }
        render(events, mutableStateOf(StatisticsPeriod.DAY), dark = true,
            semantics = events.mapIndexed { index, event -> concepts(event,
                if (index < 4) "教育" else "科技", listOf("数字学习", "健康管理", "科学文化", "城市经济", "视觉设计")[index]) })
        awaitWord("教育，4 条记录")
        compose.onNodeWithTag("recent-content-cloud").assertExists()
        screenshot("pojian-word-cloud-dark-large.png")
    }

    @Test fun referenceCompositionKeepsWordsSeparateAndOpensMatchingOriginals() {
        val counts = listOf("求职竞争" to 12, "招聘需求" to 9, "教育与升学" to 6,
            "大模型" to 5, "薪资待遇" to 4, "人工智能" to 3, "职业规划" to 2,
            "考研政策" to 2, "技能门槛" to 1, "工作环境" to 1, "行业前景" to 1)
        val events = (1..12).map { event(it.toLong(), "第${it}条采集原文：保留数据来源与适用条件。") }
        val assignments = List(events.size) { mutableListOf<String>() }
        counts.forEach { (word, count) ->
            assignments.indices.sortedBy { assignments[it].size }.take(count).forEach { assignments[it] += word }
        }
        val semantics = events.mapIndexed { index, e ->
            EventSemantics(e.id, e.visibleText, now, assignments[index].chunked(2).map {
                ArgumentUnit(e.visibleText, it[0], it.getOrElse(1) { "" }, "", "")
            })
        }
        render(events, mutableStateOf(StatisticsPeriod.DAY), semantics = semantics)
        awaitWord("求职竞争，12 条记录")
        compose.onNodeWithTag("recent-content-cloud").performScrollTo()
        val bounds = counts.map { (word, count) ->
            compose.onNodeWithContentDescription("$word，$count 条记录")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }
        bounds.forEachIndexed { index, rect ->
            bounds.drop(index + 1).forEach { other -> assertFalse("Word touch targets must not overlap", rect.overlaps(other)) }
        }
        screenshot("pojian-word-cloud-reference-light.png")
        compose.onNodeWithContentDescription("招聘需求，9 条记录").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("今天 · 9 条相关记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recent-content-details").performScrollToNode(hasTestTag("recent-content-original-9"))
        compose.onNodeWithText(events[8].visibleText).assertIsDisplayed()
        compose.onNodeWithTag("recent-content-original-10").assertDoesNotExist()
        screenshot("pojian-word-cloud-originals.png")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("recent-content-details").assertDoesNotExist()
        render(events, mutableStateOf(StatisticsPeriod.DAY), dark = true, semantics = semantics)
        awaitWord("求职竞争，12 条记录")
        counts.forEach { (word, count) ->
            compose.onNodeWithContentDescription("$word，$count 条记录").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("overview-list").performScrollToNode(hasText("今日观察"))
        screenshot("pojian-word-cloud-reference-dark-large.png")
    }

    private fun concepts(event: BrowseEvent, topic: String, subject: String) = EventSemantics(
        event.id, event.visibleText, now, listOf(ArgumentUnit(event.visibleText, topic, subject, "", "")))

    private fun awaitWord(description: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun render(events: List<BrowseEvent>, period: androidx.compose.runtime.MutableState<StatisticsPeriod>, dark: Boolean = false,
        semantics: List<EventSemantics> = emptyList()) {
        compose.runOnUiThread { compose.activity.setContent {
            EmergeTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark) 1.5f else 1f)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        OverviewScreen(calculateStatistics(events, period.value, now), period.value, { period.value = it },
                            false, "已暂停", emptyMap(), {}, {}, rawEvents = events, now = now, semantics = semantics)
                    }
                }
            }
        } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
