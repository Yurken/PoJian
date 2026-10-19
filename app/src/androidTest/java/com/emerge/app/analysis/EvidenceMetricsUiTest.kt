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

class EvidenceMetricsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun newFactsUseTheCurrentReportAndHideStaleResults() {
        val report = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.DAY, 1_792_000_000_000L)
        val fact = EvidenceFragment("岗位增加10%", EvidenceType.FACT, isNew = true)
        val claim = ClaimObservation(1, report.end, "reader", "就业", "岗位增加", listOf(fact.text), "",
            false, Increment.NEW, 0, evidenceDetails = listOf(fact))
        val state = mutableStateOf(AnalysisState(report = report.copy(claims = listOf(claim))))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            CocoonCard(state.value, StatisticsPeriod.DAY, true)
        } } }
        compose.onNode(hasText("1") and hasAnyAncestor(hasTestTag("cocoon-new-facts"))).assertExists()
        compose.runOnIdle { state.value = state.value.copy(stale = true) }
        compose.onNode(hasText("—") and hasAnyAncestor(hasTestTag("cocoon-new-facts"))).assertExists()
    }

    @Test fun sourcesDisplayCalculatedRootsInsteadOfTheFixedUnknown() {
        val text = "岗位增加，因为青禾研究所独立调查300家企业，形成《甲样本》，岗位增加10%"
        val evidence = text.substringAfter("因为")
        val event = BrowseEvent(1, "reader", 1000, 1, text, workKey = "sample")
        val semantics = EventSemantics(1, text, 2000, listOf(ArgumentUnit("岗位增加", "就业", "岗位", "数量", "增加",
            evidence, uncertain = false, sourceProvenance = SourceProvenance(true,
                listOf(EvidenceSource("青禾研究所", "《甲样本》", "primary", evidence))))))
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            SwissViewpointsScreen(listOf(event), listOf(semantics), null, StatisticsPeriod.HOURS24, 3000,
                emptyMap(), null, {}, {})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("1") and
            hasAnyAncestor(hasTestTag("viewpoints-independent-sources"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("1") and hasAnyAncestor(hasTestTag("viewpoints-independent-sources"))).assertIsDisplayed()
    }
}
