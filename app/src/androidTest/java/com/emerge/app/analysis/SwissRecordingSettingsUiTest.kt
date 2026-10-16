package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwissRecordingSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun scopeOnlyEnablesAvailableAppsAndCaptureUsesActualSelection() {
        val douyin = "com.ss.android.ugc.aweme"
        var selected by mutableStateOf(emptySet<String>())
        var captureChanges = 0
        render {
            SwissRecordingScreen(false, false, CaptureStatus(), listOf(CaptureApp(douyin, "抖音")),
                selected, emptyList(), null, AudioUiState(), emptyList(),
                onCapture = { captureChanges++ }, onOpenSettings = {}, onPackagesChange = { selected = it },
                onChooseApps = {}, onImport = {}, onAudioCapture = {}, onDownloadModel = {},
                onCancelTask = {}, onOpenAudioRecords = {})
        }
        compose.onNodeWithTag("capture-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("recording-app-com.xingin.xhs").assertIsNotEnabled()
        compose.onNodeWithTag("recording-app-$douyin").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(setOf(douyin), selected) }
        compose.onNodeWithTag("capture-toggle").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, captureChanges) }
        compose.onNodeWithTag("audio-record-toggle").assertIsNotEnabled()
    }

    @Test fun unsupportedAttestationRemainsUnavailableAndCannotClaimDatabaseEncryption() {
        render { Column { ConfidentialComputingCard(); LocalPrivacyStatusCard() } }
        compose.onNodeWithText("未配置").assertExists()
        compose.onNodeWithTag("tee-reverify").assertIsNotEnabled()
        compose.onNodeWithText("不可读取").assertDoesNotExist()
        compose.onNodeWithText("本地数据库加密").assertExists()
        compose.onNodeWithText("未启用").assertExists()
    }

    @Test fun changingGatewayClearsCredentialAndHttpNeverReachesSave() {
        var saves = 0
        render {
            CloudConnectionSettingsCard(TextServiceConfig(true, "https://example.org", "credential")) { _, _, _ -> saves++ }
        }
        compose.onNodeWithTag("service-endpoint").performTextReplacement("http://127.0.0.1:8000")
        compose.onNodeWithTag("service-token").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("service-save").assertIsNotEnabled()
        compose.onNodeWithTag("service-connection-test").performClick()
        compose.onNodeWithTag("service-connection-result").assertTextContains("仅支持 HTTPS")
        compose.runOnIdle { assertEquals(0, saves) }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme { content() } } }
        compose.waitForIdle()
    }
}
