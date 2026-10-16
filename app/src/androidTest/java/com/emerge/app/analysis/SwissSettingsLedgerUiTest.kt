package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.emerge.app.MainActivity
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SwissSettingsLedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun collapsedEndpointRetainsDraftAndSaveFailureAfterReopening() {
        var saves = 0
        render {
            CloudConnectionSettingsCard(TextServiceConfig(true, "https://example.org", "credential"), embedded = true) { _, _, fail ->
                saves++; fail("保存失败")
            }
        }
        compose.onNodeWithTag("service-endpoint").assertDoesNotExist()
        compose.onNodeWithTag("settings-endpoint-options").performClick()
        compose.onNodeWithTag("service-endpoint").performTextReplacement("https://changed.example.org")
        compose.onNodeWithTag("service-token").assertTextEquals("访问令牌", "")
        compose.onNodeWithTag("settings-endpoint-options").performClick().performClick()
        compose.onNodeWithTag("service-endpoint").assertTextContains("https://changed.example.org")
        compose.onNodeWithTag("service-save").performClick()
        compose.onNodeWithText("保存失败").assertExists()
        compose.onNodeWithTag("service-endpoint").assertTextContains("https://changed.example.org")
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun modelDownloadAndCancelRemainAvailableInsideSettings() {
        var model by mutableStateOf(AudioModelUiState())
        var downloads = 0
        var cancellations = 0
        render {
            SwissSpeechModelSettings(AudioUiState(modelState = model),
                { downloads++; model = AudioModelUiState(AudioModelStatus.DOWNLOADING, .25f) }, { cancellations++ })
        }
        compose.onNodeWithTag("audio-download-model").assertDoesNotExist()
        compose.onNodeWithTag("settings-speech-model").performClick()
        compose.onNodeWithTag("audio-download-model").performClick()
        compose.onNodeWithTag("audio-cancel-download").performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, cancellations) }
    }

    @Test fun captureRowsUseExistingActionsOnceAndGateMissingScopeAtLargeFont() {
        var selectedCount by mutableIntStateOf(0)
        var changes = 0
        var opens = 0
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    SwissCaptureSettings(false, false, CaptureStatus(), selectedCount, AudioUiState(), false, false,
                        { opens++ }, { changes++ }, {}, {}, {}, {}, null)
                }
            }
        }
        compose.onNodeWithTag("settings-capture-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("setting_row_accessibility").performClick()
        compose.runOnIdle { selectedCount = 1 }
        compose.onNodeWithTag("settings-capture-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("setting_row_audio_toggle").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, changes) }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme { content() } } }
    }
}
