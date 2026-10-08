package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FunctionalMinimalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainActivityPrioritizesOverviewDiscoverAndSettings() {
        tab("概览").assertIsSelected()
        compose.onNodeWithTag("overview-list").assertIsDisplayed()
        tab("录制").assertDoesNotExist()
        compose.onNodeWithTag("audio-entry").assertDoesNotExist()
        saveScreenshot("emerge-monitor-overview.png")
        tab("发现").performClick().assertIsSelected()
        compose.onNodeWithTag("monitor-discovery-list").assertIsDisplayed()
        compose.onNodeWithText("观点观察").assertIsDisplayed()
        compose.onNodeWithText("事实核查").assertIsDisplayed()
        compose.onNodeWithText("查看原文材料").performScrollTo().performClick()
        compose.onNodeWithText("信息足迹").assertIsDisplayed()
        tab("设置").performClick().assertIsSelected()
        compose.onNodeWithTag("capture-toggle").assertExists()
        compose.onNodeWithText("采集工具：播放音频、模型与材料导入").performScrollTo().performClick()
        compose.onNodeWithTag("recording-screen").assertIsDisplayed()
        compose.onNodeWithText("返回设置").performClick()
        tab("概览").performClick().assertIsSelected()
        compose.onNodeWithTag("overview-list").assertIsDisplayed()
    }

    @Test fun overviewWithoutGeneratedReportCanReviewMidnightSessionWithOnlyItsCoveredDuration() {
        val midnight = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val start = midnight - 60_000L
        val original = "跨午夜保存的原文，必须在零点的时段可以回查。"
        val records = listOf(BrowseEvent(901L, "com.example.reader", start, 32, original,
            endTimestamp = start + 120_000L, durationMillis = 120_000L, sampleCount = 61))
        val now = midnight + 180_000L
        var analysisCalls = 0
        render { OverviewScreen(calculateStatistics(records, StatisticsPeriod.DAY, now), StatisticsPeriod.DAY, {},
            active = false, statusLabel = "已暂停", appNames = mapOf("com.example.reader" to "资料阅读器"),
            onRecords = {}, onSettings = {}, analysis = AnalysisState(), rawEvents = records,
            onAnalyze = { analysisCalls++ }, now = now) }
        compose.onNodeWithTag("hour-0").performScrollTo().assertIsDisplayed()
            .assertContentDescriptionEquals("0时，1条保存记录，点击选择时段").performClick()
        compose.onNodeWithTag("overview-open-hour").performScrollTo().performClick()
        compose.onNodeWithTag("overview-hour-records").assertIsDisplayed()
        compose.onNode(hasText("00:00—01:00") and hasAnyAncestor(hasTestTag("overview-hour-records"))).assertIsDisplayed()
        compose.onNodeWithTag("overview-hour-records").performScrollToNode(hasText(original))
        compose.onNode(hasText(original) and hasAnyAncestor(hasTestTag("overview-hour-records"))).assertIsDisplayed()
        compose.onNode(hasText("本时段预计停留：1 分 0 秒") and hasAnyAncestor(hasTestTag("overview-hour-records"))).assertExists()
        compose.onNode(hasText("本时段预计停留：2 分 0 秒") and hasAnyAncestor(hasTestTag("overview-hour-records"))).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, analysisCalls) }
    }

    @Test fun activeRecordingPausesResumesAndStopsThroughItsCallbacks() {
        val paused = mutableStateOf(false)
        var pauseCalls = 0; var stopCalls = 0; var startCalls = 0; var downloadCalls = 0
        render { RecordingScreen(AudioUiState(modelState = AudioModelUiState(AudioModelStatus.READY),
            task = AudioTaskUiState(AudioTaskPhase.CAPTURING, partialText = "实时识别到的原文", elapsedMillis = 24_000L)),
            onImport = { startCalls++ }, onCapture = { startCalls++ }, onDownloadModel = { downloadCalls++ },
            onCancelTask = { stopCalls++ }, onOpenRecords = {},
            onPauseResume = { pauseCalls++; paused.value = !paused.value }, isPaused = paused.value) }
        compose.onNodeWithText("00:24").assertIsDisplayed()
        saveScreenshot("emerge-minimal-active-recording.png")
        compose.onNodeWithTag("audio-pause-resume").assertIsEnabled().performClick()
        compose.onNodeWithTag("audio-pause-resume").assertTextContains("继续记录")
        compose.runOnIdle { assertEquals(1, pauseCalls); assertEquals(true, paused.value); assertEquals(0, startCalls); assertEquals(0, downloadCalls) }
        compose.onNodeWithTag("audio-pause-resume").performClick().assertTextContains("暂停记录")
        compose.onNodeWithTag("audio-stop-task").performClick()
        compose.runOnIdle { assertEquals(2, pauseCalls); assertEquals(false, paused.value); assertEquals(1, stopCalls) }
        compose.onNodeWithTag("audio-import").assertDoesNotExist()
        compose.onNodeWithTag("audio-capture").assertDoesNotExist()
    }

    @Test fun recordingStartsRemainGatedUntilTheUserDownloadsAModel() {
        var downloadCalls = 0; var startCalls = 0; var pauseCalls = 0
        render { RecordingScreen(AudioUiState(), onImport = { startCalls++ }, onCapture = { startCalls++ },
            onDownloadModel = { downloadCalls++ }, onCancelTask = {}, onOpenRecords = {}, onPauseResume = { pauseCalls++ }) }
        compose.onNodeWithTag("audio-import").assertIsNotEnabled()
        compose.onNodeWithTag("audio-capture").assertIsNotEnabled()
        compose.onNodeWithTag("audio-pause-resume").assertDoesNotExist()
        compose.onNodeWithTag("audio-download-model").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, downloadCalls); assertEquals(0, startCalls); assertEquals(0, pauseCalls) }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        } } }
        compose.waitForIdle()
    }
}
