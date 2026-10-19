package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ViewpointTimeline
import com.emerge.app.data.BrowseEvent
import java.time.ZoneId

@Composable
fun OverviewScreen(
    stats: BrowseStatistics,
    period: StatisticsPeriod,
    onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean,
    statusLabel: String,
    appNames: Map<String, String>,
    onRecords: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    analysis: AnalysisState = AnalysisState(),
    rawEvents: List<BrowseEvent> = emptyList(),
    onAnalyze: (String) -> Unit = {},
    onExport: (CognitiveReport, Boolean) -> Unit = { _, _ -> },
    onPurposeChange: (String) -> Unit = {},
    selectedAppCount: Int = 0,
    now: Long = System.currentTimeMillis(),
    audioContent: (@Composable () -> Unit)? = null,
    semantics: List<EventSemantics> = emptyList(),
    onViewpointScrolling: (Boolean) -> Unit = {},
    onPreviewReport: (CognitiveReport) -> Unit = {},
    onSupplement: (com.emerge.app.monitoring.SupplementRequest) -> Unit = {},
    onKeyword: (String) -> Unit = {},
    onTopic: (String) -> Unit = {},
    onReports: () -> Unit = {}
) {
    val report = analysis.report?.takeIf { !analysis.stale && it.period == period.label }
    val zone = ZoneId.systemDefault()
    // Retain the topic snapshot across lazy card disposal; build it only in the background producer.
    val topicViewpoints = remember(rawEvents, semantics, period, now, report?.claims, report?.period, zone) {
        lazy { ViewpointTimeline.build(rawEvents, period, now, zone, report, semantics) }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("overview-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 01 / 信息视野
        item(key = "cocoon") {
            CocoonCard(analysis, period, visible = true)
        }

        // 02 / 内容情绪时间线、时段详情与情绪统计。
        item(key = "emotion-timeline") {
            SwissEmotionTimeline(rawEvents, semantics, now, appNames) {
                SwissEmotionSummary(report)
            }
        }

        // 03 / 应用时长分布
        item(key = "source-distribution") {
            SwissAppDuration(stats, appNames)
        }

        // 04 / 近期热词
        item(key = "recent-content") {
            RecentContentCloud(
                events = rawEvents,
                period = period,
                now = now,
                semantics = semantics,
                appNames = appNames,
                onWordSelected = onKeyword
            )
        }

        // 05 / 关注话题
        item(key = "topics") {
            SwissTopicTimeline(rawEvents, semantics, period, now, appNames, report, onTopic, topicViewpoints)
        }

        // 底部报告与采集引导区
        item(key = "report-entry") {
            SwissCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "浏览报告与数据导出",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink
                        )
                        Text(
                            text = "按小时 / 天 / 月生成，可导出 PDF 与 JSON",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted
                        )
                    }
                    Text(
                        text = "REPORT",
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }
                PojianPrimaryButton("浏览报告", onReports)
                if (rawEvents.isEmpty()) {
                    PojianSecondaryButton(
                        onClick = onSettings,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, DesignTokens.Border)
                    ) {
                        Text("设置记录范围", modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Muted
                        )
                    }
                }
            }
        }

        analysis.error?.let { error ->
            item(key = "analysis-error") {
                Surface(
                    shape = DesignTokens.CardShape,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        PojianTextButton(onClick = { onAnalyze(analysis.purpose) }) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}

internal fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
    }
}
