package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(stats: BrowseStatistics, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, statusLabel: String, appNames: Map<String, String>, onRecords: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, analysis: AnalysisState = AnalysisState(),
    rawEvents: List<BrowseEvent> = emptyList(), onAnalyze: (String) -> Unit = {},
    onExport: (CognitiveReport, Boolean) -> Unit = { _, _ -> },
    onPurposeChange: (String) -> Unit = {}, selectedAppCount: Int = 0,
    now: Long = System.currentTimeMillis(), audioContent: (@Composable () -> Unit)? = null) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val window = ObservationWindow.forPeriod(period, now, zone)
    val audioCount = rawEvents.count { it.sourceType.startsWith("audio_") && window.contains(it) }
    val browsingCount = (stats.total - audioCount).coerceAtLeast(0)
    val browsingUnknown = (stats.unknownDurationRecords - audioCount).coerceAtLeast(0)
    val date = if (period == StatisticsPeriod.DAY) today.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))
        else "${today.minusDays(6).format(DateTimeFormatter.ofPattern("M月d日"))} — ${today.format(DateTimeFormatter.ofPattern("M月d日"))}"
    var selectedHour by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    var detailHour by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    val hour = selectedHour ?: stats.trend.indices.maxByOrNull { stats.trend[it].count } ?: 0
    val hasReport = analysis.report?.period == period.label
    LazyColumn(modifier.fillMaxSize().testTag("overview-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "overview-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (period == StatisticsPeriod.DAY) "今日观察" else "七日观察",
                        style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.weight(1f))
                    MetadataText(date)
                }
                PeriodSelector(period, onPeriod)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSettings),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) Accent else Muted))
                    Text("文字采集 · $statusLabel · $selectedAppCount 个应用", color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("管理 ›", color = Accent, style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    CompactFact("${stats.total} 条", "已保存记录", "${stats.appCount} 个来源", Modifier.weight(1f))
                    val duration = when {
                        browsingCount == 0 -> "—"
                        browsingUnknown == browsingCount -> "未知"
                        else -> compactDuration(stats.observedDurationMillis)
                    }
                    CompactFact(duration, "预计停留时长", when {
                        browsingUnknown > 0 -> "$browsingUnknown 条未统计时长"
                        audioCount > 0 -> "音频不计入停留"
                        else -> "根据采集记录估算"
                    }, Modifier.weight(1f))
                }
            }
        }
        audioContent?.let { content -> item(key = "audio-entry") { content() } }
        if (!hasReport && stats.total > 0) item(key = "record-distribution") {
            WhiteCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (period == StatisticsPeriod.DAY) "24 小时接触分布" else "最近 7 天接触分布",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    MetadataText("按记录数")
                }
                if (period == StatisticsPeriod.DAY) {
                    HourlyRecordHeatmap(stats.trend.map { it.count }, hour, { selectedHour = it })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MetadataText("${hour.toString().padStart(2, '0')}:00 · ${stats.trend.getOrNull(hour)?.count ?: 0} 条",
                            Modifier.weight(1f))
                        TextButton(onClick = { detailHour = hour }, modifier = Modifier.testTag("overview-open-hour")) {
                            Text("回看原文 ›")
                        }
                    }
                } else {
                    val maximum = (stats.trend.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                    stats.trend.forEach { bucket ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetadataText(bucket.label, Modifier.width(44.dp))
                            LinearProgressIndicator(progress = { bucket.count.toFloat() / maximum },
                                modifier = Modifier.weight(1f).height(6.dp), color = Accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                            MetadataText("${bucket.count} 条")
                        }
                    }
                }
                ExpandableNote("记录分布说明", "颜色深浅只表示这个时段保留的记录数量，不能代表阅读投入。跨时段的记录会在每个覆盖时段出现，各时段数量不能相加作为总条数。空白不代表没有浏览。")
            }
        }
        if (rawEvents.isEmpty()) item(key = "start-guide") {
            WhiteCard {
                SectionTitle("从一条记录开始")
                Text("导入音频，或选择应用开启文字采集。", style = MaterialTheme.typography.bodyMedium, color = Muted)
                OutlinedButton(onClick = onSettings, shape = MaterialTheme.shapes.small) { Text("设置采集范围") }
                ExpandableNote("如何开始文字采集", "1. 选择要记录的应用。\n2. 在系统辅助功能中启用「破茧浏览采集」。\n3. 打开采集开关，浏览后返回查看记录。内容保存在本机，云端文字分析需主动开启；你可以随时暂停或删除。")
            }
        } else item(key = "analysis") {
            AnalysisCards(analysis, period, rawEvents, onAnalyze, onExport, onPurposeChange, appNames)
        }
        if (stats.total > 0) item(key = "source-distribution") {
            WhiteCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("记录来源", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "收起 −" else "${stats.appCount} 个来源 ＋") }
                }
                if (showSources) {
                    val shares = if (stats.apps.size <= 6) stats.apps else stats.apps.take(5) +
                        AppShare("__other__", stats.apps.drop(5).sumOf { it.count })
                    shares.forEach { share ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (share.packageName == "__other__") "其他应用" else appNames[share.packageName] ?: share.packageName,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                MetadataText("${share.count} 条 · ${String.format(Locale.CHINA, "%.0f%%", share.count * 100.0 / stats.total)}")
                            }
                            LinearProgressIndicator(progress = { share.count.toFloat() / stats.total },
                                modifier = Modifier.fillMaxWidth().height(5.dp), color = Accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                    MetadataText(if (audioCount > 0) "含 $audioCount 条音频转写，不计入停留。来源数量不等于独立信息源数量。"
                        else "应用数量不等于独立信息源数量。")
                }
                TextButton(onClick = onRecords, modifier = Modifier.align(Alignment.End)) { Text("管理本地记录 ›") }
            }
        }
    }
    detailHour?.let { selected ->
        val records = rawEvents.filter { event -> window.slices(event).any { it.hour == selected } }
            .sortedByDescending { it.timestamp }
        ModalBottomSheet(onDismissRequest = { detailHour = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface) {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f).testTag("overview-hour-records"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SectionTitle("${selected.toString().padStart(2, '0')}:00—${(selected + 1).toString().padStart(2, '0')}:00",
                        "${records.size} 条保存记录")
                }
                if (records.isEmpty()) item { MetadataText("这个时段没有保存的记录。") }
                items(records, key = { it.id }) { record ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetadataText(appNames[record.packageName] ?: record.packageName)
                        MetadataText(when {
                            record.sourceType.startsWith("audio_") -> "音频转写 · 不计入浏览停留"
                            !window.durationKnown(record) -> "未统计时长"
                            else -> "本时段预计停留：${formatDuration(window.slices(record).filter { it.hour == selected }.sumOf { it.durationMillis })}"
                        })
                        QuoteBlock(record.visibleText)
                    }
                }
                item { TextButton(onClick = { detailHour = null; onRecords() }) { Text("查看全部足迹 ›") } }
            }
        }
    }
}

@Composable
private fun PeriodSelector(period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp).selectableGroup()) {
        StatisticsPeriod.entries.forEach { option ->
            val selected = period == option
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .background(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                .selectable(selected = selected, role = Role.Tab, onClick = { onPeriod(option) })
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(option.label, color = if (selected) Accent else Muted, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun CompactFact(value: String, label: String, note: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetadataText(label)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Ink)
        MetadataText(note)
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
