package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.repeatedEvidenceShare
import com.emerge.app.analysis.reportNewFactMetric
import java.util.Locale

@Composable
internal fun CocoonCard(state: AnalysisState, period: StatisticsPeriod, visible: Boolean) {
    val report = state.report?.takeIf { !state.stale && it.period == period.label }
    val exposure = report?.periodExposure
    val primary = exposure?.primary
    val repeat = repeatedEvidenceShare(state, period)
    val newFacts = remember(report) { report?.reportNewFactMetric() }
    val fraction = primary?.share?.toFloat()
    val theme = fraction?.let { computeCocoonTheme(it * 100) }
    val accent = theme?.let { Color(it.colorArgb) } ?: DesignTokens.Emerald
    val unavailable = when {
        state.stale -> "待更新"
        report == null -> "暂无分析"
        else -> "观察不足"
    }
    WhiteCard(Modifier.testTag("cocoon-card")) {
        // 01 瑞士编号眉头 + 状态胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = DesignTokens.Ink,
                    contentColor = Color.White
                ) {
                    Text(
                        text = "01",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    text = "信息视野",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Surface(
                color = theme?.let { Color(it.backgroundArgb) } ?: Color(0xFFF0FDF4),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                    // The existing concentration share is not a trained cocoon probability.
                    Text(
                        text = if (primary == null) unavailable else if (primary.concentrated) "观点集中" else "分布均衡",
                        modifier = Modifier.testTag("cocoon-status"),
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 核心仪表盘（左侧圆环 + 右侧三项核心比率条）
        BoxWithConstraints {
            val stacked = maxWidth / LocalDensity.current.fontScale < 280.dp
            val ring: @Composable () -> Unit = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                        CocoonVisual(fraction, false, visible, Modifier.fillMaxSize())
                        Text(
                            text = fraction?.let { percentText(it) } ?: "—",
                            color = accent,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("cocoon-share")
                        )
                    }
                    Text(
                        text = "观点集中度 · ${period.label}",
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            val bars: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwissMetricBar(
                        label = "话题集中度 · ${period.label}",
                        fraction = exposure?.takeIf { it.topics.sufficient }?.topics?.shares?.values?.maxOrNull()?.toFloat(),
                        color = DesignTokens.Amber
                    )
                    SwissMetricBar(
                        label = "主要立场占比 · ${period.label}",
                        fraction = fraction,
                        color = DesignTokens.Blue
                    )
                    SwissMetricBar(
                        label = "同源重复率",
                        fraction = repeat?.fraction,
                        color = DesignTokens.Rose
                    )
                }
            }
            if (stacked) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ring()
                    bars()
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ring()
                    Box(Modifier.weight(1f)) { bars() }
                }
            }
        }

        // 重复依据引用提示槽
        repeat?.let { counts ->
            val countStyle = SpanStyle(
                color = Ink,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = DesignTokens.Background,
                shape = DesignTokens.InnerShape,
                border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.75f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(DesignTokens.PillShape)
                            .background(DesignTokens.Ink)
                    )
                    Text(
                        text = buildAnnotatedString {
                            withStyle(countStyle) { append("${counts.repeated}") }
                            append(" / ${counts.total} 条观点引用重复依据")
                        },
                        modifier = Modifier.weight(1f).testTag("cocoon-repeat-summary"),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 底部三列瑞士数据账本槽 (3-Column Ledger)
        Surface(
            shape = DesignTokens.InnerShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SwissCount(
                    label = "已记录内容",
                    value = report?.rawRecords?.toString() ?: "—",
                    modifier = Modifier.weight(1f),
                    emphasized = true
                )
                VerticalDivider(Modifier.fillMaxHeight(), color = DesignTokens.Border)
                SwissCount(
                    label = "新增事实",
                    value = newFacts?.display ?: "—",
                    modifier = Modifier.weight(1f).testTag("cocoon-new-facts")
                )
                VerticalDivider(Modifier.fillMaxHeight(), color = DesignTokens.Border)
                SwissCount(
                    label = "核心依据",
                    value = report?.claims?.flatMap { it.evidenceDetails }?.distinctBy { it.text }?.size?.toString() ?: "—",
                    modifier = Modifier.weight(1f),
                    emphasized = true
                )
            }
        }
    }
}

internal fun percentText(value: Float): String =
    String.format(Locale.CHINA, "%.0f%%", value.coerceIn(0f, 1f) * 100)

@Composable
internal fun SwissMetricBar(
    label: String,
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
            Text(
                text = fraction?.let(::percentText) ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (fraction != null) Ink else Muted
            )
        }
        if (fraction == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(DesignTokens.PillShape)
                    .background(Color(0xFFF1F1F3))
                    .clearAndSetSemantics { contentDescription = "$label 未分析" }
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(DesignTokens.PillShape),
                color = color,
                trackColor = Color(0xFFF1F1F3),
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
internal fun SwissCount(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) Ink else Muted
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
    }
}
