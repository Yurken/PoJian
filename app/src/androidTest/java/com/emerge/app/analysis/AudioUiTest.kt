package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.AudioEntryCard
import com.emerge.app.ui.AudioModelStatus
import com.emerge.app.ui.AudioModelUiState
import com.emerge.app.ui.AudioSegmentUi
import com.emerge.app.ui.AudioSessionDetailSheet
import com.emerge.app.ui.AudioSessionUi
import com.emerge.app.ui.AudioTaskPhase
import com.emerge.app.ui.AudioTaskUiState
import com.emerge.app.ui.AudioUiState
import com.emerge.app.ui.RecordsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun modelPreparationGatesAudioActionsAndStartsOnlyRequestedDownload() {
        var downloadCalls = 0
        var captureCalls = 0
        render {
            AudioEntryCard(AudioUiState(), onImport = {}, onCapture = { captureCalls++ },
                onDownloadModel = { downloadCalls++ }, onCancelTask = {}, onOpenRecords = {})
        }
        compose.onNodeWithTag("audio-import").assertIsNotEnabled()
        compose.onNodeWithTag("audio-capture").assertIsNotEnabled()
        compose.onNodeWithTag("audio-download-model").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, downloadCalls)
            assertEquals(0, captureCalls)
        }
    }

    @Test fun activeCaptureShowsRealPartialTextAndExposesStopWithoutAnotherStart() {
        var stopCalls = 0
        render {
            AudioEntryCard(AudioUiState(modelState = AudioModelUiState(AudioModelStatus.READY),
                task = AudioTaskUiState(AudioTaskPhase.CAPTURING, partialText = "这段声音里提到了学习方法", elapsedMillis = 24_000)),
                onImport = {}, onCapture = {}, onDownloadModel = {}, onCancelTask = { stopCalls++ }, onOpenRecords = {})
        }
        compose.onNodeWithText("这段声音里提到了学习方法").assertIsDisplayed()
        compose.onNodeWithText("00:24").assertIsDisplayed()
        compose.onNodeWithTag("audio-import").assertDoesNotExist()
        compose.onNodeWithTag("audio-capture").assertDoesNotExist()
        compose.onNodeWithTag("audio-stop-task").performClick()
        compose.runOnIdle { assertEquals(1, stopCalls) }
    }

    @Test fun transcriptRevisionCancelDiscardsAndSaveTargetsExactSegment() {
        var savedId: Long? = null
        var savedText: String? = null
        render {
            AudioSessionDetailSheet(fixtureSession(), onDismiss = {}, onSaveSegment = { id, text ->
                savedId = id
                savedText = text
            }, onDelete = {})
        }
        compose.onNodeWithTag("audio-detail-segments").performScrollToNode(hasTestTag("audio-edit-action-41"))
        compose.onNodeWithTag("audio-edit-action-41").performScrollTo().performClick()
        compose.onNodeWithTag("audio-edit-41").performScrollTo().performTextReplacement("不应保存的修订")
        compose.onNodeWithText("取消修订").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, savedId) }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("audio-edit-action-41").performScrollTo().performClick()
        compose.onNodeWithTag("audio-edit-41").performScrollTo().performTextReplacement("  改为准确的原始观点  ")
        compose.onNodeWithTag("audio-save-41").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(41L, savedId)
            assertEquals("改为准确的原始观点", savedText)
        }
        compose.onNodeWithText("原始观点可能识别有误").assertExists()
    }

    @Test fun audioReviewRunsOnlyOnExplicitActionAndDeletionRequiresExactConfirmation() {
        var analysisCalls = 0
        var deleteCalls = 0
        render {
            AudioSessionDetailSheet(fixtureSession(), onDismiss = {}, onSaveSegment = { _, _ -> },
                onDelete = { deleteCalls++ }, onAnalyze = { analysisCalls++ })
        }
        compose.onNodeWithTag("audio-detail-segments").performScrollToNode(hasTestTag("audio-analyze-session"))
        compose.onNodeWithTag("audio-analyze-session").performClick()
        compose.runOnIdle { assertEquals(1, analysisCalls) }
        compose.onNodeWithTag("audio-detail-segments").performScrollToNode(hasTestTag("audio-delete-session"))
        compose.onNodeWithTag("audio-delete-session").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deleteCalls) }
        compose.onNodeWithTag("audio-delete-session").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(1, deleteCalls) }
    }

    @Test fun searchableAudioTranscriptShowsMediaOffsetsWithoutPretendingItIsLegacyData() {
        val event = BrowseEvent(41L, "imported-material", 1_790_000_000_000L, 0, "当前就业形势很差，因为招聘岗位同比减少20%",
            sourceType = "audio_import", audioSessionId = "session-7", mediaStartMillis = 3_000, mediaEndMillis = 11_000)
        render { RecordsScreen(listOf(event), 1, mapOf("imported-material" to "导入材料")) }

        compose.onNodeWithTag("record-group-imported-material").performClick()
        compose.onNodeWithText("音频转写").assertIsDisplayed()
        compose.onNodeWithText("媒体偏移 00:03 — 00:11").assertIsDisplayed()
        compose.onNodeWithText("旧版本").assertDoesNotExist()
        compose.onNodeWithText("查看原文 ›").performClick()
        compose.onNodeWithText("媒体偏移用于定位转写，不计入浏览停留时长。").performScrollTo().assertIsDisplayed()
    }

    @Test fun analysisFailureIsVisibleWithinTheDetailSheetAndAllowsRetry() {
        var retries = 0
        render {
            AudioSessionDetailSheet(fixtureSession(), onDismiss = {}, onSaveSegment = { _, _ -> }, onDelete = {},
                onAnalyze = { retries++ }, message = "今日文字服务调用已达上限，可在设置中关闭云端并使用本地回顾")
        }
        compose.onNodeWithTag("audio-detail-segments").performScrollToNode(hasTestTag("audio-detail-message"))
        compose.onNodeWithTag("audio-detail-message").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio-analyze-session").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun fixtureSession() = AudioSessionUi("session-7", "学习方法", "导入材料", 1_790_000_000_000L,
        durationMillis = 15_000L, segments = listOf(AudioSegmentUi(41L, 0L, 8_000L, "原始观点可能识别有误")))

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme(content = content) } }
        compose.waitForIdle()
    }
}
