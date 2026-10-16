package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.BuildConfig
import com.emerge.app.MainActivity
import com.emerge.app.data.LocalStorageUsage
import com.emerge.app.ui.LocalStorageSettingsCard
import com.emerge.app.update.UpdateSettingsSection
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

    @Test fun embeddedCapacityEditRequiresCleanupConfirmationAndCancelPreservesCurrentLimit() {
        var usage by mutableStateOf(LocalStorageUsage(usedBytes = 600_000_000L, loading = false))
        var saves = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { LocalStorageSettingsCard(usage, true, {}, {
                usage = usage.copy(limitBytes = it); saves++
            }, embedded = true) }
        } }
        compose.onNodeWithText("本地存储空间").assertDoesNotExist()
        compose.onNodeWithTag("local-storage").assertIsDisplayed()
        compose.onNodeWithText("5 GB").assertIsDisplayed()
        listOf("256 MB", "512 MB", "1 GB", "2 GB").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithTag("storage-limit").performClick()
        compose.onNodeWithTag("storage-limit-input").performTextReplacement("0.256")
        compose.onNodeWithText("超出上限的最早记录将被删除。").assertIsDisplayed()
        compose.onNodeWithText("保存并清理").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(0, saves)
            assertEquals(5_000_000_000L, usage.limitBytes)
        }
        compose.onNodeWithTag("storage-limit").performClick()
        compose.onNodeWithTag("storage-limit-input").assertTextContains("5")
        compose.onNodeWithTag("storage-limit-input").performTextReplacement("0.512")
        compose.onNodeWithText("保存并清理").performClick()
        compose.waitUntil { saves == 1 }
        compose.runOnIdle { assertEquals(512_000_000L, usage.limitBytes) }
    }

    @Test fun manualCapacityLimitsSaveDecimalBytesOnlyAfterExplicitSave() {
        var usage by mutableStateOf(LocalStorageUsage(usedBytes = 128_000_000L, loading = false))
        var saves = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { LocalStorageSettingsCard(usage, true, {}, {
                usage = usage.copy(limitBytes = it); saves++
            }, embedded = true) }
        } }
        listOf(
            "0.256" to 256_000_000L,
            "0.512" to 512_000_000L,
            "1" to 1_000_000_000L,
            "2" to 2_000_000_000L
        ).forEachIndexed { index, (draft, bytes) ->
            compose.onNodeWithTag("storage-limit").performClick()
            compose.onNodeWithTag("storage-limit-input").performTextReplacement(draft)
            compose.runOnIdle { assertEquals(index, saves) }
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil { saves == index + 1 }
            compose.runOnIdle { assertEquals(bytes, usage.limitBytes) }
        }
    }

    @Test fun retentionDraftNeedsConfirmationAndCancelPreservesSavedPeriod() {
        var usage by mutableStateOf(LocalStorageUsage(loading = false))
        var saves = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { LocalStorageSettingsCard(usage, true, {}, {}, onSaveRetention = {
                usage = usage.copy(retentionDays = it); saves++
            }) }
        } }
        compose.onNodeWithTag("storage-retention").performClick()
        compose.onNodeWithTag("storage-retention-option-0").assertIsSelected()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("storage-retention-option-7").performClick().assertIsSelected()
        compose.onNodeWithText("超出保留周期的记录将被删除。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, usage.retentionDays) }
        compose.onNodeWithTag("storage-retention").performClick()
        compose.onNodeWithTag("storage-retention-option-0").assertIsSelected()
        compose.onNodeWithTag("storage-retention-option-30").performClick()
        compose.onNodeWithText("保存并清理").performClick()
        compose.waitUntil { saves == 1 }
        compose.onNodeWithTag("storage-retention").assertTextContains("30天")
        compose.onNodeWithTag("storage-retention").performClick()
        compose.onNodeWithTag("storage-retention-option-30").assertIsSelected()
        compose.onNodeWithTag("storage-retention-option-90").performClick()
        compose.onNodeWithText("超出保留周期的记录将被删除。").assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { saves == 2 }
        compose.onNodeWithTag("storage-retention").performClick()
        compose.onNodeWithTag("storage-retention-option-0").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { saves == 3 }
        compose.runOnIdle { assertEquals(0, usage.retentionDays) }
    }

    @Test fun failedRetentionSaveKeepsSelectionAndCanBeRetried() {
        var usage by mutableStateOf(LocalStorageUsage(loading = false))
        var attempts = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { LocalStorageSettingsCard(usage, true, {}, {}, onSaveRetention = {
                attempts++
                if (attempts == 1) error("storage")
                usage = usage.copy(retentionDays = it)
            }) }
        } }
        compose.onNodeWithTag("storage-retention").performClick()
        compose.onNodeWithTag("storage-retention-option-7").performClick()
        compose.onNodeWithText("保存并清理").performClick()
        compose.waitUntil { compose.onAllNodesWithText("保存失败，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("storage-retention-option-7").assertIsSelected()
        compose.onNodeWithText("保存并清理").performClick()
        compose.waitUntil { attempts == 2 && compose.onAllNodesWithTag("storage-retention-option-7").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(7, usage.retentionDays) }
    }

    @Test fun embeddedUpdateShowsInstalledVersionAndOnlyConfiguredCheckAction() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { UpdateSettingsSection(embedded = true) }
        } }
        compose.onNodeWithText("当前版本").assertIsDisplayed()
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("应用更新").assertDoesNotExist()
        if (BuildConfig.UPDATE_MANIFEST_URL.isBlank()) {
            compose.onNodeWithTag("check-update").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("check-update").assertIsDisplayed()
        }
    }
}
