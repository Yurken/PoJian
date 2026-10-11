package com.emerge.app.capture.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.capture.VisibilityInterval
import com.emerge.app.capture.VisibilityIntervals
import com.emerge.app.capture.VisibilityRegion
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.CaptureVisibilityBreakdown
import com.emerge.app.ui.theme.EmergeTheme
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic content replaces the activity UI and never writes an application record. */
@RunWith(AndroidJUnit4::class)
class CaptureVisibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun exactHourlyRegionsExcludePausedGapAndLegacyKeepsItsOwnMethodNotice() {
        val zone = ZoneId.of("Asia/Shanghai")
        val hour = ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val exact = BrowseEvent(4901, "synthetic.reader", hour - 10_000, 32, "合成正文与评论",
            endTimestamp = hour + 12_000, durationMillis = 10_000, sampleCount = 6,
            visibilityIntervals = VisibilityIntervals.encode(listOf(
                VisibilityInterval(hour - 10_000, hour - 6000, 4000, VisibilityRegion.MAIN),
                VisibilityInterval(hour - 6000, hour - 4000, 2000, VisibilityRegion.COMMENTS),
                // No visible interval bridges the pause from 11:59:56 through 12:00:08.
                VisibilityInterval(hour + 8000, hour + 12_000, 4000, VisibilityRegion.UNLOCATED))))
        val event = mutableStateOf(exact)
        val selectedHour = mutableStateOf<Int?>(null)
        val window = mutableStateOf(ObservationWindow(hour - 20_000, hour + 20_000, zone))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(24.dp)) {
                    CaptureVisibilityBreakdown(event.value, window.value, selectedHour.value)
                }
            }
        } } }
        assertRegions(4, 2, 4)
        compose.onNodeWithText("只统计实际记录的可见区间；页面切换时段归为未定位，暂停和缺失窗口不计时。评论区停留尚未分配到单条评论。")
            .assertIsDisplayed()
        compose.runOnIdle { selectedHour.value = 11 }
        assertRegions(4, 2, 0)
        compose.runOnIdle { selectedHour.value = 12 }
        assertRegions(0, 0, 4)
        compose.onNodeWithText("未定位：12 秒").assertDoesNotExist()
        saveScreenshot()
        compose.runOnIdle { window.value = ObservationWindow(hour + 9000, hour + 11_000, zone) }
        assertRegions(0, 0, 2)
        compose.runOnIdle { event.value = exact.copy(visibilityIntervals = null) }
        compose.onNodeWithText("旧记录只有整次访问的时长估计，未区分正文和评论区。")
            .assertIsDisplayed()
        compose.onNodeWithTag("capture-region-times-${exact.id}").assertDoesNotExist()
    }

    private fun assertRegions(main: Int, comments: Int, unlocated: Int) {
        compose.onNodeWithText("正文/视频页面：$main 秒").assertIsDisplayed()
        compose.onNodeWithText("评论区：$comments 秒").assertIsDisplayed()
        compose.onNodeWithText("未定位：$unlocated 秒").assertIsDisplayed()
    }

    private fun saveScreenshot() {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "validation0.4.9")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "capture-visibility-hour.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
