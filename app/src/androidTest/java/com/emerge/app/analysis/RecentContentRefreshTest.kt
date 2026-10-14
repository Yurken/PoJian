package com.emerge.app.analysis

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.RecentContentCloud
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.theme.EmergeTheme
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecentContentRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val midnight = LocalDate.of(2026, 10, 11).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val now = midnight + 12 * 3_600_000L
    private val records = listOf(
        BrowseEvent(1, "reader", now - 60_000, 32, "科技领域讨论隐私"),
        BrowseEvent(2, "reader", now - 60_000, 32, "社会领域讨论隐私"),
        BrowseEvent(3, "reader", midnight - 1_000, 32, "昨日讨论历史"))
    private fun semantics() = BackgroundReadGate(records.mapIndexed { index, event ->
        EventSemantics(event.id, event.visibleText, now, listOf(ArgumentUnit(event.visibleText,
            listOf("科技", "社会", "历史")[index], if (index < 2) "隐私保护" else "古代文化", "", "")))
    })

    @Test fun minuteRefreshKeepsTheCloudInPlaceWithoutOpeningStaleDetails() {
        val clock = mutableStateOf(now)
        val semantics = semantics()
        render(mutableStateOf(records), mutableStateOf(StatisticsPeriod.DAY), clock, semantics)
        awaitWord("隐私保护，2 条记录")
        val originalBounds = compose.onNodeWithTag("recent-content-card").getUnclippedBoundsInRoot()
        semantics.hold()
        try {
            compose.runOnIdle { clock.value += 60_000 }
            semantics.awaitRead()
            compose.onNodeWithText("正在整理近期内容…").assertDoesNotExist()
            compose.onNodeWithContentDescription("隐私保护，2 条记录").assertIsDisplayed().assertIsNotEnabled()
            assertEquals(originalBounds, compose.onNodeWithTag("recent-content-card").getUnclippedBoundsInRoot())
            compose.onNodeWithContentDescription("隐私保护，2 条记录").performTouchInput { click() }
            compose.onNodeWithTag("recent-content-details").assertDoesNotExist()
        } finally {
            semantics.release()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("隐私保护，2 条记录") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun periodSwitchDoesNotPresentThePreviousWindowWhileComputing() {
        val period = mutableStateOf(StatisticsPeriod.DAY)
        val semantics = semantics()
        render(mutableStateOf(records), period, mutableStateOf(now), semantics)
        awaitWord("隐私保护，2 条记录")
        semantics.hold()
        try {
            compose.runOnIdle { period.value = StatisticsPeriod.WEEK }
            semantics.awaitRead()
            compose.onNodeWithText("正在整理近期内容…").assertIsDisplayed()
            compose.onNodeWithTag("recent-content-cloud").assertDoesNotExist()
        } finally {
            semantics.release()
        }
        awaitWord("古代文化，1 条记录")
    }

    @Test fun categorySwitchKeepsItsSelectionAfterThePendingCalculationFinishes() {
        val semantics = semantics()
        render(mutableStateOf(records), mutableStateOf(StatisticsPeriod.DAY), mutableStateOf(now), semantics)
        awaitWord("隐私保护，2 条记录")
        semantics.hold()
        try {
            compose.onNodeWithTag("recent-content-category-科技").performClick()
            semantics.awaitRead()
            compose.onNodeWithText("正在整理近期内容…").assertIsDisplayed()
            compose.onNodeWithContentDescription("隐私保护，2 条记录").assertDoesNotExist()
        } finally {
            semantics.release()
        }
        awaitWord("隐私保护，1 条记录")
        compose.onNodeWithTag("recent-content-category-科技").assertIsSelected()
        compose.onNodeWithContentDescription("社会，1 条记录").assertDoesNotExist()
    }

    @Test fun returningToTheCompletedPeriodWhileAnotherCalculationIsPendingKeepsWordsClickable() {
        val period = mutableStateOf(StatisticsPeriod.DAY)
        val semantics = semantics()
        render(mutableStateOf(records), period, mutableStateOf(now), semantics)
        awaitWord("隐私保护，2 条记录")
        semantics.hold()
        try {
            compose.runOnIdle { period.value = StatisticsPeriod.WEEK }
            semantics.awaitRead()
            compose.onNodeWithTag("recent-content-cloud").assertDoesNotExist()
            compose.runOnIdle { period.value = StatisticsPeriod.DAY }
            compose.onNodeWithContentDescription("隐私保护，2 条记录").assertIsDisplayed().assertIsEnabled()
        } finally {
            semantics.release()
        }
        compose.onNodeWithContentDescription("隐私保护，2 条记录").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("今天 · 2 条相关记录").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("古代文化，1 条记录").assertDoesNotExist()
    }

    @Test fun clearingRecordsRemovesOpenDetailsBeforeTheRefreshFinishes() {
        val events = mutableStateOf(records)
        val semantics = semantics()
        render(events, mutableStateOf(StatisticsPeriod.DAY), mutableStateOf(now), semantics)
        awaitWord("隐私保护，2 条记录")
        compose.onNodeWithContentDescription("隐私保护，2 条记录").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("今天 · 2 条相关记录").fetchSemanticsNodes().isNotEmpty() }
        semantics.hold()
        try {
            compose.runOnIdle { events.value = emptyList() }
            semantics.awaitRead()
            compose.onNodeWithText("今天 · 2 条相关记录").assertDoesNotExist()
            compose.onNodeWithText("正在整理相关内容…").assertIsDisplayed()
        } finally {
            semantics.release()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("暂无关键词").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recent-content-details").assertDoesNotExist()
    }

    private fun render(events: MutableState<List<BrowseEvent>>, period: MutableState<StatisticsPeriod>,
        clock: MutableState<Long>, semantics: List<EventSemantics>) {
        compose.runOnUiThread { compose.activity.setContent {
            EmergeTheme { Column { RecentContentCloud(events.value, period.value, clock.value, semantics) } }
        } }
    }

    private fun awaitWord(description: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    }

    // Keep the real analysis and Compose lifecycle, delaying only background reads.
    private class BackgroundReadGate<T>(private val values: List<T>) : AbstractList<T>() {
        @Volatile private var resume = CountDownLatch(0)
        @Volatile private var started = CountDownLatch(0)
        override val size: Int get() = values.size
        override fun get(index: Int): T {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                started.countDown()
                check(resume.await(15, TimeUnit.SECONDS)) { "Background read was not released" }
            }
            return values[index]
        }
        fun hold() {
            started = CountDownLatch(1)
            resume = CountDownLatch(1)
        }
        fun awaitRead() {
            assertTrue("Refresh analysis should start", started.await(5, TimeUnit.SECONDS))
        }
        fun release() { resume.countDown() }
    }
}
