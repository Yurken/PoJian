@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emerge.app.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emerge.app.ui.HelpButton
import com.emerge.app.ui.SectionTitle
import com.emerge.app.audioanalysis.TextServiceConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorOverviewCard(state: MonitorState, config: MonitorConfig, onDiscover: () -> Unit, onSettings: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("监测总览", help = "画面分析${if (config.visual) "已开启" else "未开启"}。仅核查已采样并处理的内容，证据不足时保留待确认状态。")
            Text("观点观察 · ${if (config.cocoon) "开" else "关"} · 实时核查 · ${if (config.realtime) "开" else "关"}")
            if (state.notice.isNotBlank() && state.notice != MonitorState().notice)
                Text(state.notice, style = MaterialTheme.typography.bodySmall)
            if (state.pending > 0 || state.retrying > 0) Text(
                "待分析 ${state.pending} 项${if (state.retrying > 0) "（含 ${state.retrying} 项待重试）" else ""}",
                style = MaterialTheme.typography.bodySmall)
            if (state.skipped > 0) Text("${state.skipped} 次核查未完成", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDiscover) { Text("查看发现") }
                TextButton(onClick = onSettings) { Text("监测设置") }
            }
        }
    }
}

@Composable
fun MonitorDiscoverScreen(state: MonitorState, appNames: Map<String, String>, modifier: Modifier = Modifier,
    onRecords: () -> Unit, onClear: () -> Unit) {
    var selected by remember { mutableStateOf<MonitorFinding?>(null) }
    var filter by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val facts = state.findings.filter { it.kind == "fact" }
    val completed = facts.count { it.verdict in setOf("supported", "contradicted", "conflicting", "insufficient") }
    val displayed = state.findings.filter { filter == 0 || (filter == 1 && it.kind == "cocoon") || (filter == 2 && it.kind == "fact") }
    LazyColumn(modifier.testTag("monitor-discovery-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("发现", help = "观点观察比较观点与依据的重复出现；事实核查展示外部来源与结论。仅覆盖已采样的内容，转写与画面识别可能有误，可查看原文核对。") }
        if (state.pending > 0) item {
            Text("待分析 ${state.pending} 项",
                style = MaterialTheme.typography.bodySmall)
        }
        item { Text("已核查 $completed 条 · 未核查 ${facts.count { it.verdict == "not_checked" }} 条",
            style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "观点观察", "事实核查").forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
        }
        if (displayed.isEmpty()) item {
            Text("暂无发现")
        }
        items(displayed, key = { it.id }) { finding ->
            OutlinedCard(onClick = { selected = finding }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(finding.label, style = MaterialTheme.typography.titleSmall)
                    Text(finding.claim, maxLines = 4)
                    Text("${appNames[finding.packageName] ?: finding.packageName} · ${SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(Date(finding.capturedAt))} · " +
                        when (finding.inputKind) { "frame" -> "画面采样"; "audio" -> "口播转写"; else -> "可见文字" },
                        style = MaterialTheme.typography.labelSmall)
                    Text(if (finding.sources.isEmpty()) "${finding.explanation.take(100)}" else "${finding.sources.size} 条来源 ›",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onRecords) { Text("查看原文材料") }
            TextButton(onClick = { confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除核查结果") }
        } }
    }
    selected?.let { finding ->
        // A revision/deletion must close a detail sheet containing a stale result.
        LaunchedEffect(state.findings) { if (state.findings.none { it == finding }) selected = null }
        val uri = LocalUriHandler.current
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(finding.label) },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(finding.claim, style = MaterialTheme.typography.titleSmall)
                Text(finding.explanation)
                finding.sources.forEach { source ->
                    HorizontalDivider(); Text(source.title, style = MaterialTheme.typography.titleSmall); Text("引文：${source.quote}")
                    TextButton(onClick = { if (source.valid()) runCatching { uri.openUri(source.url) } }) { Text("打开来源") }
                }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清除全部核查结果？") },
        text = { Text("清除核查结果与比较材料，保留原文记录。无法撤销。") },
        confirmButton = { Button(onClick = { onClear(); confirm = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("清除结果") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
fun MonitorSettingsSection(config: MonitorConfig, onChange: (MonitorConfig) -> Unit,
    requestCount: Int, frameCount: Int, onCloud: () -> Unit, onCaptureTools: () -> Unit,
    cloudEnabled: Boolean = false, onCloudEnabledChange: (Boolean) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("监测与提醒", help = "悬浮球：在所选应用内点击核查。\n观点重复观察：比较议题、观点与依据。\n实时事实核查：自动核查新增文字与口播。\n关键画面分析：采样当前画面，需 Android 11 及以上。\n专题研究模式：保留统计，暂缓重复提醒。\n夜间免打扰：${config.quietStart}:00—${config.quietEnd}:00 不主动提醒，提醒始终静音。")
        MonitorSwitch("云端分析", "发送文字；开启画面分析时发送采样画面", cloudEnabled, onCloudEnabledChange)
        MonitorSwitch("悬浮球", "在所选应用内显示，点击即可核查", config.bubble) { onChange(config.copy(bubble = it)) }
        MonitorSwitch("观点重复观察", "识别议题、观点与依据的重复出现", config.cocoon) { onChange(config.copy(cocoon = it)) }
        MonitorSwitch("实时事实核查", "自动核查新增文字与口播片段", config.realtime) { onChange(config.copy(realtime = it)) }
        MonitorSwitch("关键画面分析", "Android 11+，采样当前画面用于分析", config.visual) { onChange(config.copy(visual = it)) }
        MonitorSwitch("专题研究模式", "保留统计，暂缓观点重复提醒", config.researchMode) { onChange(config.copy(researchMode = it)) }
        MonitorSwitch("夜间免打扰", "${config.quietStart}:00—${config.quietEnd}:00", config.quiet) { onChange(config.copy(quiet = it)) }
        MonitorSlider("同议题/声明提醒间隔", config.cooldownMinutes, 5..180, "分钟") { onChange(config.copy(cooldownMinutes = it)) }
        MonitorSlider("每日主动提醒上限", config.dailyAlerts, 0..30, "次") { onChange(config.copy(dailyAlerts = it)) }
        MonitorSlider("自动提示停留", config.displaySeconds, 2..8, "秒") { onChange(config.copy(displaySeconds = it)) }
        MonitorSlider("自动画面采样间隔", config.frameIntervalSeconds, 15..120, "秒") { onChange(config.copy(frameIntervalSeconds = it)) }
        MonitorSlider("每日画面请求上限", config.dailyFrames, 1..100, "次") { onChange(config.copy(dailyFrames = it)) }
        Text("今日云端请求 $requestCount 次（含画面 $frameCount 次）", style = MaterialTheme.typography.bodySmall)
        // 网关与模型配置统一在服务端管理台维护，App 不再提供设置入口。
        // TextButton(onClick = onCloud) { Text("配置语义、视觉与取证网关") }
        TextButton(onClick = onCaptureTools) { Text("采集工具") }
    }
}

@Composable
private fun MonitorSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (title == "云端分析" || title == "允许云端分析" || title == "夜间免打扰")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("monitor-switch-$title"))
    }
}

@Composable
private fun MonitorSlider(title: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$title：${draft.toInt()} $unit", style = MaterialTheme.typography.bodySmall)
    Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat())
}

@Composable
fun MonitorGatewayDialog(config: TextServiceConfig, onDismiss: () -> Unit,
    onSave: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit) {
    var enabled by remember { mutableStateOf(config.enabled) }
    var endpoint by remember { mutableStateOf(config.endpoint) }
    var token by remember { mutableStateOf(config.token) }
    var limit by remember { mutableStateOf(config.dailyLimit.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("分析与取证网关") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("填写已部署服务的地址与访问令牌。")
            MonitorSwitch("允许云端分析", "决定自动发送范围；关闭后只保留本地观察", enabled) { enabled = it }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS 网关地址（不含 /monitor）") }, singleLine = true)
            OutlinedTextField(token, { token = it }, label = { Text("访问令牌（非模型密钥）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(limit, { limit = it }, label = { Text("每日共享请求上限（1—100）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text("文字、音频与画面共用额度。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = {
            val count = limit.toIntOrNull()
            if (count == null || count !in 1..100) error = "请输入 1—100 的每日请求上限"
            else { saving = true; onSave(TextServiceConfig(enabled, endpoint, token, count), onDismiss) { error = it; saving = false } }
        }) { Text(if (saving) "保存中" else "保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
