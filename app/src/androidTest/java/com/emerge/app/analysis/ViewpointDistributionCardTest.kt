package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.DailyViewpointDistribution
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Rule
import org.junit.Test

class ViewpointDistributionCardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun spectrumSelectionCanClearAndRepresentativeRowOpensSources() {
        val positive = item(1, "支持", "方案甲")
        val negative = item(2, "反对", "方案乙")
        val groups = ViewpointTimeline.groups(listOf(positive, negative))
        render(groups)
        compose.onNodeWithTag("viewpoint-spectrum-SUPPORT").assertContentDescriptionEquals("支持，1个作品，50.0%")
        compose.onNodeWithTag("viewpoint-spectrum-QUESTION").assertContentDescriptionEquals("质疑，1个作品，50.0%")
        compose.onNodeWithTag("viewpoint-spectrum-SUPPORT").performClick()
        compose.onNodeWithTag("viewpoint-topic-subject:方案乙").assertDoesNotExist()
        compose.onNodeWithTag("viewpoint-stance-SUPPORT").assertIsSelected().performClick()
        compose.onNodeWithTag("viewpoint-topic-subject:方案乙").assertExists()
        val entry = groups.single { it.items.single().record.id == 1L }
        compose.onNodeWithTag("viewpoint-entry-${entry.key}").assertDoesNotExist()
        compose.onNodeWithTag("viewpoint-topic-subject:方案甲").performScrollTo()
        compose.onNode(hasText("方案甲") and hasAnyAncestor(hasTestTag("daily-viewpoint-pie")))
            .performClick()
        compose.onNodeWithTag("viewpoint-entry-${entry.key}").performScrollTo().performClick()
        compose.onNodeWithTag("viewpoint-source-sheet").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("viewpoint-source-sheet").assertDoesNotExist()
    }

    @Test fun spectrumHandlesMixedAndUnknownWorksWithLargeTextInDarkTheme() {
        render(ViewpointTimeline.groups(listOf(item(1, "支持", "方案甲"), item(1, "反对", "方案甲"),
            item(2, "尚待验证", "方案乙"))), dark = true)
        compose.onNodeWithTag("viewpoint-spectrum-MIXED").assertContentDescriptionEquals("多立场，1个作品，50.0%")
        compose.onNodeWithTag("viewpoint-spectrum-UNKNOWN").assertContentDescriptionEquals("未识别，1个作品，50.0%")
        compose.onNodeWithTag("viewpoint-stance-UNKNOWN").performScrollTo().performClick()
        compose.onNodeWithTag("viewpoint-topic-subject:方案甲").assertDoesNotExist()
        compose.onNodeWithTag("viewpoint-topic-subject:方案乙").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("viewpoint-search").performScrollTo().performTextInput("无匹配")
        compose.onNodeWithText("无匹配观点").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("viewpoint-spectrum").assertDoesNotExist()
        compose.onNodeWithTag("viewpoint-search").performScrollTo().performTextClearance()
        compose.onNodeWithTag("viewpoint-stance-UNKNOWN").assertIsNotSelected()
    }

    private fun item(id: Long, stance: String, subject: String): ViewpointItem {
        val event = BrowseEvent(id, "reader", 1000, 32, "关于$subject 的原文", workKey = "work:$id")
        val claim = ViewpointClaim(event.visibleText, "技术", subject, "应用", stance,
            analysisSource = "structured_model", record = event)
        return ViewpointItem(event, claim.label, claim.quote, false, listOf(claim), "structured_model", workIdentity = "work:$id")
    }

    private fun render(groups: List<ViewpointShare>, dark: Boolean = false) {
        compose.runOnUiThread { compose.activity.setContent {
            EmergeTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark) 2f else 1f)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            DailyViewpointDistribution(groups, "10/13", mapOf("reader" to "阅读器"), 0, 2000)
                        }
                    }
                }
            }
        } }
    }
}
