package com.emerge.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

internal val swissPeriods = listOf(StatisticsPeriod.HOUR, StatisticsPeriod.HOURS24, StatisticsPeriod.MONTH)

@Composable
internal fun SwissAppHeader(title: String, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, status: String, onRecording: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, tonalElevation = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val compact = maxWidth / LocalDensity.current.fontScale < 540.dp
            val controls: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.clip(DesignTokens.PillShape).background(Color(0xFFF4F4F5)).selectableGroup()) {
                        swissPeriods.forEachIndexed { index, option ->
                            val selected = period == option
                            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(DesignTokens.PillShape)
                                .background(if (selected) DesignTokens.Ink else Color.Transparent)
                                .selectable(selected, role = Role.Tab, onClick = { onPeriod(option) })
                                .testTag("header-period-${option.name}").padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                Text(listOf("1H", "24H", "30D")[index], style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color.White else Muted)
                            }
                        }
                    }
                    Surface(onClick = onRecording, shape = DesignTokens.PillShape, color = Color(0xFFF4F4F5)) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RecordingDot(active)
                            Text(status, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (compact) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwissBrandLogo(Modifier.size(28.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                controls()
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SwissBrandLogo(Modifier.size(28.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                controls()
            }
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
    Box(Modifier.size(7.dp).clip(CircleShape).background((if (active) DesignTokens.Emerald else Color(0xFFA1A1AA)).copy(alpha = opacity)))
}
