package com.emerge.app.update

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.BuildConfig
import com.emerge.app.MainActivity
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val release = UpdatePolicy.release(27, "0.5.5", "https://example.com/app.apk", "ab".repeat(32))

    @Test fun emptyConfiguredEndpointLeavesAppUninterrupted() {
        assertTrue(BuildConfig.UPDATE_MANIFEST_URL.isEmpty())
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.onNodeWithTag("overview-list").assertIsDisplayed()
    }

    @Test fun offerRequiresExplicitDownloadAndCanBeDismissed() {
        var downloads = 0
        var dismissals = 0
        compose.activity.setContent { EmergeTheme { UpdateDialog(UpdateUiState(release), { downloads++ }, {}, { dismissals++ }) } }
        compose.onNodeWithText("v0.5.5").assertIsDisplayed()
        compose.onNodeWithText("立即更新").performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun downloadShowsRealProgressAndAllowsCancellation() {
        var cancelled = false
        compose.activity.setContent { EmergeTheme {
            UpdateDialog(UpdateUiState(release, UpdateStage.DOWNLOADING, progress = 42), {}, {}, { cancelled = true })
        } }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("安装").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun readyPackageRequiresInstallActionAndShowsPermissionFailure() {
        var installs = 0
        compose.activity.setContent { EmergeTheme {
            UpdateDialog(UpdateUiState(release, UpdateStage.READY, error = "未允许安装更新。"), {}, { installs++ }, {})
        } }
        compose.onNodeWithText("未允许安装更新。").assertIsDisplayed()
        compose.onNodeWithText("安装").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test fun failedDownloadCanRetry() {
        var retries = 0
        compose.activity.setContent { EmergeTheme {
            UpdateDialog(UpdateUiState(release, error = "更新下载失败，请重试。"), { retries++ }, {}, {})
        } }
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
