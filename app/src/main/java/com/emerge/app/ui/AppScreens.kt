package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun OverviewScreen(stats: BrowseStatistics, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, statusLabel: String, appNames: Map<String, String>, onRecords: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, analysis: AnalysisState = AnalysisState(),
    rawEvents: List<BrowseEvent> = emptyList(), onAnalyze: (String) -> Unit = {},
    onExport: (CognitiveReport, Boolean) -> Unit = { _, _ -> },
    onPurposeChange: (String) -> Unit = {}, selectedAppCount: Int = 0,
    now: Long = System.currentTimeMillis()) {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val date = if (period == StatisticsPeriod.DAY)
        today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA))
    else "${today.minusDays(6).format(DateTimeFormatter.ofPattern("M月d日"))} — ${today.format(DateTimeFormatter.ofPattern("M月d日"))}"
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Text(if (period == StatisticsPeriod.DAY) "今日观察" else "七日观察",
                style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif, color = Ink)
            Text(date, color = Muted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            PeriodSelector(period, onPeriod)
            Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) Accent else Muted))
                Text("$statusLabel · 已选 $selectedAppCount 个应用", color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text("管理范围 ›", color = Accent, style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.Top) {
                FactValue(stats.total.toString(), "已保存记录", "来自 ${stats.appCount} 个应用", Modifier.weight(1f))
                val duration = when {
                    stats.total == 0 -> "—"
                    stats.unknownDurationRecords == stats.total -> "未知"
                    else -> compactDuration(stats.observedDurationMillis)
                }
                FactValue(duration, "预计停留时长", if (stats.unknownDurationRecords > 0)
                    "${stats.unknownDurationRecords} 条未统计时长" else "根据采集记录估算", Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (rawEvents.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("从一小段浏览开始", style = MaterialTheme.typography.titleLarge)
                Text("先选择想记录的应用并开启采集。浏览一段时间后回到这里，看看自己都接触了什么内容。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onSettings, shape = RoundedCornerShape(8.dp)) { Text("设置采集范围") }
                Text("所有内容只保存在这台设备上，你可以随时暂停或删除。", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        } else item {
            AnalysisCards(analysis, period, rawEvents, onAnalyze, onExport, onPurposeChange, appNames)
        }
        if (stats.total > 0) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("来源应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRecords) { Text("全部记录 ›") }
                }
                val shares = if (stats.apps.size <= 6) stats.apps else stats.apps.take(5) +
                    AppShare("__other__", stats.apps.drop(5).sumOf { it.count })
                val colors = ChartColors
                shares.forEachIndexed { index, share ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (share.packageName == "__other__") "其他应用" else appNames[share.packageName] ?: share.packageName,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            MetadataText("${share.count} 条 · ${String.format(Locale.CHINA, "%.0f%%", share.count * 100.0 / stats.total)}")
                        }
                        LinearProgressIndicator(progress = { share.count.toFloat() / stats.total },
                            modifier = Modifier.fillMaxWidth().height(4.dp), color = colors[index % colors.size],
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                Text("共 ${stats.total} 条记录。注意：应用数量不等于信息来源数量。", color = Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PeriodSelector(period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp).selectableGroup()) {
        StatisticsPeriod.entries.forEach { option ->
            val selected = period == option
            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = { onPeriod(option) })
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(option.label, color = if (selected) Accent else Muted,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun FactValue(value: String, label: String, note: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(note, style = MaterialTheme.typography.bodySmall, color = Muted)
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
