package com.emerge.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

internal val swissPeriods = listOf(StatisticsPeriod.HOUR, StatisticsPeriod.HOURS24, StatisticsPeriod.MONTH)
private val periodLabels = listOf("1H", "24H", "30D")

@Composable
internal fun SwissAppHeader(title: String, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, status: String, onRecording: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val controlStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    fun textWidth(text: String, titleText: Boolean = false) = with(density) {
        measurer.measure(text, if (titleText) titleStyle else controlStyle, softWrap = false).size.width.toDp()
    }
    val titleWidth = textWidth(title, true) + 42.dp
    val periodsWidth = periodLabels.map { textWidth(it) + 16.dp }.reduce { a, b -> a + b } + 8.dp
    val statusWidth = textWidth(status) + 38.dp
    val brand: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SwissBrandLogo(Modifier.size(32.dp))
            Text(title, style = titleStyle)
        }
    }
    val periods: @Composable () -> Unit = {
        PojianSegmentedGroup {
            swissPeriods.forEachIndexed { index, option ->
                PojianSegmentOption(period == option, { onPeriod(option) },
                    Modifier.testTag("header-period-${option.name}")) {
                    Text(periodLabels[index], maxLines = 1)
                }
            }
        }
    }
    val recording: @Composable () -> Unit = {
        Surface(onClick = onRecording, shape = DesignTokens.PillShape,
            color = Color.Transparent, contentColor = Color.White) {
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.background(DesignTokens.ControlInk, DesignTokens.PillShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecordingDot(active)
                    Text(status, style = controlStyle, maxLines = 1)
                }
            }
        }
    }
    Surface(modifier, color = scheme.surface, tonalElevation = 0.dp) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Measure the actual labels so long states and larger system fonts wrap before they collide.
                if (maxWidth >= titleWidth + periodsWidth + statusWidth + 30.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        brand()
                        Spacer(Modifier.weight(1f))
                        periods()
                        recording()
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    brand()
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        periods()
                        Spacer(Modifier.width(12.dp))
                        recording()
                    }
                }
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .75f))
        }
    }
}

@Composable
private fun RecordingDot(active: Boolean) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = active && rememberSystemMotionEnabled() && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val opacity = if (animate) {
        val transition = rememberInfiniteTransition(label = "recording")
        transition.animateFloat(.45f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "recording-dot").value
    } else 1f
    Box(Modifier.size(8.dp).clip(CircleShape).background((if (active) DesignTokens.Emerald else Color(0xFFA1A1AA)).copy(alpha = opacity)))
}
