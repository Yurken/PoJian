@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emerge.app.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emerge.app.ui.SectionTitle
import com.emerge.app.audioanalysis.TextServiceConfig

@Composable
fun MonitorSettingsSection(config: MonitorConfig, onChange: (MonitorConfig) -> Unit,
    requestCount: Int, frameCount: Int, onCloud: () -> Unit, onCaptureTools: () -> Unit,
    cloudEnabled: Boolean = false, onCloudEnabledChange: (Boolean) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("监测与提醒", help = "悬浮球：在所选应用内点击核查。\n观点重复观察：比较议题、观点与依据。\n实时事实核查：自动核查新增文字与口播。\n关键画面分析：采样当前画面，需 Android 11 及以上。\n专题研究模式：保留统计，暂缓重复提醒。\n夜间免打扰：${config.quietStart}:00—${config.quietEnd}:00 不主动提醒，提醒始终静音。")
        MonitorSwitch("云端分析", "发送文字；开启画面分析时发送采样画面", cloudEnabled, onCloudEnabledChange)
        MonitorSwitch("实时事实核查", "", config.realtime) { onChange(config.copy(realtime = it)) }
        MonitorSwitch("关键画面分析", "", config.visual) { onChange(config.copy(visual = it)) }
        MonitorSlider("画面采样间隔", config.frameIntervalSeconds, 15..120, "秒") { onChange(config.copy(frameIntervalSeconds = it)) }
        MonitorSlider("每日画面请求上限", config.dailyFrames, 1..100, "次") { onChange(config.copy(dailyFrames = it)) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        MonitorSwitch("悬浮球", "", config.bubble) { onChange(config.copy(bubble = it)) }
        MonitorSwitch("观点重复观察", "", config.cocoon) { onChange(config.copy(cocoon = it)) }
        MonitorSwitch("专题研究模式", "", config.researchMode) { onChange(config.copy(researchMode = it)) }
        MonitorSwitch("夜间免打扰", "${config.quietStart}:00—${config.quietEnd}:00", config.quiet) { onChange(config.copy(quiet = it)) }
        MonitorSlider("提醒间隔", config.cooldownMinutes, 5..180, "分钟") { onChange(config.copy(cooldownMinutes = it)) }
        MonitorSlider("每日提醒上限", config.dailyAlerts, 0..30, "次") { onChange(config.copy(dailyAlerts = it)) }
        MonitorSlider("自动提示停留", config.displaySeconds, 2..8, "秒") { onChange(config.copy(displaySeconds = it)) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("今日云端请求 $requestCount 次（含画面 $frameCount 次）", style = MaterialTheme.typography.bodySmall)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text("${draft.toInt()} $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
