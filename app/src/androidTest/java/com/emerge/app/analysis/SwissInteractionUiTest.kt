package com.emerge.app.analysis

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.emerge.app.MainActivity
import com.emerge.app.ui.SwissCard
import com.emerge.app.ui.theme.EmergeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SwissInteractionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cardPressCancellationDoesNotNavigateAndConfirmedClickRunsOnce() {
        var opens = 0
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            Column {
                SwissCard(Modifier.testTag("interactive-card"), onClick = { opens++ }) { Text("浏览历史") }
                SwissCard(Modifier.testTag("static-card")) { Text("内容集中度") }
            }
        } } }
        compose.onNodeWithTag("static-card").assertHasNoClickAction()
        compose.onNodeWithTag("interactive-card").performTouchInput { down(center); cancel() }
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("interactive-card").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithTag("interactive-card").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(2, opens) }
    }
}
