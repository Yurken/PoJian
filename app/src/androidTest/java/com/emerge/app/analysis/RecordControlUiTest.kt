package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.AppPickerDialog
import com.emerge.app.ui.CaptureApp
import com.emerge.app.ui.RecordsScreen
import com.emerge.app.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordControlUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordFilterReachesLastApplicationCombinesSearchAndDeletesExactMatch() {
        val apps = fixtureApps()
        val last = apps.last()
        val records = apps.mapIndexed { index, app ->
            BrowseEvent(index + 1L, app.packageName, 1_000L + index, 32,
                if (app == last) "目标资料：嵌入式设计依据" else "其他应用的嵌入式内容 $index")
        } + BrowseEvent(90L, last.packageName, 2_000L, 32, "同一应用的摄影资料")
        var deleted: Long? = null
        render {
            MaterialTheme {
                RecordsScreen(records, records.size, apps.associate { it.packageName to it.label },
                    onDelete = { deleted = it.id })
            }
        }

        compose.onNodeWithTag("record-app-filter").performClick()
        compose.onNodeWithTag("record-app-options").performScrollToNode(hasText(last.label))
        compose.onNode(hasText(last.label) and hasAnyAncestor(hasTestTag("record-app-options")))
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("record-app-filter").assert(hasText(last.label))
        compose.onNodeWithText("搜索原文或应用").performTextInput("嵌入式")

        compose.onNodeWithText("目标资料：嵌入式设计依据").assertIsDisplayed()
        compose.onNodeWithText("同一应用的摄影资料").assertDoesNotExist()
        compose.onNodeWithText("其他应用的嵌入式内容 0").assertDoesNotExist()
        compose.onNodeWithText("1 条匹配").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(apps.size.toLong(), deleted) }
    }

    @Test fun captureSelectionCancelDiscardsDraftAndSaveKeepsOriginalPlusNewApplication() {
        val apps = fixtureApps()
        val first = apps.first()
        val last = apps.last()
        var visible by mutableStateOf(true)
        var saved by mutableStateOf(setOf(first.packageName))
        var applyCalls = 0
        render {
            MaterialTheme {
                if (visible) AppPickerDialog(apps, saved, loading = false, recordCounts = emptyMap(),
                    onDismiss = { visible = false },
                    onApply = { saved = it; applyCalls++; visible = false }, onDeletePackage = {})
                else Button(onClick = { visible = true }) { Text("重新选择") }
            }
        }

        compose.onNodeWithText(first.label).assertIsOn()
        compose.onNodeWithTag("capture-app-options").performScrollToNode(hasText(last.label))
        compose.onNodeWithText(last.label).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(0, applyCalls) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(setOf(first.packageName), saved)
            assertEquals(0, applyCalls)
        }

        compose.onNodeWithText("重新选择").performClick()
        compose.onNodeWithText(first.label).assertIsOn()
        compose.onNodeWithTag("capture-app-options").performScrollToNode(hasText(last.label))
        compose.onNodeWithText(last.label).assertIsOff().performClick()
        compose.onNodeWithText("保存范围").performClick()
        compose.runOnIdle {
            assertEquals(setOf(first.packageName, last.packageName), saved)
            assertEquals(1, applyCalls)
        }
    }

    @Test fun emptyScopeDisablesCaptureEvenWithSystemPermissionAndServiceConnection() {
        var captureChanges = 0
        render {
            MaterialTheme {
                SettingsScreen(permission = true, enabled = false, count = 0,
                    onCapture = { captureChanges++ }, onOpenSettings = {}, onClear = {}, message = null,
                    diagnostics = CaptureStatus(connected = true), selectedApps = emptyList())
            }
        }

        compose.onNodeWithText("尚未选择要记录的应用").assertIsDisplayed()
        compose.onNodeWithTag("capture-toggle").assertIsNotEnabled().assertIsOff()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, captureChanges) }
        compose.onNodeWithText("辅助功能授权").performScrollTo()
        compose.onNodeWithText("已启用").assertIsDisplayed()
        compose.onNodeWithText("辅助服务连接").performScrollTo()
        compose.onNodeWithText("已连接").assertIsDisplayed()
    }

    @Test fun captureStatusRequiresPermissionAndConnectionAndRespectsPause() {
        var permission by mutableStateOf(false)
        var connected by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        render {
            MaterialTheme {
                SettingsScreen(permission, enabled, count = 0, onCapture = { enabled = it },
                    onOpenSettings = {}, onClear = {}, message = null,
                    diagnostics = CaptureStatus(connected = connected), selectedApps = listOf(fixtureApps().first()))
            }
        }

        assertCaptureStatus("已开启 · 等待系统授权")
        compose.runOnIdle { permission = true }
        assertCaptureStatus("已开启 · 等待服务连接")
        compose.runOnIdle { connected = true }
        assertCaptureStatus("运行中 · 正在记录所选应用")
        compose.onNodeWithTag("capture-toggle").performClick()
        assertCaptureStatus("采集已暂停")
        compose.onNodeWithTag("capture-toggle").assertIsOff()
    }

    private fun assertCaptureStatus(label: String) {
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithTag("capture-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
    }

    private fun fixtureApps(): List<CaptureApp> = (1..16).map { index ->
        val suffix = index.toString().padStart(2, '0')
        CaptureApp("com.example.reader$suffix", "应用$suffix")
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
        compose.waitForIdle()
    }
}
