package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.data.BrowseEvent
import com.emerge.app.R
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.analysis.AnalysisState
import com.emerge.app.analysis.CognitiveReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

val Accent = Color(0xFF14584E)
val PageBackground = Color(0xFFF7F8F4)
val Ink = Color(0xFF20342E)
internal val Muted = Color(0xFF64736C)
internal val Blue = Color(0xFF236F87)
internal val Amber = Color(0xFFD69F45)
internal val ChartColors = listOf(Accent, Color(0xFF7CAA91), Amber,
    Color(0xFF8298B1), Color(0xFFAF8F88), Color(0xFFAEB9B0))

@Composable
internal fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
}

@Composable
fun OverviewScreen(stats: BrowseStatistics, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, statusLabel: String, appNames: Map<String, String>, onRecords: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, analysis: AnalysisState = AnalysisState(),
    rawEvents: List<BrowseEvent> = emptyList(), onAnalyze: (String) -> Unit = {},
    onExport: (CognitiveReport, Boolean) -> Unit = { _, _ -> },
    onPurposeChange: (String) -> Unit = {}, selectedAppCount: Int = 0) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(stringResource(R.string.brand_name), color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.brand_tagline), color = Muted, modifier = Modifier.padding(top = 8.dp), lineHeight = 24.sp)
            Surface(color = if (active) Color(0xFFE6F1E9) else Color(0xFFEBEEE8),
                shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(top = 16.dp).clickable(onClick = onSettings)) {
                Text("${if (active) "●" else "○"} $statusLabel · 已选择 $selectedAppCount 个应用 ›", color = Accent,
                    fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        if (rawEvents.isEmpty()) item {
            WhiteCard {
                SectionTitle("从一次自愿的观察开始", "你决定记录哪些应用，也可以随时暂停或删除。")
                Text("先选择应用，再授权「破茧浏览采集」并开启采集。阅读后回到这里，核查自己的信息足迹。", color = Muted, lineHeight = 23.sp)
                Button(onClick = onSettings) { Text("设置我的采集范围") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0xFFE9EDE5)).padding(5.dp)) {
                StatisticsPeriod.entries.forEach { option ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                        .background(if (period == option) Color.White else Color.Transparent)
                        .clickable { onPeriod(option) }.padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(option.label, color = if (period == option) Accent else Muted,
                            fontWeight = if (period == option) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        item { AnalysisCards(analysis, period, rawEvents, onAnalyze, onExport, onPurposeChange) }
        item {
            WhiteCard {
                SectionTitle("我的信息来自哪些应用", "${stats.total} 条已保存记录 · 分母为记录条数")
                val shares = if (stats.apps.size <= 6) stats.apps else stats.apps.take(5) + AppShare("__other__", stats.apps.drop(5).sumOf { it.count })
                if (shares.isNotEmpty()) DonutChart(shares, stats.total)
                shares.forEachIndexed { index, share ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(ChartColors[index]))
                        Column(Modifier.weight(1f).padding(start = 9.dp, end = 8.dp)) {
                            Text(if (share.packageName == "__other__") "其他应用" else appNames[share.packageName] ?: share.packageName,
                                fontSize = 14.sp, overflow = TextOverflow.Ellipsis)
                            Text("${share.count} 条", color = Muted, fontSize = 12.sp)
                        }
                        Text(String.format(Locale.CHINA, "%.1f%%", share.count * 100.0 / max(stats.total, 1)), color = Muted, fontSize = 13.sp)
                    }
                }
                if (shares.isEmpty()) Text("当前区间暂无记录。选择应用并采集后，这里会展示来源分布。", color = Muted)
                Text("应用来源不能代表独立作者或材料源头。多个应用也可能在转述同一材料。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                TextButton(onClick = onRecords) { Text("回查全部本地记录 ›") }
            }
        }
    }
}

@Composable
internal fun DonutChart(shares: List<AppShare>, total: Int, label: String = "条采集记录") {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp).semantics { contentDescription = "$label 分布环图，共 $total，占比见下方列表" }) {
            val stroke = 25.dp.toPx()
            val inset = stroke / 2
            val circle = Size(size.width - stroke, size.height - stroke)
            drawArc(PageBackground, 0f, 360f, false, Offset(inset, inset), circle, style = Stroke(stroke))
            var angle = -90f
            shares.forEachIndexed { index, share ->
                val sweep = share.count * 360f / max(total, 1)
                drawArc(ChartColors[index % ChartColors.size], angle, sweep, false, Offset(inset, inset), circle, style = Stroke(stroke))
                angle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(total.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
fun RecordsScreen(events: List<BrowseEvent>, count: Int, appNames: Map<String, String>, modifier: Modifier = Modifier,
    onDelete: (BrowseEvent) -> Unit = {}, onSettings: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(events, query, appNames) {
        val search = query.trim()
        if (search.isEmpty()) events else events.filter {
            it.visibleText.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) ||
                appNames[it.packageName].orEmpty().contains(search, ignoreCase = true)
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("信息足迹", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("本地保存 $count 条 · ${if (query.isBlank()) "按时间回看" else "匹配 ${filtered.size} 条"}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索原文或应用") }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
        }
        if (filtered.isEmpty()) item {
            WhiteCard {
                SectionTitle(if (query.isBlank()) "等待第一条信息足迹" else "没有匹配的记录")
                Text(if (query.isBlank()) "先选择要记录的应用，再授权并开启采集。这里仅展示实际保存的可见文字。" else "换一个关键词，或清除搜索查看全部记录。", color = Muted)
                if (query.isBlank()) Button(onClick = onSettings) { Text("去设置采集") }
                else TextButton(onClick = { query = "" }) { Text("查看全部记录") }
            }
        }
        items(filtered, key = { it.id }) { event ->
            var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
            WhiteCard {
                Text(appNames[event.packageName] ?: event.packageName, fontWeight = FontWeight.SemiBold)
                Text(SimpleDateFormat("MM/dd HH:mm:ss", Locale.CHINA).format(Date(event.timestamp)), color = Muted, fontSize = 12.sp)
                Text(if (event.sampleCount > 0) "估计可见停留 ${formatDuration(event.durationMillis)} · ${event.sampleCount} 次检查"
                    else "旧版快照 · 停留时长未知", color = Muted, fontSize = 12.sp)
                Text(event.visibleText, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, lineHeight = 23.sp)
                if (expanded) Text("事件 #${event.id} · ${event.packageName}", color = Muted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "查看全文 ›") }
                    TextButton(onClick = { onDelete(event) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(permission: Boolean, enabled: Boolean, count: Int, onCapture: (Boolean) -> Unit,
    onOpenSettings: () -> Unit, onClear: () -> Unit, message: String?, modifier: Modifier = Modifier,
    diagnostics: CaptureStatus = CaptureStatus(), selectedApps: List<CaptureApp> = emptyList(), onChooseApps: () -> Unit = {}) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("我的破茧", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("你的数据，由你掌握", color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
        item {
            WhiteCard {
                SectionTitle("先决定观察范围", "仅采集你主动选中的应用；默认不采集任何应用。")
                Text(if (selectedApps.isEmpty()) "尚未选择应用" else selectedApps.joinToString("、") { it.label }, color = Muted, lineHeight = 22.sp)
                OutlinedButton(onClick = onChooseApps) { Text("选择应用与管理记录（${selectedApps.size}）") }
                Text("改变范围会暂停采集，历史记录仍可回看。可在应用列表中单独删除某个应用的历史。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("授权与采集")
                Text("破茧通过辅助功能读取所选应用当前窗口暴露的可见文字、应用名称与时间，用于在本机生成信息环境报告。开启前请确认愿意记录这些内容。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                OutlinedButton(onClick = onOpenSettings) { Text(if (permission) "查看系统辅助功能授权" else "前往系统授权") }
                Text("在系统中启用「破茧浏览采集」，返回后再打开下方开关。", color = Muted, fontSize = 12.sp)
                HorizontalDivider(color = PageBackground)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("允许采集", fontWeight = FontWeight.Medium)
                        Text(if (selectedApps.isEmpty()) "请先选择至少一个应用" else if (enabled && diagnostics.connected) "正在观察所选应用" else if (enabled && permission) "系统已授权，等待服务连接" else if (enabled) "等待系统辅助功能授权" else "已暂停，可随时重新开启", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = enabled, onCheckedChange = onCapture, enabled = selectedApps.isNotEmpty())
                }
                message?.let { Text(it, color = Accent, fontSize = 13.sp) }
            }
        }
        item {
            WhiteCard {
                SectionTitle("采集状态", "记录缺失时，可从这里检查授权与服务连接。")
                Text("系统授权：${if (permission) "已启用" else "未检测到"}；服务连接：${if (diagnostics.connected) "已连接" else "未连接"}", fontSize = 13.sp)
                Text("本次进程收到事件 ${diagnostics.eventsReceived} 次", color = Muted, fontSize = 12.sp)
                Text("最近应用：${diagnostics.lastPackage}", fontSize = 12.sp)
                Text("最近检查 ${diagnosticTime(diagnostics.lastCheck)} · 最近保存 ${diagnosticTime(diagnostics.lastSaved)}", fontSize = 12.sp)
                Text(diagnostics.result, color = Accent, fontSize = 13.sp)
                Text("若已授权但未连接，请关闭再开启「破茧浏览采集」服务，返回破茧检查。部分应用不会暴露正文，视频和音频也不在当前观察范围。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("本地数据")
                Text("$count 条已保存记录", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("仅保存在这台设备，不上传。最多保留 5000 条，超出后删除最早记录。浏览目的会保留；分析报告在打开应用后重新生成。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                OutlinedButton(onClick = onClear, enabled = count > 0, shape = RoundedCornerShape(14.dp)) { Text("暂停并清空全部记录") }
                Text("删除记录会同步更新本机分析。已导出的文件由你自行管理。", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("隐私与观察边界")
                Text("采集会跳过密码和可编辑输入框，过滤本应用、系统设置及部分键盘。可见正文仍可能包含个人信息，请按需开启，结束后暂停。数据库尚未额外加密。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                Text("可见停留不代表真实阅读、理解或认同。未记录的内容保留为未知，内容中的焦虑表达也不代表你的心理状态。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("关于破茧 Emerge", "个人信息环境自查 · 离线可解释原型")
                Text("回顾接触的观点、比较依据变化，再自主决定是否补充材料。当前使用文字规则，尚未接入多模态、语义模型、时序图学习或可信执行环境。每项候选都需要回到原文核查。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
fun AppPickerDialog(apps: List<CaptureApp>, selected: Set<String>, loading: Boolean,
    recordCounts: Map<String, Int>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit,
    onDeletePackage: (CaptureApp) -> Unit) {
    var draft by remember(selected) { mutableStateOf(selected.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择采集应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已选 ${draft.size} 个。保存新范围后采集会暂停，需重新开启。", color = Muted, fontSize = 12.sp)
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("搜索应用") })
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (filtered.isEmpty() && !loading) item { Text("没有匹配的应用。可启动应用、已选应用及有历史记录的应用会出现在这里。", color = Muted) }
                    items(filtered, key = { it.packageName }) { application ->
                        Column {
                            Row(Modifier.fillMaxWidth().clickable {
                                draft = if (application.packageName in draft) draft - application.packageName else draft + application.packageName
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = application.packageName in draft, onCheckedChange = { checked ->
                                    draft = if (checked) draft + application.packageName else draft - application.packageName
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(application.label, fontWeight = FontWeight.Medium)
                                    Text(application.packageName, fontSize = 10.sp, color = Muted)
                                }
                            }
                            val records = recordCounts[application.packageName] ?: 0
                            if (records > 0) TextButton(onClick = { onDeletePackage(application) }) {
                                Text("删除此应用的 $records 条记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                            HorizontalDivider(color = PageBackground)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("保存范围") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
    }
}

private fun diagnosticTime(timestamp: Long): String = if (timestamp == 0L) "尚无"
    else SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
