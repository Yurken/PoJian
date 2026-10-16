package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.repeatedEvidenceShare
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
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
    val heroColors = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF2DD4BF), onSurface = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFFCBD5E1))
    MaterialTheme(colorScheme = heroColors) {
        CompositionLocalProvider(LocalContentColor provides heroColors.onSurface) {
            Column(Modifier.fillMaxWidth().testTag("cocoon-card").clip(DesignTokens.CardShape)
                .background(DesignTokens.CocoonHeroBrush).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("信息视野 · ${period.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("cocoon-share"))
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val fontScale = LocalDensity.current.fontScale
                    val sideBySide = maxWidth / fontScale >= 260.dp
                    val ringSize = minOf(maxWidth, 128.dp * fontScale)
                    val visual: @Composable (Modifier) -> Unit = { modifier ->
                        Box(modifier.height(ringSize), contentAlignment = Alignment.Center) {
                            CocoonVisual(share?.share?.toFloat(), true,
                                playing && visible && share != null, Modifier.fillMaxSize())
                            percentage()
                        }
                    }
                    if (sideBySide) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { evidence() }
                            visual(Modifier.width(ringSize))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            visual(Modifier.fillMaxWidth())
                            evidence()
                        }
                    }
                }
                val metrics = buildList {
                    if (share != null) {
                        add("主要立场" to share.stance)
                        add("较前7天" to (share.shareChange?.let {
                            String.format(Locale.CHINA, "%+.1f 个百分点", it * 100)
                        } ?: "暂无可比历史"))
                        add("同向应用" to "${share.alignedApps}个")
                    }
                    exposure?.takeIf { it.topics.sufficient }?.let { summary ->
                        summary.leadingTopic?.let { topic ->
                            add(topic to String.format(Locale.CHINA, "%.1f%%", summary.topics.shares.getValue(topic) * 100))
                            summary.topicShareChange?.let { change ->
                                add("主题占比较前7天" to String.format(Locale.CHINA, "%+.1f 个百分点", change * 100))
                            }
                        }
                    }
                    add("重复依据 · ${period.label}" to (evidenceShare?.let {
                        String.format(Locale.CHINA, "%.1f%%", it.fraction * 100)
                    } ?: "—"))
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = when {
                        maxWidth / LocalDensity.current.fontScale >= 330.dp -> 3
                        maxWidth / LocalDensity.current.fontScale >= 240.dp -> 2
                        else -> 1
                    }
                    val tileWidth = (maxWidth - 8.dp * (columns - 1)) / columns
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        metrics.forEach { (label, value) -> ExposureMetric(label, value, Modifier.width(tileWidth)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExposureMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(DesignTokens.InnerShape)
        .background(Color.White.copy(alpha = .07f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
