package com.zhiheng.app.ui

import android.view.accessibility.AccessibilityEvent
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiheng.app.data.BrowseEvent
import com.zhiheng.app.capture.CaptureStatus
import com.zhiheng.app.analysis.AnalysisState
import com.zhiheng.app.analysis.CognitiveReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

val Accent = Color(0xFF208B80)
val PageBackground = Color(0xFFF3F5F7)
val Ink = Color(0xFF202A35)
internal val Muted = Color(0xFF87919B)
internal val Blue = Color(0xFF369AF5)
internal val ChartColors = listOf(Blue, Color(0xFF49C681), Color(0xFFFFAA49),
    Color(0xFF9D83E8), Color(0xFFF17FA5), Color(0xFFBCC7D2))

@Composable
internal fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp)
}

@Composable
fun OverviewScreen(stats: BrowseStatistics, period: StatisticsPeriod, onPeriod: (StatisticsPeriod) -> Unit,
    active: Boolean, statusLabel: String, appNames: Map<String, String>, onRecords: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, analysis: AnalysisState = AnalysisState(),
    rawEvents: List<BrowseEvent> = emptyList(), onAnalyze: (String) -> Unit = {},
    onExport: (CognitiveReport, Boolean) -> Unit = { _, _ -> }) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("信息环境", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("看清接触的观点与来源", color = Muted, modifier = Modifier.padding(top = 5.dp))
                }
                Surface(color = if (active) Color(0xFFE5F5ED) else Color(0xFFE8EDF2),
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable(onClick = onSettings)) {
                    Text(if (active) "● $statusLabel" else "○ $statusLabel", color = if (active) Accent else Muted,
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0xFFE8EBEF)).padding(5.dp)) {
                StatisticsPeriod.entries.forEach { option ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                        .background(if (period == option) Color.White else Color.Transparent)
                        .clickable { onPeriod(option) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(option.label, color = if (period == option) Ink else Muted,
                            fontWeight = if (period == option) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        item { AnalysisCards(analysis, period, rawEvents, onAnalyze, onExport) }
        item {
            WhiteCard {
                SectionTitle("我的信息来自哪里", "应用来源 · 按已保存记录统计")
                val shares = if (stats.apps.size <= 6) stats.apps else stats.apps.take(5) + AppShare("__other__", stats.apps.drop(5).sumOf { it.count })
                DonutChart(shares, stats.total)
                shares.forEachIndexed { index, share ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(ChartColors[index]))
                        Text(if (share.packageName == "__other__") "其他应用" else appNames[share.packageName] ?: share.packageName,
                            modifier = Modifier.weight(1f).padding(start = 9.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(Locale.CHINA, "%.1f%%", share.count * 100.0 / max(stats.total, 1)), color = Muted, fontSize = 13.sp)
                    }
                }
                if (shares.isEmpty()) Text("有了数据，就能看到不同应用的记录分布。", color = Muted, fontSize = 12.sp)
                Text("当前仅能识别来源应用，尚未识别作者、媒体和原始出处。跨多个应用也可能接触同一来源或观点。",
                    color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("来源应用排行")
                stats.apps.take(5).forEachIndexed { index, share ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(ChartColors[index].copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", color = ChartColors[index], fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(appNames[share.packageName] ?: share.packageName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${share.count} 条采集记录", fontSize = 12.sp, color = Muted)
                        }
                        Text(String.format(Locale.CHINA, "%.1f%%", share.count * 100.0 / max(stats.total, 1)), color = Muted)
                    }
                    if (index < minOf(stats.apps.size, 5) - 1) HorizontalDivider(color = PageBackground)
                }
                if (stats.apps.isEmpty()) Text("暂无来源应用", color = Muted)
                TextButton(onClick = onRecords, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("查看采集记录  ›") }
            }
        }
        item {
            WhiteCard {
                SectionTitle("分析基础")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("已记录的应用来源", color = Muted)
                    Text("${stats.appCount} 个", fontWeight = FontWeight.SemiBold)
                }
                Text("本期保存 ${stats.total} 条文本记录。离线分析将过滤界面文字、合并连续相同画面；观点候选需要核查，不代表独立帖子或实际阅读量。", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun DonutChart(shares: List<AppShare>, total: Int, label: String = "条采集记录") {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(210.dp).semantics { contentDescription = "$label 分布环图，共 $total，占比见下方列表" }) {
            val stroke = 27.dp.toPx()
            val inset = stroke / 2
            val circle = Size(size.width - stroke, size.height - stroke)
            drawArc(PageBackground, 0f, 360f, false, Offset(inset, inset), circle, style = Stroke(stroke))
            var angle = -90f
            shares.forEachIndexed { index, share ->
                val sweep = share.count * 360f / max(total, 1)
                drawArc(ChartColors[index], angle, sweep, false, Offset(inset, inset), circle, style = Stroke(stroke))
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
fun RecordsScreen(events: List<BrowseEvent>, count: Int, appNames: Map<String, String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("浏览记录", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("已保存 $count 条 · 展示最新 200 条", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (events.isEmpty()) item {
            WhiteCard {
                Text("等待第一条信息足迹", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("在「我的」启用辅助功能与采集开关，去其他应用阅读和滚动，然后回到这里。", color = Muted)
            }
        }
        items(events, key = { it.id }) { event ->
            var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
            WhiteCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(appNames[event.packageName] ?: event.packageName, modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(event.timestamp)), color = Muted, fontSize = 11.sp)
                }
                Text(if (event.sampleCount > 0) "估计可见停留 ${event.durationMillis / 1000} 秒 · ${event.sampleCount} 次检查"
                    else "旧版快照 · 停留时长未知", color = Muted, fontSize = 12.sp)
                Text(event.visibleText, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, lineHeight = 23.sp)
                if (expanded) Text("${event.packageName}\n${AccessibilityEvent.eventTypeToString(event.eventType)} (${event.eventType})",
                    color = Muted, fontSize = 11.sp)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "查看全文  ›") }
            }
        }
    }
}

@Composable
fun SettingsScreen(permission: Boolean, enabled: Boolean, count: Int, onCapture: (Boolean) -> Unit,
    onOpenSettings: () -> Unit, onClear: () -> Unit, message: String?, modifier: Modifier = Modifier,
    diagnostics: CaptureStatus = CaptureStatus()) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("我的知衡", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("你的数据，由你掌握", color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
        item {
            WhiteCard {
                SectionTitle("采集管理")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许采集", fontWeight = FontWeight.Medium)
                        Text(if (enabled && diagnostics.connected) "采集服务已连接" else if (enabled && permission) "系统已授权，等待服务连接" else if (enabled) "等待启用辅助功能" else "已暂停采集", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = enabled, onCheckedChange = onCapture)
                }
                HorizontalDivider(color = PageBackground)
                Row(Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("辅助功能权限", modifier = Modifier.weight(1f))
                    Text(if (permission) "已启用  ›" else "去开启  ›", color = if (permission) Accent else Blue)
                }
                Text("启用「知衡浏览采集」后，返回此处打开采集开关。浏览其他应用的文字页面，再到「记录」查看。", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("采集诊断", "用于排查已开启但没有记录的问题")
                Text("系统授权：${if (permission) "已启用" else "未检测到"}")
                Text("服务连接：${if (diagnostics.connected) "已连接" else "未连接"}")
                Text("收到事件：${diagnostics.eventsReceived} 次（本次进程）")
                Text("最近浏览应用：${diagnostics.lastPackage}", fontSize = 12.sp)
                Text("最近检查：${diagnosticTime(diagnostics.lastCheck)}", fontSize = 12.sp)
                Text("最近保存：${diagnosticTime(diagnostics.lastSaved)}", fontSize = 12.sp)
                Text(diagnostics.result, color = Accent, fontSize = 13.sp)
                Text("请确认启用的是「知衡浏览采集」服务本身，而非仅开启辅助功能快捷按钮。若已授权但未连接，关闭再开启该服务，返回知衡检查。",
                    color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("本地数据")
                Text("$count 条已保存记录", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("仅保存在这台设备，不上传。最多保留 5000 条，超出后删除最早记录。", color = Muted, fontSize = 12.sp)
                OutlinedButton(onClick = onClear, enabled = count > 0, shape = RoundedCornerShape(14.dp)) { Text("暂停并清空全部记录") }
                message?.let { Text(it, color = Accent, fontSize = 12.sp) }
            }
        }
        item {
            WhiteCard {
                SectionTitle("采集说明与隐私")
                Text("开启后读取其他应用当前窗口暴露的可见文字、应用包名、时间与事件类型。跳过密码和可编辑输入框，并过滤本应用、系统设置及部分键盘。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                Text("正文仍可能包含个人信息。请只在愿意记录的内容上开启，结束后暂停。数据库尚未额外加密。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
            }
        }
        item {
            WhiteCard {
                SectionTitle("关于知衡", "V0.2 · 离线可解释分析原型")
                Text("离线文本规则分析支持观点候选、依据片段比较、长期重复线索和报告导出。未接入多模态、语义模型、时序图学习或 TEE，规则结果需要人工核查。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
            }
        }
    }
}

private fun diagnosticTime(timestamp: Long): String = if (timestamp == 0L) "尚无"
    else SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))

