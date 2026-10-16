package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class SwissChartUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val start = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
    private fun hour(index: Int, fresh: Int = index) = SwissEvidenceBucket("$index:00", fresh, 0, index,
        start + index * 3_600_000L, start + (index + 1) * 3_600_000L)

    @Test fun hourlySelectionKeepsItsTimeWhenWindowMovesAndUpdatesActualCounts() {
        var buckets by mutableStateOf(listOf(hour(0), hour(1), hour(2)))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            var selectedTime by remember { mutableStateOf<Long?>(null) }
            Column(Modifier.width(320.dp)) { SwissEvidenceChart(buckets, selectedTime) { selectedTime = it } }
        } } }
        compose.onNodeWithTag("swiss-evidence-next").assertIsNotEnabled()
        compose.onNodeWithTag("swiss-evidence-previous").performClick()
        compose.onNodeWithTag("swiss-evidence-date").assertTextEquals(swissEvidenceInterval(hour(1)))
        compose.runOnIdle { buckets = listOf(hour(1, fresh = 9), hour(2), hour(3)) }
        compose.onNodeWithTag("swiss-evidence-date").assertTextEquals(swissEvidenceInterval(hour(1)))
        compose.onNode(hasText("新增依据 9") and hasAnyAncestor(hasTestTag("swiss-evidence-readout"))).assertExists()
        compose.onNodeWithTag("swiss-evidence-previous").assertIsNotEnabled()
        compose.onNodeWithTag("swiss-evidence-plot").performTouchInput { click(Offset(width - 1f, height / 2f)) }
        compose.onNodeWithTag("swiss-evidence-date").assertTextEquals(swissEvidenceInterval(hour(3)))
    }

    @Test fun hourlyParentRefreshRetainsHistoricalSelectionAfterAsyncAnalysisReloads() {
        val quote = "当前互联网招聘需求下降"
        val evidence = "报告松岚记录岗位数量同比减少24%"
        val first = BrowseEvent(1L, "fixture.reader", start + 2 * 3_600_000L + 600_000L,
            32, "$quote，因为$evidence", sampleCount = 1, workKey = "work:1")
        var events by mutableStateOf(listOf(first))
        var now by mutableLongStateOf(start + 3 * 3_600_000L + 1_800_000L)
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            val semantics = remember(events) { events.map { event ->
                EventSemantics(event.id, event.visibleText, event.timestamp + 1,
                    listOf(ArgumentUnit(quote, "就业与职场", "互联网招聘需求", "岗位数量", "下降",
                        evidence, "当前", uncertain = false)))
            } }
            Column(Modifier.width(320.dp)) { SwissEvidenceTrend(events, semantics, now) }
        } } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("swiss-evidence-date").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("swiss-evidence-previous").performClick()
        compose.onNodeWithTag("swiss-evidence-date").assertTextEquals(swissEvidenceInterval(hour(2)))
        compose.onNode(hasText("待判断 1") and hasAnyAncestor(hasTestTag("swiss-evidence-readout"))).assertExists()

        // Changing the parent request replaces the chart with its loading state while real analysis runs.
        compose.runOnIdle {
            events = events + first.copy(id = 2L, timestamp = first.timestamp + 600_000L, workKey = "work:2")
            now += 31 * 60_000L
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("重复依据 1") and hasAnyAncestor(hasTestTag("swiss-evidence-readout")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("swiss-evidence-date").assertTextEquals(swissEvidenceInterval(hour(2)))
        compose.onNodeWithTag("swiss-evidence-next").assertIsEnabled()
    }

    @Test fun unknownDayCanBeSelectedAndReadAtLargeFontWithoutBecomingZero() {
        val date = LocalDate.of(2026, 10, 16)
        fun counts(unknown: Int) = ViewpointStance.entries.associateWith { if (it == ViewpointStance.UNKNOWN) unknown else 0 }
        val days = listOf(SwissStanceDay(date.minusDays(1), null, counts(2)),
            SwissStanceDay(date, 1f, counts(0) + (ViewpointStance.SUPPORT to 3)))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { SwissStanceTimeline(days) }
            }
        } } }
        compose.onNodeWithTag("swiss-stance-previous").performScrollTo().performClick()
        compose.onNodeWithTag("swiss-stance-date").assertTextEquals("2026-10-15")
        compose.onNode(hasText("未识别 2 篇") and hasAnyAncestor(hasTestTag("swiss-stance-readout")))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("swiss-stance-plot").assertContentDescriptionContains("2026-10-15 未识别", substring = true)
        compose.onNodeWithTag("swiss-stance-next").performScrollTo().performClick()
        compose.onNodeWithTag("swiss-stance-date").assertTextEquals("2026-10-16")
    }
}
