package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CocoonUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun state(repeated: Int, total: Int = 5, period: StatisticsPeriod = StatisticsPeriod.DAY): AnalysisState {
        val report = CognitiveAnalyzer().analyze(emptyList(), period, 1_792_000_000_000L)
        val claims = (0 until total).map { i ->
            ClaimObservation(i.toLong(), report.end, "test", "主题", "观点", emptyList(), "", false,
                if (i < repeated) Increment.REPEATED else Increment.NEW, null)
        }
        val dist = ExposureDistribution(total * 6, 1, total * 6, mapOf("主题" to 1.0), mapOf("test" to total * 6))
        val views = claims.firstOrNull()?.let { listOf(ViewpointExposure("fixture", "主题", "对象", "维度", "支持", dist,
            repeated.toDouble() / total, null, null, 1, false, it, listOf(it.eventId))) }.orEmpty()
        return AnalysisState(report = report.copy(claims = claims,
            exposure = ExposureAssessment(report.end - 7 * 86_400_000L, report.end, dist, "主题", null,
                views.map { it.copy(share = .2) }, claims),
            periodExposure = ExposureAssessment(report.start, report.end, dist, "主题", null, views, claims)))
    }

    @Test fun overviewShowsActualShareAndDoesNotReuseStaleOrWrongPeriodData() {
        val analysis = mutableStateOf(state(3))
        val period = mutableStateOf(StatisticsPeriod.DAY)
        compose.runOnUiThread {
            compose.activity.setContent {
                EmergeTheme {
                    OverviewScreen(calculateStatistics(emptyList(), period.value, 1_792_000_000_000L),
                        period.value, { period.value = it }, false, "已暂停", emptyMap(), {}, {}, analysis = analysis.value)
                }
            }
        }
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("cocoon-card"))
        compose.onNodeWithText("观点集中度 · 今天").assertIsDisplayed()
        compose.onNodeWithText("信息视野").assertIsDisplayed()
        compose.onNodeWithTag("cocoon-share").assertTextEquals("60%")
        compose.onNodeWithText("3 / 5 条观点引用重复依据").assertIsDisplayed()
        compose.onNodeWithTag("cocoon-density").assertDoesNotExist()
        compose.runOnIdle { analysis.value = analysis.value.copy(stale = true) }
        compose.onNodeWithTag("cocoon-share").assertTextEquals("—")
        compose.onNodeWithText("待更新").assertIsDisplayed()
        compose.runOnIdle { analysis.value = state(3); period.value = StatisticsPeriod.WEEK }
        compose.onNodeWithText("信息视野").assertIsDisplayed()
        compose.onNodeWithTag("cocoon-share").assertTextEquals("—")
        compose.onNodeWithText("暂无分析").assertIsDisplayed()
        StatisticsPeriod.entries.forEachIndexed { index, selected ->
            compose.runOnIdle {
                period.value = selected
                analysis.value = state(index + 1, period = selected)
            }
            compose.onNodeWithText("观点集中度 · ${selected.label}").assertIsDisplayed()
            compose.onNodeWithText("话题集中度 · ${selected.label}").assertIsDisplayed()
            compose.onNodeWithText("主要立场占比 · ${selected.label}").assertIsDisplayed()
            compose.onNodeWithTag("cocoon-share").assertTextEquals("${(index + 1) * 20}%")
            compose.onNodeWithText("观点集中度 · 近7天").assertDoesNotExist()
        }
    }

    @Test fun ringSupportsSystemDarkPreferenceLargeTextAndDataExtremes() {
        val analysis = mutableStateOf(state(0))
        for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                compose.activity.setContent {
                    EmergeTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (dark) 1.5f else 1f)) {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    CocoonCard(analysis.value, StatisticsPeriod.DAY, visible = false)
                                }
                            }
                        }
                    }
                }
            }
            for (repeated in listOf(0, 3, 5)) {
                compose.runOnIdle { analysis.value = state(repeated) }
                compose.onNodeWithTag("cocoon-share").performScrollTo().assertTextEquals("${repeated * 20}%")
                screenshot("cocoon-${if(dark) "dark-large" else "light"}-${repeated * 20}.png")
            }
            compose.runOnIdle { analysis.value = state(0, 0) }
            compose.onNodeWithTag("cocoon-share").assertTextEquals("—")
            compose.onNodeWithContentDescription("观察不足").assertExists()
            screenshot("cocoon-${if(dark) "dark-large" else "light"}-insufficient.png")
            compose.runOnIdle { analysis.value = AnalysisState() }
            compose.onNodeWithTag("cocoon-share").assertTextEquals("—")
            compose.onNodeWithText("暂无分析").assertIsDisplayed()
            compose.onNodeWithTag("cocoon-play").assertDoesNotExist()
            screenshot("cocoon-${if(dark) "dark-large" else "light"}-initial.png")
        }
    }

    @Test fun measuredChangeUpdatesTheRingAndSettlesWithoutPerpetualMotion() {
        val density = mutableStateOf(.2f)
        val moving = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            compose.activity.setContent {
                EmergeTheme { CocoonVisual(density.value, false, moving.value, Modifier.size(280.dp)) }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val first = compose.onNodeWithTag("cocoon-visual").captureToImage().asAndroidBitmap()
        compose.runOnUiThread { density.value = .8f }
        compose.mainClock.advanceTimeBy(800)
        val next = compose.onNodeWithTag("cocoon-visual").captureToImage().asAndroidBitmap()
        assertFalse("A changed measurement must update the ring", first.sameAs(next))
        compose.onNodeWithContentDescription("观点集中度 80.0%").assertExists()
        compose.runOnUiThread { moving.value = false }
        compose.mainClock.advanceTimeBy(400)
        val paused = compose.onNodeWithTag("cocoon-visual").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(600)
        assertTrue("A settled ring stays still", paused.sameAs(compose.onNodeWithTag("cocoon-visual").captureToImage().asAndroidBitmap()))
    }

    @Test fun disabledSystemMotionKeepsMeasurementsVisibleWithoutPlaybackControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val key = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        val original = android.provider.Settings.Global.getFloat(resolver, key, 1f)
        fun setScale(value: Float) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $value")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        try {
            setScale(0f)
            compose.runOnUiThread {
                compose.activity.setContent { EmergeTheme { CocoonCard(state(3), StatisticsPeriod.DAY, true) } }
            }
            compose.onNodeWithTag("cocoon-play").assertDoesNotExist()
            compose.onNodeWithTag("cocoon-share").assertTextEquals("60%")
            val still = compose.onNodeWithTag("cocoon-visual").captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(600)
            assertTrue(still.sameAs(compose.onNodeWithTag("cocoon-visual").captureToImage().asAndroidBitmap()))
        } finally { setScale(original) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
