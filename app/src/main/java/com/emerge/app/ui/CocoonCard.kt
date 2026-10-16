package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.repeatedEvidenceShare
import java.util.Locale

@Composable
internal fun CocoonCard(state: AnalysisState, period: StatisticsPeriod, visible: Boolean) {
    val report = state.report?.takeIf { !state.stale && it.period == period.label }
    val exposure = report?.exposure
    val primary = exposure?.primary
    val repeat = repeatedEvidenceShare(state, period)
    val fraction = primary?.share?.toFloat()
    val theme = fraction?.let { computeCocoonTheme(it * 100) }
    val accent = theme?.let { Color(it.colorArgb) } ?: DesignTokens.Emerald
    val unavailable = when { state.stale -> "待更新"; report == null -> "暂无分析"; else -> "观察不足" }
    WhiteCard(Modifier.testTag("cocoon-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("信息视野", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(color = theme?.let { Color(it.backgroundArgb) } ?: Color(0xFFF0FDF4), shape = DesignTokens.PillShape) {
                // The existing concentration share is not a trained cocoon probability.
                Text(if (primary == null) unavailable else if (primary.concentrated) "观点集中" else "分布均衡",
                    Modifier.testTag("cocoon-status").padding(horizontal = 10.dp, vertical = 5.dp), color = accent,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        BoxWithConstraints {
            val stacked = maxWidth / LocalDensity.current.fontScale < 280.dp
            val ring: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                        CocoonVisual(fraction, false, visible, Modifier.fillMaxSize())
                        Text(fraction?.let { percentText(it) } ?: "—", color = accent,
                            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("cocoon-share"))
                    }
                    Text("观点集中度 · 近7天", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            val bars: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SwissMetricBar("话题集中度 · 近7天", exposure?.takeIf { it.topics.sufficient }?.topics?.shares?.values?.maxOrNull()?.toFloat(), DesignTokens.Amber)
                    SwissMetricBar("主要立场占比 · 近7天", fraction, DesignTokens.Blue)
                    SwissMetricBar("同源重复率", repeat?.fraction, DesignTokens.Rose)
                }
            }
            if (stacked) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) { ring(); bars() }
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ring(); Box(Modifier.weight(1f)) { bars() }
            }
        }
        repeat?.let {
            Surface(color = Color(0xFFF8FAFC), shape = DesignTokens.InnerShape) {
                Text("${it.repeated} / ${it.total} 条观点引用重复论据", Modifier.fillMaxWidth().padding(12.dp),
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(color = DesignTokens.Border)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwissCount("已记录内容", report?.rawRecords?.toString() ?: "—", Modifier.weight(1f))
            SwissCount("新增独立事实", "未核实", Modifier.weight(1f))
            SwissCount("提炼核心论据", report?.claims?.flatMap { it.evidenceDetails }?.distinctBy { it.text }?.size?.toString() ?: "—", Modifier.weight(1f))
        }
    }
}

internal fun percentText(value: Float): String = String.format(Locale.CHINA, "%.0f%%", value.coerceIn(0f, 1f) * 100)

@Composable
internal fun SwissMetricBar(label: String, fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(fraction?.let(::percentText) ?: "—", style = MaterialTheme.typography.labelSmall)
        }
        if (fraction == null) Box(Modifier.fillMaxWidth().height(5.dp).clip(DesignTokens.PillShape)
            .background(Color(0xFFF1F1F3)).clearAndSetSemantics { contentDescription = "$label 未分析" })
        else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(DesignTokens.PillShape),
            color = color, trackColor = Color(0xFFF1F1F3), drawStopIndicator = {})
    }
}

@Composable
internal fun SwissCount(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
