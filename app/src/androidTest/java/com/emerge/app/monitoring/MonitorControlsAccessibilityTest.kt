package com.emerge.app.monitoring

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorControlsAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cloudSwitchHasOneNamedActionAcrossItsLabelAndTrack() {
        val cloud = mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        var monitorChanges = 0
        render {
            MonitorSettingsSection(MonitorConfig(), { monitorChanges++ }, 0, 0, {}, {},
                cloudEnabled = cloud.value, onCloudEnabledChange = {
                    changes += it
                    cloud.value = it
                })
        }

        val tag = "monitor-switch-云端分析"
        val control = compose.onNodeWithTag(tag)
        control.assertTextContains("云端分析")
            .assertTextContains("发送文字；开启画面分析时发送采样画面")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
        compose.onAllNodes(isToggleable() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true).assertCountEquals(1)

        compose.onNodeWithText("云端分析", useUnmergedTree = true).performTouchInput { click() }
        control.assertIsOn()
        compose.runOnIdle { assertEquals(listOf(true), changes) }

        control.performTouchInput { click(Offset(width - 12f, height / 2f)) }
        control.assertIsOff()
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }

        control.performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(true, false, true), changes)
            assertEquals(0, monitorChanges)
        }
    }

    @Test fun sliderAnnouncesItsSettingAndUnitsAndCommitsAccessibilityAdjustment() {
        val config = mutableStateOf(MonitorConfig(frameIntervalSeconds = 30))
        val changes = mutableListOf<MonitorConfig>()
        render {
            MonitorSettingsSection(config.value, {
                changes += it
                config.value = it
            }, 0, 0, {}, {})
        }

        val slider = compose.onNodeWithContentDescription("画面采样间隔")
        slider.performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "30 秒"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(30f, 15f..120f)))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "60 秒"))
        compose.runOnIdle {
            assertEquals(listOf(MonitorConfig(frameIntervalSeconds = 60)), changes)
            config.value = config.value.copy(frameIntervalSeconds = 45)
        }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "45 秒"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(45f, 15f..120f)))
    }

    @Test fun moreStartsCollapsedAndRetainsAllThreeReminderSettingsAcrossExpansion() {
        val config = mutableStateOf(MonitorConfig(cooldownMinutes = 37, dailyAlerts = 8, displaySeconds = 4))
        val changes = mutableListOf<MonitorConfig>()
        render {
            MonitorSettingsSection(config.value, {
                changes += it
                config.value = it
            }, 0, 0, {}, {})
        }

        val settings = listOf("提醒间隔", "每日提醒上限", "提示停留时长")
        settings.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("画面采样间隔").assertExists()
        val more = compose.onNodeWithTag("monitor-more")
        more.performScrollTo().assertTextContains("更多").assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
            .performClick()
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        listOf("37 分钟", "8 次", "4 秒").forEachIndexed { index, value ->
            compose.onNodeWithContentDescription(settings[index]).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
        }

        listOf(45f, 12f, 6f).forEachIndexed { index, value ->
            compose.onNodeWithContentDescription(settings[index]).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        more.performScrollTo().performClick()
        settings.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        more.performClick()
        listOf("45 分钟", "12 次", "6 秒").forEachIndexed { index, value ->
            compose.onNodeWithContentDescription(settings[index]).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
        }
        compose.runOnIdle {
            assertEquals(MonitorConfig(cooldownMinutes = 45, dailyAlerts = 12, displaySeconds = 6), config.value)
            assertEquals(3, changes.size)
        }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                EmergeTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
