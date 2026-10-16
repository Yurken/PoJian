package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onSupplement: (com.emerge.app.monitoring.SupplementRequest) -> Unit = {}) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val browsingCount = stats.visitCount
    val browsingUnknown = stats.unknownDurationRecords
    val date = when {
        period == StatisticsPeriod.DAY -> today.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
        period.rollingHours > 0 -> "截至 " + Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        else -> "${today.minusDays(period.calendarDays - 1L).format(DateTimeFormatter.ofPattern("M月d日"))} — ${today.format(DateTimeFormatter.ofPattern("M月d日"))}"
    }
    var showSources by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var workScrolling by remember { mutableStateOf(false) }
    val scrollingCallback by rememberUpdatedState(onViewpointScrolling)
    LaunchedEffect(listState) {
        snapshotFlow { workScrolling || (listState.isScrollInProgress &&
            listState.layoutInfo.visibleItemsInfo.any { it.key == "viewpoint-content" }) }
            .distinctUntilChanged().collectLatest { scrolling ->
                if (!scrolling) delay(650)
                scrollingCallback(scrolling)
            }
    }
    DisposableEffect(Unit) { onDispose { scrollingCallback(false) } }
    val timeline = if (rawEvents.isNotEmpty()) rememberViewpointTimelineState(rawEvents, period, now,
        analysis.report?.takeUnless { analysis.stale }, semantics) else null
    val plotHeight = (LocalConfiguration.current.screenHeightDp * .22f).coerceIn(64f, 156f).dp
    val cocoonVisible by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.any { it.key == "cocoon" }
    } }
    LazyColumn(modifier.fillMaxSize().testTag("overview-list"),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "overview-header") {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when(period) { StatisticsPeriod.DAY -> "今日观察"; StatisticsPeriod.HOURS24 -> "24 小时观察"; StatisticsPeriod.WEEK -> "七日观察"; StatisticsPeriod.HOUR -> "小时观察"; StatisticsPeriod.MONTH -> "30 天观察" },
                        style = MaterialTheme.typography.titleLarge, color = Ink)
                    MetadataText(date)
                    HelpButton("浏览统计", "作品按采集记录估算，重复打开只增加观看次数。当前有 ${stats.unidentifiedWorks} 个作品未识别。\n\n停留时长按可见区间估算，${stats.unknownDurationRecords} 条时长未统计；音频与画面文字不单独计入作品数和停留。")
                }
                PeriodSelector(period, onPeriod)
            }
        }
        if (rawEvents.isEmpty()) item(key = "start-guide") {
            WhiteCard {
                SectionTitle("暂无记录", help = "选择应用，在系统辅助功能中启用「破茧浏览采集」，再打开采集开关。也可从采集工具导入音频。")
                PojianButton(onClick = onSettings, shape = MaterialTheme.shapes.small) { Text("设置采集范围") }
            }
        }
        item(key = "overview-statistics") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSettings),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) Accent else Muted))
                    Text("$statusLabel · $selectedAppCount 个应用", color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("设置 ›", color = Accent, style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    CompactFact("${stats.total} 个", "浏览作品", "${stats.visitCount} 次观看", Modifier.weight(1f))
                    val duration = when {
                        browsingCount == 0 -> "—"
                        browsingUnknown == browsingCount -> "未知"
                        else -> compactDuration(stats.observedDurationMillis)
                    }
                    CompactFact(duration, "停留时长", modifier = Modifier.weight(1f))
                }
            }
        }
        item(key = "cocoon") { CocoonCard(analysis, period, visible = cocoonVisible) }
        item(key = "recent-content") { RecentContentCloud(rawEvents, period, now, semantics, appNames) }
        audioContent?.let { content -> item(key = "audio-entry") { content() } }
        timeline?.let { viewpointTimeline(it, appNames, plotHeight, onWorkScrolling = { workScrolling = it }) }
        if (rawEvents.isNotEmpty()) item(key = "analysis") {
            AnalysisCards(analysis, period, rawEvents, onAnalyze, onExport, onPurposeChange, appNames,
                onPreviewReport = onPreviewReport, onSupplement = onSupplement)
        }
        if (stats.total > 0) item(key = "source-distribution") {
            WhiteCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("平台分布", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    PojianTextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "收起" else "${stats.appCount} 个平台") }
                }
                run {
                    val shares = if (showSources) stats.apps else stats.apps.take(3)
                    shares.forEach { share ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SourceAppText(share.packageName,
                                    if (share.packageName == "__other__") "其他应用" else appNames[share.packageName] ?: share.packageName, color = Ink,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MetadataText("${share.count} 个作品")
                            }
                            LinearProgressIndicator(progress = { share.count.toFloat() / stats.total },
                                modifier = Modifier.fillMaxWidth().height(5.dp), color = Accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
                PojianTextButton(onClick = onRecords, modifier = Modifier.align(Alignment.End)) { Text("全部记录 ›") }
            }
        }
    }
}

@Composable
private fun PeriodSelector(period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit) {
    val options = listOf(StatisticsPeriod.HOUR, StatisticsPeriod.DAY, StatisticsPeriod.HOURS24,
        StatisticsPeriod.WEEK, StatisticsPeriod.MONTH)
    BoxWithConstraints {
        val tabsWidth = maxOf(maxWidth, 400.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            PojianSegmentedTabs(options.map { it.shortLabel }, selectedIndex = options.indexOf(period),
                onSelect = { onPeriod(options[it]) }, modifier = Modifier.width(tabsWidth))
        }
    }
}

@Composable
private fun CompactFact(value: String, label: String, note: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetadataText(label)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Ink)
        note?.let { MetadataText(it) }
    }
}

private fun compactDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3600 -> "${seconds / 60} 分"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
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
