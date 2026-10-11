package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.repeatedEvidenceShare
import java.util.Locale

@Composable
internal fun CocoonCard(state: AnalysisState, period: StatisticsPeriod, visible: Boolean) {
    var playing by rememberSaveable { mutableStateOf(true) }
    val share = remember(state.report, state.stale, period) { repeatedEvidenceShare(state, period) }
    val motionEnabled = rememberSystemMotionEnabled()
    val unavailable = when {
        state.stale -> "待更新"
        state.report == null || state.report.period != period.label -> "暂无分析"
        else -> "暂无可比依据"
    }
    WhiteCard(Modifier.testTag("cocoon-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("信息茧房", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (share != null && motionEnabled) PojianTextButton(onClick = { playing = !playing }, modifier = Modifier.testTag("cocoon-play")) {
                Text(if (playing) "暂停" else "播放")
            }
        }
        val evidence: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("重复依据", style = MaterialTheme.typography.bodyMedium)
                Text(share?.let { "${it.repeated}/${it.total} 个观点" } ?: unavailable,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val percentage: @Composable () -> Unit = {
            Text(share?.let { String.format(Locale.CHINA, "%.1f%%", it.fraction * 100) } ?: "—",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("cocoon-share"))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth / LocalDensity.current.fontScale >= 240.dp
            val visual: @Composable (Modifier) -> Unit = { modifier ->
                CocoonVisual(share?.fraction, MaterialTheme.colorScheme.background.luminance() < .5f,
                    playing && visible && share != null, modifier.height(144.dp))
            }
            if (sideBySide) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    visual(Modifier.weight(1f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        percentage()
                        evidence()
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visual(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { evidence() }
                        percentage()
                    }
                }
            }
        }
    }
}
