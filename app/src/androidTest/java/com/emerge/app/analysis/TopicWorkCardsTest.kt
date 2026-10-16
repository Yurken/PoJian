package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.MainActivity
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.BrowseWorkCards
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Device specifications: compilation alone does not verify scrolling or rendered colors. */
@RunWith(AndroidJUnit4::class)
class TopicWorkCardsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cardsShowTopicsShortViewpointsAndTimeThenOpenActualTextAndAnalysis() {
        val quote = "在岗位要求相近时，应届生需要结合岗位要求准备项目"
        val raw = "$quote。这里是保存的完整正文与说明，只能在作品详情里展示。"
        val stored = "分享\n点赞 1.2万\n$raw\n关注\n评论(36)\n按钮\n直播\n推荐"
        val row = BrowseEvent(1, "reader", 1_000, 0, stored, endTimestamp = 121_000, durationMillis = 120_000,
            sampleCount = 2, workKey = "work-a")
        val signal = ImpactSignal(ImpactLevel.HIGH, "持续只看同类材料可能强化担忧，需要结合适用条件判断。", quote)
        val presentation = ClaimPresentation("求职", "岗位要求相近时准备项目", signal, signal, signal,
            listOf("核对岗位要求，并比较不同处境的材料。"))
        val claim = ViewpointClaim(quote, "求职", "应届生", "准备项目", "支持", uncertain = false,
            analysisSource = "structured_model", record = row, presentation = presentation, inputKind = "text")
        val item = ViewpointItem(row, claim.label, quote, false, listOf(claim), "structured_model")
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            BrowseWorkCards(listOf(item), 0, 200_000, mapOf("reader" to "阅读器"), ZoneId.of("UTC"))
        } } }
        compose.onNodeWithTag("work-topic-求职").assertIsDisplayed()
        compose.onNodeWithText(presentation.viewpoint).assertIsDisplayed()
        compose.onNodeWithText("2 分 0 秒").assertIsDisplayed()
        compose.onAllNodesWithText(raw).assertCountEquals(0)
        compose.onNodeWithTag("work-card-${item.workIdentity}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "影响程度：高")).performClick()
        compose.onNodeWithTag("work-detail-content").performScrollToNode(hasText("大模型分析"))
        compose.onNodeWithText("大模型分析").assertIsDisplayed()
        compose.onNodeWithTag("work-detail-content").performScrollToNode(hasText(presentation.suggestions.single()))
        compose.onNodeWithText(presentation.suggestions.single()).assertIsDisplayed()
        compose.onNodeWithTag("work-detail-content").performScrollToNode(hasText(raw))
        compose.onAllNodesWithText(raw).onLast().assertIsDisplayed()
        compose.onAllNodesWithText(stored).assertCountEquals(0)
        listOf("分享", "点赞 1.2万", "关注", "评论(36)", "按钮", "直播", "推荐").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
    }
}
