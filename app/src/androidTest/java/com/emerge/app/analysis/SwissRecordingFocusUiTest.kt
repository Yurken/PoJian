package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwissRecordingFocusUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun missingModelRoutesToSettingsWithoutBlockingScreenCapture() {
        var settingsOpens = 0
        var screenEnabled = false
        var audioStarts = 0
        render {
            RecordingScreen(
                onCapture = { screenEnabled = it },
                onSettings = { settingsOpens++ },
                onAudioCapture = { audioStarts++ }
            )
        }

        compose.onNodeWithTag("capture-toggle").assertIsEnabled().performClick()
        compose.onNodeWithTag("audio-record-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("recording-model-settings").assertIsEnabled().performClick()
        compose.onNodeWithText("模型未就绪").assertExists()
        compose.onNodeWithTag("audio-download-model").assertDoesNotExist()
        compose.onNodeWithTag("audio-cancel-download").assertDoesNotExist()
        compose.onNodeWithText("— ms/帧").assertDoesNotExist()
        compose.onNodeWithText("RTF —").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(true, screenEnabled)
            assertEquals(1, settingsOpens)
            assertEquals(0, audioStarts)
        }
    }

    @Test fun failedModelKeepsFailureVisibleAndRecordsReachable() {
        var recordsOpens = 0
        render {
            RecordingScreen(
                state = AudioUiState(modelState = AudioModelUiState(
                    AudioModelStatus.ERROR, detail = "模型校验失败"
                )),
                onOpenAudioRecords = { recordsOpens++ }
            )
        }

        compose.onNodeWithText("模型下载失败").assertExists()
        compose.onNodeWithText("模型校验失败").assertExists()
        compose.onNodeWithTag("audio-import").assertIsNotEnabled()
        compose.onNodeWithTag("recording-model-settings").assertIsEnabled()
        compose.onNodeWithText("转写记录").performClick()
        compose.runOnIdle { assertEquals(1, recordsOpens) }
    }

    @Test fun screenToggleDelegatesToItsCallbackAndActiveAudioKeepsTaskControls() {
        var paused by mutableStateOf(false)
        var screenEnabled = true
        var audioStops = 0
        render {
            RecordingScreen(
                state = AudioUiState(
                    modelState = AudioModelUiState(AudioModelStatus.READY),
                    task = AudioTaskUiState(
                        AudioTaskPhase.CAPTURING,
                        partialText = "正在识别的语音材料",
                        elapsedMillis = 24_000L
                    )
                ),
                enabled = true,
                selectedPackages = emptySet(),
                paused = paused,
                onCapture = { screenEnabled = it },
                onPauseResume = { paused = !paused },
                onCancelTask = { audioStops++ }
            )
        }

        compose.onNodeWithTag("capture-toggle").performClick()
        compose.runOnIdle {
            assertEquals(false, screenEnabled)
            assertEquals(0, audioStops)
        }
        compose.onNodeWithTag("swiss-recording-screen")
            .performScrollToNode(hasTestTag("audio-pause-resume"))
        compose.onNodeWithTag("audio-pause-resume").performClick().assertTextContains("继续录音")
        compose.onNodeWithTag("audio-stop-task").performClick()
        compose.runOnIdle { assertEquals(1, audioStops) }
        compose.onNodeWithTag("swiss-recording-screen")
            .performScrollToNode(hasTestTag("recording-transcript-stream"))
        compose.onNodeWithTag("audio-live-transcript").assertTextEquals("正在识别的语音材料")
    }

    @Composable
    private fun RecordingScreen(
        state: AudioUiState = AudioUiState(),
        enabled: Boolean = false,
        selectedPackages: Set<String> = setOf("com.ss.android.ugc.aweme"),
        paused: Boolean = false,
        onCapture: (Boolean) -> Unit = {},
        onSettings: () -> Unit = {},
        onAudioCapture: () -> Unit = {},
        onOpenAudioRecords: () -> Unit = {},
        onPauseResume: (() -> Unit)? = null,
        onCancelTask: () -> Unit = {}
    ) {
        val packageName = "com.ss.android.ugc.aweme"
        SwissRecordingScreen(
            permission = true,
            enabled = enabled,
            diagnostics = CaptureStatus(connected = true),
            apps = listOf(CaptureApp(packageName, "抖音")),
            selectedPackages = selectedPackages,
            events = emptyList(),
            report = null,
            audioState = state,
            audioSessions = emptyList(),
            onCapture = onCapture,
            onOpenSettings = {},
            onPackagesChange = {},
            onChooseApps = {},
            onImport = {},
            onAudioCapture = onAudioCapture,
            onDownloadModel = {},
            onCancelTask = onCancelTask,
            onOpenAudioRecords = onOpenAudioRecords,
            onPauseResume = onPauseResume,
            isAudioPaused = paused,
            onSettings = onSettings
        )
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme { content() } } }
        compose.waitForIdle()
    }
}
