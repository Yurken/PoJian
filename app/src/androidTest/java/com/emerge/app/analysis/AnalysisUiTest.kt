package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import java.time.Instant
import java.time.ZoneId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hourDetailsSeparateDurationAndExposeOriginalEvidence() {
        val now = Instant.parse("2026-10-08T04:00:00Z").toEpochMilli()
        val start = Instant.parse("2026-10-07T16:10:00Z").toEpochMilli()
        val records = List(2) { index -> BrowseEvent(index + 1L, "com.example.reader",
            start + index * 600_000, 32, "当前就业形势很差，因为招聘岗位同比减少20%",
            endTimestamp = start + index * 600_000 + 10_000, durationMillis = 10_000, sampleCount = 2) }
        val report = CognitiveAnalyzer().analyze(records, StatisticsPeriod.DAY, now, ZoneId.of("Asia/Shanghai"))
        render {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = PageBackground)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AnalysisCards(AnalysisState(report = report, active = true), StatisticsPeriod.DAY,
                            records, {}, { _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("0时，2条记录，估计可见停留20 秒，点击查看详情")
            .performScrollTo().performClick()
        compose.onNodeWithText("00:00—01:00").assertIsDisplayed()
        compose.onNodeWithText("2 条可用接触记录 · 20 秒").assertIsDisplayed()
        compose.onAllNodesWithText("当前采集原文：", substring = true).onFirst().assertExists()
        compose.onNodeWithTag("hourly-review-details")
            .performScrollToNode(hasText("比较的先前事件 #1"))
        compose.onNodeWithText("比较的先前事件 #1").assertIsDisplayed()
        saveScreenshot("emerge-hourly-qa.png")
    }

    @Test fun midnightSessionRemainsTraceableInItsCoveredHour() {
        val start = Instant.parse("2026-10-07T15:59:00Z").toEpochMilli()
        val records = listOf(BrowseEvent(9, "com.example.reader", start, 32,
            "当前就业形势很差，因为招聘岗位同比减少20%", endTimestamp = start + 120_000,
            durationMillis = 120_000, sampleCount = 61))
        val report = CognitiveAnalyzer().analyze(records, StatisticsPeriod.DAY,
            start + 180_000, ZoneId.of("Asia/Shanghai"))
        render {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AnalysisCards(AnalysisState(report = report, active = true), StatisticsPeriod.DAY,
                        records, {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithContentDescription("0时，1条记录，估计可见停留1 分 0 秒，点击查看详情")
            .performScrollTo().performClick()
        compose.onNodeWithText("0 条时长未知 · 1 个观点候选").assertIsDisplayed()
        compose.onAllNodesWithText("事件 #9", substring = true).onFirst().assertExists()
        compose.onNodeWithText("当前采集原文：", substring = true).assertExists()
    }

    @Test fun recordSearchUsesAllProvidedHistoryAndDeletionTargetsMatchingRecord() {
        val records = listOf(
            BrowseEvent(1, "com.example.reader", 1_000, 32, "目标资料：嵌入式岗位要求"),
            BrowseEvent(2, "com.example.other", 2_000, 32, "另一条无关内容"))
        var deleted: Long? = null
        render {
            MaterialTheme { RecordsScreen(records, 2, mapOf("com.example.reader" to "资料阅读器"),
                onDelete = { deleted = it.id }) }
        }
        compose.onNodeWithText("搜索原文或应用").performTextInput("嵌入式")
        compose.onNodeWithText("目标资料：嵌入式岗位要求").assertIsDisplayed()
        compose.onNodeWithText("另一条无关内容").assertDoesNotExist()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(1L, deleted) }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
        compose.waitForIdle()
    }
}
