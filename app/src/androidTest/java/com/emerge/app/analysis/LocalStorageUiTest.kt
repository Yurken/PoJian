package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.data.LocalStorageUsage
import com.emerge.app.ui.LocalStorageSettingsCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStorageUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capacityAcceptsDecimalsRejectsInvalidValuesAndCancelDiscardsDraft() {
        var usage by mutableStateOf(LocalStorageUsage(usedBytes = 128_000_000L, loading = false))
        var saves = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { LocalStorageSettingsCard(usage, true, {}, {
                usage = usage.copy(limitBytes = it); saves++
            }) }
        } }
        compose.onNodeWithText("5 GB").assertIsDisplayed()
        compose.onNodeWithText("5000 条").assertDoesNotExist()
        compose.onNodeWithTag("storage-limit").performClick()
        compose.onNodeWithTag("storage-limit-input").performTextReplacement("0")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("storage-limit-input").performTextReplacement("2.5")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("storage-limit").performClick()
        compose.onNodeWithTag("storage-limit-input").assertTextEquals("容量上限（GB）", "5")
        compose.onNodeWithTag("storage-limit-input").performTextReplacement("2.5")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { saves == 1 }
        compose.onNodeWithText("2.5 GB").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2_500_000_000L, usage.limitBytes) }
    }

    @Test fun loweringBelowUsageRequiresExplicitCleanupActionAndFailedSaveKeepsDraft() {
        val usage = LocalStorageUsage(usedBytes = 128_000_000L, loading = false)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { LocalStorageSettingsCard(usage, true, {}, { error("storage") }) }
        } }
        compose.onNodeWithTag("storage-limit").performClick()
        compose.onNodeWithTag("storage-limit-input").performTextReplacement("0.1")
        compose.onNodeWithText("保存并清理").assertIsEnabled()
        compose.onNodeWithText("超出上限的最早记录将被删除。").assertIsDisplayed()
        compose.onNodeWithText("保存并清理").performClick()
        compose.waitUntil { compose.onAllNodesWithText("保存失败，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("storage-limit-input").assertTextContains("0.1")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("5 GB").assertIsDisplayed()
    }
}
