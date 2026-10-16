package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent

@Composable
fun OverviewScreen(stats: BrowseStatistics, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, statusLabel: String, appNames: Map<String, String>, onRecords: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, analysis: AnalysisState = AnalysisState(),
    rawEvents: List<BrowseEvent> = emptyList(), onAnalyze: (String) -> Unit = {},
    onExport: (CognitiveReport, Boolean) -> Unit = { _, _ -> },
    onPurposeChange: (String) -> Unit = {}, selectedAppCount: Int = 0,
    now: Long = System.currentTimeMillis(), audioContent: (@Composable () -> Unit)? = null,
    semantics: List<EventSemantics> = emptyList(), onViewpointScrolling: (Boolean) -> Unit = {},
    onPreviewReport: (CognitiveReport) -> Unit = {},
    onSupplement: (com.emerge.app.monitoring.SupplementRequest) -> Unit = {},
    onKeyword: (String) -> Unit = {}, onTopic: (String) -> Unit = {}, onReports: () -> Unit = {}) {
    val report = analysis.report?.takeIf { !analysis.stale && it.period == period.label }
    LazyColumn(modifier.fillMaxSize().testTag("overview-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "cocoon") { CocoonCard(analysis, period, visible = true) }
        item(key = "evidence-trend") { SwissEvidenceTrend(rawEvents, semantics, now) }
        item(key = "source-distribution") { SwissAppDuration(stats, appNames) }
        item(key = "emotion") { SwissEmotionCard(report) }
        item(key = "recent-content") { RecentContentCloud(rawEvents, period, now, semantics, appNames, onWordSelected = onKeyword) }
        item(key = "topics") { SwissTopicList(report, rawEvents, semantics, period, now, onTopic) }
        item(key = "report-entry") {
            PojianPrimaryButton("体检报告", onReports)
        }
        if (rawEvents.isEmpty()) item(key = "start-guide") {
            PojianSecondaryButton(onSettings, modifier = Modifier.fillMaxWidth()) { Text("设置记录范围") }
        }
        analysis.error?.let { error -> item(key = "analysis-error") {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            PojianTextButton(onClick = { onAnalyze(analysis.purpose) }) { Text("重试") }
        } }
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
