package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun purposePresetsPreserveCustomTextAndCancelDoesNotApplyDraft() {
        val initial = "比较嵌入式岗位的要求"
        val purpose = mutableStateOf(initial)
        val changes = mutableListOf<String>()
        render {
            EmergeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AnalysisCards(AnalysisState(purpose = purpose.value), StatisticsPeriod.DAY,
                        emptyList(), {}, { _, _ -> }, onPurposeChange = {
                            changes += it
                            purpose.value = it
                        })
                }
            }
        }
        compose.onNodeWithTag("purpose-edit").performClick()
        compose.onNodeWithTag("purpose-input").assertTextContains(initial)
        compose.onNodeWithText("专题学习").performClick()
        compose.onNodeWithTag("purpose-input").assertTextContains("$initial；专题学习")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        compose.onNodeWithTag("purpose-edit").performClick()
        compose.onNodeWithTag("purpose-input").assertTextContains(initial)
        compose.onNodeWithTag("purpose-input").performTextReplacement("核查招聘数字的来源")
        compose.onNodeWithText("保存目的").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("核查招聘数字的来源"), changes) }
        compose.onNodeWithText("核查招聘数字的来源").assertIsDisplayed()
    }

    @Test fun unknownDurationRemainsUnknownInOverviewAndHourlyReview() {
        val start = todayStart()
        val now = start + 12 * 3_600_000
        val records = listOf(BrowseEvent(51, "com.example.reader", start + 8 * 3_600_000,
            32, "机器人课程提供了多种实验资料，可以比较教学方案。"))
        val report = CognitiveAnalyzer().analyze(records, StatisticsPeriod.DAY, now)
        renderOverview(records, report, now, dark = false)
        compose.onNodeWithText("未知", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1 条未统计时长", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("24 小时信息回顾").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hourly-hour-options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("8时，1条记录，未统计时长，点击查看详情")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText("08:00—09:00") and hasAnyAncestor(hasTestTag("hourly-review-details"))).assertIsDisplayed()
        compose.onNode(hasText("1 条未统计时长", substring = true) and
            hasAnyAncestor(hasTestTag("hourly-review-details"))).assertExists()
    }

    @Test fun overviewSupportsDarkThemeAndLargeTextWithEvidenceStillReachable() {
        val start = todayStart()
        val now = start + 12 * 3_600_000
        val records = listOf(
            measured(61, start + 8 * 3_600_000, "当前就业形势很差，因为招聘岗位同比减少20%", 80_000),
            measured(62, start + 9 * 3_600_000, "当前就业形势很差，因为招聘岗位同比减少20%，其中技术岗位增加5%", 140_000),
            measured(63, start + 10 * 3_600_000, "机器人课程提供实验资料，建议比较教学方案与项目训练。", 200_000))
        val report = CognitiveAnalyzer().analyze(records, StatisticsPeriod.DAY, now)
        renderOverview(records, report, now, dark = false)
        compose.onNodeWithText("今日观察").assertIsDisplayed()
        saveScreenshot("emerge-ui-overview-light.png")
        compose.onNodeWithText("24 小时信息回顾").performScrollTo()
        saveScreenshot("emerge-ui-hourly-light.png")

        renderOverview(records, report, now, dark = true, fontScale = 1.3f)
        compose.onNodeWithText("今日观察").assertIsDisplayed()
        saveScreenshot("emerge-ui-overview-dark-large.png")
        compose.onNodeWithText("24 小时信息回顾").performScrollTo().assertIsDisplayed()
        // Bring the horizontal list into the vertical viewport before scrolling to its hour.
        compose.onNodeWithTag("hourly-hour-options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("8时，1条记录，预计停留1 分 20 秒，点击查看详情")
            .performScrollTo().assertIsDisplayed().performClick()
        saveScreenshot("emerge-ui-hourly-heading-dark-large.png")
        compose.onNode(hasText("08:00—09:00") and hasAnyAncestor(hasTestTag("hourly-review-details"))).assertIsDisplayed()
        compose.onNodeWithTag("hourly-review-details")
            .performScrollToNode(hasText("本条采集原文：", substring = true))
        // The evidence item is taller than the sheet at large font sizes. Position the exact
        // original-text heading after composing the item, rather than stopping at its top edge.
        compose.onNode(hasText("本条采集原文：") and
            hasAnyAncestor(hasTestTag("hourly-review-details"))).performScrollTo().assertIsDisplayed()
        saveScreenshot("emerge-ui-evidence-dark-large.png")
    }

    private fun renderOverview(records: List<BrowseEvent>, report: CognitiveReport, now: Long,
        dark: Boolean, fontScale: Float = 1f) = render {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            EmergeTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    OverviewScreen(calculateStatistics(records, StatisticsPeriod.DAY, now),
                        StatisticsPeriod.DAY, {}, active = false, statusLabel = "已暂停",
                        appNames = mapOf("com.example.reader" to "资料阅读器"), onRecords = {}, onSettings = {},
                        analysis = AnalysisState(report = report, active = true, purpose = "比较资料中的观点与依据"),
                        rawEvents = records, selectedAppCount = 1, now = now)
                }
            }
        }
    }

    private fun measured(id: Long, start: Long, text: String, duration: Long) =
        BrowseEvent(id, "com.example.reader", start, 32, text,
            endTimestamp = start + duration, durationMillis = duration, sampleCount = 4)

    private fun todayStart() = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
        compose.waitForIdle()
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Allow the emulator compositor to present the settled dialog frame.
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
