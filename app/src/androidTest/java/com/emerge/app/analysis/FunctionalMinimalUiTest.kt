package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.capture.CaptureStatus
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
class FunctionalMinimalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainActivityConnectsFiveTabsFactChecksAndRecordingSettings() {
        tab("概览").assertIsSelected()
        compose.onNodeWithTag("overview-list").assertIsDisplayed()
        listOf("概览", "观点", "记录", "历史", "设置").forEach { tab(it).assertExists() }
        tab("发现").assertDoesNotExist()
        compose.onNodeWithTag("audio-entry").assertDoesNotExist()
        saveScreenshot("pojian-monitor-overview.png")
        tab("观点").performClick().assertIsSelected()
        compose.onNodeWithTag("swiss-viewpoints-screen").assertIsDisplayed()
        compose.onNodeWithText("事实核查").performClick()
        compose.onNodeWithTag("monitor-discovery-list").assertIsDisplayed()
        compose.onNodeWithText("重复观点").assertIsDisplayed()
        compose.onAllNodesWithText("事实核查").onLast().assertIsDisplayed()
        compose.onNodeWithTag("monitor-discovery-list").performScrollToNode(hasText("全部记录"))
        compose.onNodeWithText("全部记录").performClick()
        tab("历史").assertIsSelected()
        compose.onNodeWithTag("swiss-history-screen").assertIsDisplayed()
        compose.onNodeWithTag("monitor-discovery-list").assertDoesNotExist()
        tab("设置").performClick().assertIsSelected()
        compose.onNodeWithTag("swiss-settings-screen").assertIsDisplayed()
        compose.onNodeWithTag("capture-toggle").assertDoesNotExist()
        compose.onNodeWithTag("swiss-settings-screen").performScrollToNode(hasText("提醒频率与隐私安全"))
        saveScreenshot("pojian-monitor-settings-analysis.png")
        compose.onNodeWithText("专题研究模式").performScrollTo()
        saveScreenshot("pojian-monitor-settings-alerts.png")
        compose.onNodeWithText("实时记录").performScrollTo().performClick()
        tab("记录").assertIsSelected()
        compose.onNodeWithTag("swiss-recording-screen").assertIsDisplayed()
        compose.onNodeWithTag("capture-toggle").assertExists()
        compose.onNodeWithTag("audio-record-toggle").assertExists()
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
        compose.onNodeWithTag("overview-list").performScrollToNode(hasText("应用时长分布"))
        compose.onNodeWithTag("overview-list").performScrollToNode(hasText("1 分 0 秒"))
        compose.onNodeWithText("资料阅读器").assertIsDisplayed()
        compose.onNodeWithText("1 分 0 秒").assertIsDisplayed()
        compose.onNodeWithText("2 分 0 秒").assertDoesNotExist()
        // The retained timeline still covers window-clipped source retrieval independently of overview layout.
        render { TimelineHarness(records, StatisticsPeriod.DAY, now, mapOf("com.example.reader" to "资料阅读器")) }
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-bar-0"))
        compose.onNodeWithTag("viewpoint-bar-0").performClick()
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-details"))
        compose.onNodeWithTag("viewpoint-details").assertIsDisplayed()
        compose.onAllNodesWithText(original).assertCountEquals(0)
        compose.onNode(hasText("1 分 0 秒") and hasAnyAncestor(hasTestTag("viewpoint-details"))).assertExists()
        compose.onNode(hasText("2 分 0 秒") and hasAnyAncestor(hasTestTag("viewpoint-details"))).assertDoesNotExist()
        saveScreenshot("emerge-viewpoint-details.png")
        compose.onNodeWithTag("work-card-${BrowseWorkKey.of(records.first())}").performClick()
        compose.onNodeWithTag("work-detail-content").performScrollToNode(hasText(original))
        compose.onAllNodesWithText(original).onFirst().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, analysisCalls) }
    }

    @Test fun activeRecordingPausesResumesAndStopsThroughItsCallbacks() {
        val paused = mutableStateOf(false)
        var pauseCalls = 0; var stopCalls = 0; var startCalls = 0; var downloadCalls = 0
        render { RecordingHarness(AudioUiState(modelState = AudioModelUiState(AudioModelStatus.READY),
            task = AudioTaskUiState(AudioTaskPhase.CAPTURING, partialText = "实时识别到的原文", elapsedMillis = 24_000L)),
            onImport = { startCalls++ }, onCapture = { startCalls++ }, onDownloadModel = { downloadCalls++ },
            onCancelTask = { stopCalls++ }, onOpenRecords = {},
            onPauseResume = { pauseCalls++; paused.value = !paused.value }, isPaused = paused.value) }
        compose.onNodeWithTag("swiss-recording-screen").performScrollToNode(hasTestTag("audio-pause-resume"))
        compose.onAllNodesWithText("00:24").onFirst().assertIsDisplayed()
        saveScreenshot("pojian-minimal-active-recording.png")
        compose.onNodeWithTag("audio-pause-resume").assertIsEnabled().performClick()
        compose.onNodeWithTag("audio-pause-resume").assertTextContains("继续录音")
        compose.runOnIdle { assertEquals(1, pauseCalls); assertEquals(true, paused.value); assertEquals(0, startCalls); assertEquals(0, downloadCalls) }
        compose.onNodeWithTag("audio-pause-resume").performClick().assertTextContains("暂停录音")
        compose.onNodeWithTag("audio-stop-task").performClick()
        compose.runOnIdle { assertEquals(2, pauseCalls); assertEquals(false, paused.value); assertEquals(1, stopCalls) }
        compose.onNodeWithTag("audio-import").assertIsNotEnabled()
        compose.onNodeWithTag("swiss-recording-screen").performScrollToNode(hasTestTag("audio-record-toggle"))
        compose.onNodeWithTag("audio-record-toggle").assertIsOn()
    }

    @Test fun weeklyViewpointBarsOpenSpecificViewpointQuotes() {
        val midnight = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = midnight + 12 * 3600000L
        val records = (1L..4L).map { id -> BrowseEvent(id, "reader", midnight + 3600000L, 32,
            when (id) { 3L -> "女性应该经济独立并自主生活"; 4L -> "菜单"; else -> "找工作很难，很多人因此感到焦虑" }, endTimestamp = midnight + 3660000L,
            durationMillis = 60000, sampleCount = 2, workKey = "work-$id") }
        render { TimelineHarness(records, StatisticsPeriod.WEEK, now, mapOf("reader" to "测试阅读器")) }
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-bar-6"))
        saveScreenshot("emerge-viewpoint-chart.png")
        compose.onNodeWithTag("viewpoint-bar-6").performClick()
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-details"))
        compose.onNodeWithTag("viewpoint-details").assertIsDisplayed()
        compose.onAllNodesWithText("就业焦虑").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("daily-viewpoint-pie").assertDoesNotExist()
        compose.onNodeWithText("全部观点").performClick()
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("daily-viewpoint-pie"))
        compose.onNodeWithTag("daily-viewpoint-pie").assertContentDescriptionEquals("就业焦虑，2个作品，50.0%；女性独立，1个作品，25.0%；未识别，1个作品，25.0%")
        saveScreenshot("emerge-daily-viewpoint-pie.png")
        saveScreenshot("pojian-distribution-card.png", "daily-viewpoint-card")
        val distribution = hasAnyAncestor(hasTestTag("daily-viewpoint-pie"))
        compose.onNode(hasText("女性独立") and distribution).performScrollTo().performClick()
        compose.onNode(hasText("查看原文 ›") and distribution).performScrollTo().performClick()
        compose.onNode(hasText("收起原文") and distribution).assertIsDisplayed()
        compose.onAllNodes(hasText(records[2].visibleText) and distribution).assertCountEquals(2)
        compose.onNode(hasText("女性独立") and distribution).performScrollTo().performClick()
        compose.onNode(hasText("收起原文") and distribution).assertDoesNotExist()
    }

    @Test fun recordingStartsRemainGatedUntilTheUserDownloadsAModel() {
        var downloadCalls = 0; var startCalls = 0; var pauseCalls = 0
        render { RecordingHarness(AudioUiState(), onImport = { startCalls++ }, onCapture = { startCalls++ },
            onDownloadModel = { downloadCalls++ }, onCancelTask = {}, onOpenRecords = {}, onPauseResume = { pauseCalls++ }) }
        compose.onNodeWithTag("audio-record-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("swiss-recording-screen").performScrollToNode(hasTestTag("audio-import"))
        compose.onNodeWithTag("audio-import").assertIsNotEnabled()
        compose.onNodeWithTag("audio-pause-resume").assertDoesNotExist()
        compose.onNodeWithTag("swiss-recording-screen").performScrollToNode(hasTestTag("audio-download-model"))
        compose.onNodeWithTag("audio-download-model").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, downloadCalls); assertEquals(0, startCalls); assertEquals(0, pauseCalls) }
    }

    @Test fun identicalTextWithoutIdentityEvidenceKeepsFourWorkCards() {
        val midnight = LocalDate.of(2026, 10, 12).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = midnight + 12 * 3600000L
        val rows = (1L..4).map { id -> BrowseEvent(id, "com.sina.weibo", midnight+3600000L,32,
            "楼下大爷说的话，我是不会轻易相信的。",workKey="visit:$id",identityMethod="session_estimate") }
        render { BrowseWorkCards(ViewpointTimeline.build(rows,StatisticsPeriod.DAY,now,ZoneId.systemDefault()).flatMap { it.items },
            midnight,now,mapOf("com.sina.weibo" to "微博")) }
        compose.onAllNodes(hasTestTag("work-card-${BrowseWorkKey.of(rows.first())}")).assertCountEquals(1)
        for(row in rows) {
            compose.onNodeWithTag("work-card-list").performScrollToNode(hasTestTag("work-card-${BrowseWorkKey.of(row)}"))
            compose.onNodeWithTag("work-card-${BrowseWorkKey.of(row)}").assertExists()
        }
    }

    @Test fun oneWorkWithTwoModelViewpointsUsesIndependentSharesAndPreservesQuotes() {
        val midnight = LocalDate.of(2026, 10, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = midnight + 12 * 3600000L
        val row = BrowseEvent(901, "reader", midnight + 3600000L, 32,
            "今年招聘岗位减少\n未来岗位需求可能回升", sampleCount = 1, workKey = "modeled")
        val unknown = row.copy(id = 902, visibleText = "菜单", workKey = "unknown")
        val records = listOf(row, unknown)
        val semantics = listOf(EventSemantics(row.id, row.visibleText, now, listOf(
            ArgumentUnit("今年招聘岗位减少", "就业", "招聘岗位", "数量", "减少", conditions = "今年", uncertain = false),
            ArgumentUnit("未来岗位需求可能回升", "就业", "招聘岗位", "需求", "回升", conditions = "未来", uncertain = false))))
        render { TimelineHarness(records, StatisticsPeriod.WEEK, now, mapOf("reader" to "测试阅读器"), semantics) }
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("daily-viewpoint-pie"))
        compose.onNodeWithTag("daily-viewpoint-pie").assert(SemanticsMatcher("multi-viewpoint work denominator") { node ->
            val description = node.config[SemanticsProperties.ContentDescription].joinToString()
            description.contains("今年 · 减少，1个作品，50.0%") &&
                description.contains("未来 · 回升，1个作品，50.0%") && description.contains("未识别，1个作品，50.0%")
        })
        saveScreenshot("pojian-model-viewpoint-shares.png")
        saveScreenshot("pojian-overlapping-distribution-card.png", "daily-viewpoint-card")
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-bar-6"))
        compose.onNodeWithTag("viewpoint-bar-6").performClick()
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-details"))
        compose.onNodeWithTag("work-card-list").performScrollToNode(hasTestTag("work-card-${BrowseWorkKey.of(row)}"))
        compose.onNode(hasText("今年招聘岗位减少") and hasAnyAncestor(hasTestTag("viewpoint-details"))).assertExists()
        compose.onAllNodes(hasTestTag("work-card-${BrowseWorkKey.of(row)}")).assertCountEquals(1)
    }

    @Test fun retainedTimelineStaysPinnedAndFiveTabNavigationRemainsVisibleWhileWorkListScrolls() {
        val midnight = LocalDate.of(2026, 10, 13).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = midnight + 12 * 3600000L
        val records = (1L..40).map { id -> BrowseEvent(id, "reader", midnight + 3600000L, 32,
            "职业能力需要持续提升，作品 $id 的项目经历", workKey = "scroll-work-$id", sampleCount = 1) }
        val scrolling = mutableStateOf(false)
        val scrollingChanges = mutableListOf<Boolean>()
        render {
            Scaffold(bottomBar = {
                PojianNavigationBar(listOf("概览", "观点", "记录", "历史", "设置"), 1, {}) { Text("•") }
            }) { padding ->
                TimelineHarness(records, StatisticsPeriod.WEEK, now, mapOf("reader" to "测试阅读器"),
                    modifier = Modifier.padding(padding), onWorkScrolling = { scrolling.value = it; scrollingChanges += it })
            }
        }
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-bar-6"))
        compose.onNodeWithTag("viewpoint-bar-6").performClick()
        compose.onNodeWithTag("timeline-test-list").performScrollToNode(hasTestTag("viewpoint-details"))
        val chartTop = compose.onNodeWithTag("viewpoint-chart-pinned").fetchSemanticsNode().boundsInRoot.top
        for (direction in listOf(-1f, 1f)) {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("work-card-list").performTouchInput {
                down(center)
                moveBy(Offset(0f, direction * 90f), delayMillis = 200)
            }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("main-navigation").assertIsDisplayed()
            assertTrue(scrolling.value)
            val movedTop = compose.onNodeWithTag("viewpoint-chart-pinned").fetchSemanticsNode().boundsInRoot.top
            assertTrue(kotlin.math.abs(chartTop - movedTop) < 8f)
            compose.onNodeWithTag("work-card-list").performTouchInput { advanceEventTime(400); up() }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { !scrolling.value }
            compose.onNodeWithTag("main-navigation").assertIsDisplayed()
        }
        compose.runOnIdle { assertTrue(scrollingChanges.contains(true)); assertEquals(false, scrollingChanges.last()) }
    }

    @Test fun cloudMasterSwitchHasAnIndependentCallback() {
        val cloud = mutableStateOf(true)
        var cloudCalls = 0
        var monitorCalls = 0
        render { com.emerge.app.monitoring.MonitorSettingsSection(
            com.emerge.app.monitoring.MonitorConfig(), { monitorCalls++ }, 0, 0, {}, {},
            cloudEnabled = cloud.value, onCloudEnabledChange = { cloud.value = it; cloudCalls++ }) }
        compose.onNodeWithTag("monitor-switch-云端分析").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(1, cloudCalls); assertEquals(0, monitorCalls) }
    }

    @Composable
    private fun TimelineHarness(records: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
        names: Map<String, String>, semantics: List<EventSemantics> = emptyList(),
        modifier: Modifier = Modifier, onWorkScrolling: (Boolean) -> Unit = {}) {
        val state = rememberViewpointTimelineState(records, period, now, report = null, semantics = semantics)
        LazyColumn(modifier.fillMaxSize().testTag("timeline-test-list"), contentPadding = PaddingValues(16.dp)) {
            viewpointTimeline(state, names, plotHeight = 120.dp, onWorkScrolling = onWorkScrolling)
        }
    }

    @Composable
    private fun RecordingHarness(state: AudioUiState, onImport: () -> Unit, onCapture: () -> Unit,
        onDownloadModel: () -> Unit, onCancelTask: () -> Unit, onOpenRecords: () -> Unit,
        onPauseResume: (() -> Unit)? = null, isPaused: Boolean = false) {
        SwissRecordingScreen(permission = true, enabled = true, diagnostics = CaptureStatus(connected = true),
            apps = listOf(CaptureApp("com.ss.android.ugc.aweme", "抖音")),
            selectedPackages = setOf("com.ss.android.ugc.aweme"), events = emptyList(), report = null,
            audioState = state, audioSessions = emptyList(), onCapture = {}, onOpenSettings = {},
            onPackagesChange = {}, onChooseApps = {}, onImport = onImport, onAudioCapture = onCapture,
            onDownloadModel = onDownloadModel, onCancelTask = onCancelTask, onOpenAudioRecords = onOpenRecords,
            onPauseResume = onPauseResume, isAudioPaused = isPaused)
    }

    private fun saveScreenshot(name: String, tag: String? = null) {
        tag?.let { compose.onNodeWithTag(it).performScrollTo() }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(350)
        val bitmap = tag?.let { compose.onNodeWithTag(it).captureToImage().asAndroidBitmap() }
            ?: instrumentation.uiAutomation.takeScreenshot()
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
