package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class SwissArgumentReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = 1_792_000_000_000L
    private val quote = "招聘岗位增加"
    private fun event(id: Long, evidence: String) = BrowseEvent(id, "reader", now - (10 - id) * 60_000,
        1, "$quote，因为$evidence", workKey = "work-$id")
    private fun semantic(event: BrowseEvent, evidence: String, emotions: List<ContentEmotion>? = null): EventSemantics {
        val unknown = ImpactSignal(ImpactLevel.UNKNOWN, "未评估", "")
        val presentation = emotions?.let { ClaimPresentation("就业", quote, unknown, unknown, unknown, listOf("核对来源"), it) }
        return EventSemantics(event.id, event.visibleText, now, listOf(ArgumentUnit(quote, "就业", "招聘岗位", "数量", "增加",
            evidence, "", false, presentation = presentation)))
    }

    @Test fun fullViewpointEvidenceOpensTheSecondOriginalAndReturnsToTheList() {
        val a = event(1, "报告甲记录岗位增加10%")
        val b = event(2, "报告乙记录岗位增加20%")
        val events = listOf(a, b)
        val models = listOf(semantic(a, "报告甲记录岗位增加10%"), semantic(b, "报告乙记录岗位增加20%"))
        val group = ViewpointTimeline.groups(ViewpointTimeline.build(events, StatisticsPeriod.HOUR, now,
            ZoneId.systemDefault(), semantics = models).flatMap { it.items }).single()
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissViewpointsScreen(events, models, null, StatisticsPeriod.HOUR, now, emptyMap(), null, {}, {})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("viewpoints-topic-就业").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("swiss-viewpoints-screen").performScrollToNode(hasTestTag("viewpoints-claim-${group.key}"))
        compose.onNodeWithTag("viewpoints-claim-${group.key}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("swiss-argument-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("报告甲记录岗位增加10%") and hasAnyAncestor(hasTestTag("swiss-argument-detail"))).assertExists()
        compose.onNodeWithTag("swiss-argument-detail").performScrollToNode(hasTestTag("argument-evidence-1-source-2"))
        compose.onNodeWithTag("argument-evidence-1-source-2").performClick()
        compose.onNodeWithTag("swiss-work-detail").assertExists()
        compose.onNodeWithTag("history-detail-close").performClick()
        compose.onNodeWithTag("swiss-argument-detail").assertExists()
        compose.onNodeWithTag("argument-evidence-1-source-2").assertIsDisplayed()
    }

    @Test fun reportEmotionLinksToItsOriginalAndDataRefreshClearsAnOpenDetail() {
        val evidence = "招聘变动令人焦虑"
        val a = event(1, evidence)
        val models = listOf(semantic(a, evidence, listOf(ContentEmotion(EmotionCategory.ANXIETY, ImpactLevel.HIGH, evidence, "招聘变动"))))
        val events = mutableStateOf(listOf(a))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissReportDialog(events.value, models, now, {})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("今天").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("swiss-report-content").performScrollToNode(hasTestTag("report-emotion-ANXIETY"))
        compose.onNodeWithTag("report-emotion-ANXIETY").performClick()
        compose.onNodeWithTag("swiss-argument-detail").performScrollToNode(hasTestTag("argument-evidence-0-source-1"))
        compose.onNodeWithTag("argument-evidence-0-source-1").performClick()
        compose.onNodeWithTag("swiss-work-detail").assertExists()
        compose.onNodeWithTag("history-detail-close").performClick()
        compose.runOnIdle { events.value = emptyList() }
        compose.onNodeWithTag("swiss-argument-detail").assertDoesNotExist()
        compose.onNodeWithTag("swiss-work-detail").assertDoesNotExist()
    }
}
