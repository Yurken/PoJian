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
import com.emerge.app.audioanalysis.TextServiceConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorOverviewCard(state: MonitorState, config: MonitorConfig, onDiscover: () -> Unit, onSettings: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("信息环境与事实核查", style = MaterialTheme.typography.titleMedium)
            Text("观点观察${if (config.cocoon) "已开启" else "已关闭"} · 实时核查${if (config.realtime) "已开启" else "已关闭"}")
            Text("图文、短视频与文章共用分析入口。${if (config.visual) "关键画面按间隔采样。" else "画面分析未开启，图片中的信息可能未被覆盖。"}",
                style = MaterialTheme.typography.bodySmall)
            Text(state.notice, style = MaterialTheme.typography.bodySmall)
            if (state.skipped > 0) Text("本次运行有 ${state.skipped} 次输入未完成核查；没有提醒不等于真实。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDiscover) { Text("查看发现与证据") }
                TextButton(onClick = onSettings) { Text("设置监测方式") }
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
        item { Text("发现", style = MaterialTheme.typography.headlineMedium) }
        item { Text("保留的事实候选中：$completed 条完成了取证流程，${facts.count { it.verdict == "not_checked" }} 条未核查。证据不足不代表真假已定。",
            style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "观点观察", "事实核查").forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
        }
        if (displayed.isEmpty()) item {
            Text("还没有相应发现。在设置中选择应用并启用监测。图文画面需要单独开启视觉分析；音频转写不是唯一输入。")
        }
        items(displayed, key = { it.id }) { finding ->
            OutlinedCard(onClick = { selected = finding }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(finding.label, style = MaterialTheme.typography.titleSmall)
                    Text(finding.claim, maxLines = 4)
                    Text("${appNames[finding.packageName] ?: finding.packageName} · ${SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(Date(finding.capturedAt))} · " +
                        when (finding.inputKind) { "frame" -> "画面采样"; "audio" -> "口播转写"; else -> "可见文字" },
                        style = MaterialTheme.typography.labelSmall)
                    Text(if (finding.sources.isEmpty()) "${finding.explanation.take(100)}" else "${finding.sources.size} 条外部引用 · 点击查看依据",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onRecords) { Text("查看原文材料") }
            TextButton(onClick = { confirm = true }) { Text("清除核查结果") }
        } }
    }
    selected?.let { finding ->
        // A revision/deletion must close a detail sheet containing a stale result.
        LaunchedEffect(state.findings) { if (state.findings.none { it == finding }) selected = null }
        val uri = LocalUriHandler.current
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(finding.label) },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(finding.claim, style = MaterialTheme.typography.titleSmall)
                if (finding.inputKind == "frame") Text("图中文字由视觉模型提取，仍需对照原画面核实。", style = MaterialTheme.typography.bodySmall)
                if (finding.inputKind == "audio") Text("语音转写可能听错否定、数字和姓名，请回查对应文字。", style = MaterialTheme.typography.bodySmall)
                Text(finding.explanation)
                finding.sources.forEach { source ->
                    HorizontalDivider(); Text(source.title, style = MaterialTheme.typography.titleSmall); Text("引文：${source.quote}")
                    TextButton(onClick = { if (source.valid()) runCatching { uri.openUri(source.url) } }) { Text("打开原始来源") }
                }
                Text("仅覆盖已经采样且成功处理的内容，不代表整篇笔记、全部图片或完整视频已被核查。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清除全部核查结果？") },
        text = { Text("将清除保留的核查结果、比较材料和临时缓存，并作废正在处理的旧请求；不会重置调用额度或删除原文记录。") },
        confirmButton = { TextButton(onClick = { onClear(); confirm = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
fun MonitorSettingsSection(config: MonitorConfig, onChange: (MonitorConfig) -> Unit,
    requestCount: Int, frameCount: Int, onCloud: () -> Unit, onCaptureTools: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("监测与提醒", style = MaterialTheme.typography.titleMedium)
        MonitorSwitch("悬浮球", "仅在所选应用显示，不抢焦点；拖动调整位置，点击核查。", config.bubble) { onChange(config.copy(bubble = it)) }
        MonitorSwitch("信息茧房线索观察", "区分议题、对象、观点与依据；不把某种价值观本身当作风险。", config.cocoon) { onChange(config.copy(cocoon = it)) }
        MonitorSwitch("实时事实核查", "启用云端服务后，自动发送新增文字及稳定口播片段并检索外部依据。", config.realtime) { onChange(config.copy(realtime = it)) }
        MonitorSwitch("关键画面分析", "Android 11+。将所选应用的当前画面采样发送给视觉模型；图文无需录音。与文字共享云端额度。", config.visual) { onChange(config.copy(visual = it)) }
        MonitorSwitch("专题研究模式", "保留统计，暂缓观点集中提醒；事实矛盾提醒不受影响。", config.researchMode) { onChange(config.copy(researchMode = it)) }
        MonitorSwitch("夜间免打扰", "${config.quietStart}:00—${config.quietEnd}:00 不主动展开提醒；默认始终静音、不振动。", config.quiet) { onChange(config.copy(quiet = it)) }
        MonitorSlider("同议题/声明提醒间隔", config.cooldownMinutes, 5..180, "分钟") { onChange(config.copy(cooldownMinutes = it)) }
        MonitorSlider("每日主动提醒上限", config.dailyAlerts, 0..30, "次") { onChange(config.copy(dailyAlerts = it)) }
        MonitorSlider("自动提示停留", config.displaySeconds, 2..8, "秒") { onChange(config.copy(displaySeconds = it)) }
        MonitorSlider("自动画面采样间隔", config.frameIntervalSeconds, 15..120, "秒") { onChange(config.copy(frameIntervalSeconds = it)) }
        MonitorSlider("每日画面请求上限", config.dailyFrames, 1..100, "次") { onChange(config.copy(dailyFrames = it)) }
        Text("今日共享云端请求 $requestCount 次，其中画面请求 $frameCount 次。单次网关请求可能包含多次模型/检索调用，不是固定费用。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onCloud) { Text("配置语义、视觉与取证网关") }
        TextButton(onClick = onCaptureTools) { Text("采集工具：播放音频、模型与材料导入") }
    }
}

@Composable
private fun MonitorSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange)
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
            Text("启动 server/monitor_server.py。语义模型、视觉模型和检索密钥分别在服务端配置；这里只填写网关地址与访问令牌。")
            MonitorSwitch("允许云端分析", "实时核查和关键画面开关决定自动发送范围；关闭本开关后只保留本地观察。", enabled) { enabled = it }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS 网关地址（不含 /monitor）") }, singleLine = true)
            OutlinedTextField(token, { token = it }, label = { Text("服务访问令牌，不是模型 API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(limit, { limit = it }, label = { Text("每日共享请求上限（1—100）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text("文字、播放音频和画面共用本额度。视觉与检索能力需要服务端另行配置；没有外部证据时不会宣称核查成功。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = {
            val count = limit.toIntOrNull()
            if (count == null || count !in 1..100) error = "请输入 1—100 的每日请求上限"
            else { saving = true; onSave(TextServiceConfig(enabled, endpoint, token, count), onDismiss) { error = it; saving = false } }
        }) { Text(if (saving) "保存中" else "保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
