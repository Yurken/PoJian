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
    val exposure = remember(state.report, state.stale, period) {
        state.report?.takeIf { !state.stale && it.period == period.label }?.exposure
    }
    val share = exposure?.primary
    val evidenceShare = remember(state.report, state.stale, period) { repeatedEvidenceShare(state, period) }
    val motionEnabled = rememberSystemMotionEnabled()
    val unavailable = when {
        state.stale -> "待更新"
        state.report == null || state.report.period != period.label -> "暂无分析"
        else -> "观察不足"
    }
    WhiteCard(Modifier.testTag("cocoon-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("信息茧房 · 近7天", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (share != null && motionEnabled) PojianTextButton(onClick = { playing = !playing }, modifier = Modifier.testTag("cocoon-play")) {
                Text(if (playing) "暂停" else "播放")
            }
        }
        val evidence: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("观点集中度", style = MaterialTheme.typography.bodyMedium)
                if (share != null) Text("${share.subject} · ${share.dimension}", style = MaterialTheme.typography.bodySmall)
                Text(share?.let { "${it.distribution.contacts}次接触 · ${it.distribution.days}天" } ?: unavailable,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val percentage: @Composable () -> Unit = {
            Text(share?.let { String.format(Locale.CHINA, "%.1f%%", it.share * 100) } ?: "—",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("cocoon-share"))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth / LocalDensity.current.fontScale >= 240.dp
            val visual: @Composable (Modifier) -> Unit = { modifier ->
                CocoonVisual(share?.share?.toFloat(), MaterialTheme.colorScheme.background.luminance() < .5f,
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
        if (share != null) {
            ExposureMetric("主要立场", share.stance)
            ExposureMetric("较前7天", share.shareChange?.let {
                String.format(Locale.CHINA, "%+.1f 个百分点", it * 100)
            } ?: "暂无可比历史")
            ExposureMetric("同向应用", "${share.alignedApps}个")
        }
        exposure?.takeIf { it.topics.sufficient }?.let { summary ->
            summary.leadingTopic?.let { topic ->
                ExposureMetric(topic, String.format(Locale.CHINA, "%.1f%%", summary.topics.shares.getValue(topic) * 100))
                summary.topicShareChange?.let { change ->
                    ExposureMetric("主题占比较前7天", String.format(Locale.CHINA, "%+.1f 个百分点", change * 100))
                }
            }
        }
        ExposureMetric("重复依据 · ${period.label}", evidenceShare?.let {
            String.format(Locale.CHINA, "%.1f%%", it.fraction * 100)
        } ?: "—")
    }
}

@Composable
private fun ExposureMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
