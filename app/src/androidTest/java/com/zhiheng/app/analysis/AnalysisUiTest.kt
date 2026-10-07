package com.zhiheng.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiheng.app.MainActivity
import com.zhiheng.app.data.BrowseEvent
import com.zhiheng.app.ui.*
import java.io.File
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisUiTest {
    @Test fun populatedAnalysisCardsRenderFromInMemoryFixture() {
        val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val records = List(12) { index -> BrowseEvent(index + 1L, "com.example.reader",
            now - (index % 3) * 86_400_000L - (index / 3) * 300_000L, 32,
            "当前就业形势很差，因为招聘岗位同比减少20%") }
        val report = CognitiveAnalyzer().analyze(records, StatisticsPeriod.WEEK, now)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = PageBackground)) {
                        Surface(Modifier.fillMaxSize()) {
                            LazyColumn { item {
                                AnalysisCards(AnalysisState(report = report), StatisticsPeriod.WEEK,
                                    records, {}, { _, _ -> })
                            } }
                        }
                    }
                }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            Thread.sleep(1000)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.cacheDir, "analysis-ui-qa.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
