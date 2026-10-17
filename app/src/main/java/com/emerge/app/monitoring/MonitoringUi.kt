@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emerge.app.monitoring

import com.emerge.app.ui.PojianTextButton
import com.emerge.app.ui.PojianSwitch
import com.emerge.app.ui.PojianTextField
import com.emerge.app.ui.PojianSlider
import com.emerge.app.ui.PojianDialog
import com.emerge.app.ui.ServiceConnectionTest
import com.emerge.app.ui.DesignTokens
import com.emerge.app.ui.Ink
import com.emerge.app.ui.Muted
import com.emerge.app.ui.SectionTitle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emerge.app.audioanalysis.TextServiceConfig

@Composable
fun MonitorSettingsSection(
    config: MonitorConfig,
    onChange: (MonitorConfig) -> Unit,
    requestCount: Int,
    frameCount: Int,
    onCloud: () -> Unit,
    onCaptureTools: () -> Unit,
    cloudEnabled: Boolean = false,
    onCloudEnabledChange: (Boolean) -> Unit = {},
    cloudConfig: TextServiceConfig? = null,
    embedded: Boolean = false,
    includeCloudControls: Boolean = true,
    includeBubbleControl: Boolean = true
) {
    var showMore by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!embedded) SectionTitle("提醒与隐私")
        if (includeCloudControls) {
            MonitorSwitch("云端分析", "发送文字；开启画面分析时发送采样画面", cloudEnabled, onCloudEnabledChange)
        }
        MonitorSwitch("实时事实核查", "", config.realtime) { onChange(config.copy(realtime = it)) }
        MonitorSwitch("关键画面分析", "", config.visual) { onChange(config.copy(visual = it)) }
        MonitorSlider("画面采样间隔", config.frameIntervalSeconds, 15..120, "秒") {
            onChange(config.copy(frameIntervalSeconds = it))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DesignTokens.Border.copy(alpha = 0.75f))
        if (includeBubbleControl) {
            MonitorSwitch("悬浮球", "", config.bubble) { onChange(config.copy(bubble = it)) }
        }
        MonitorSwitch("重复观点提醒", "", config.cocoon) { onChange(config.copy(cocoon = it)) }
        MonitorSwitch("内容情绪提醒", "", config.emotion) { onChange(config.copy(emotion = it)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("情绪提醒时段", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(
                text = "30 分钟 · 固定",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Ink
            )
        }
        MonitorSwitch("专题研究模式", "", config.researchMode) { onChange(config.copy(researchMode = it)) }
        MonitorSwitch("夜间免打扰", "${config.quietStart}:00—${config.quietEnd}:00", config.quiet) {
            onChange(config.copy(quiet = it))
        }
        PojianTextButton(
            onClick = { showMore = !showMore },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("monitor-more")
                .semantics { stateDescription = if (showMore) "已展开" else "已收起" }
        ) {
            Text("更多", Modifier.weight(1f))
            Icon(
                imageVector = if (showMore) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        if (showMore) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonitorSlider("提醒间隔", config.cooldownMinutes, 5..180, "分钟") {
                    onChange(config.copy(cooldownMinutes = it))
                }
                MonitorSlider("每日提醒上限", config.dailyAlerts, 0..30, "次") {
                    onChange(config.copy(dailyAlerts = it))
                }
                MonitorSlider("提示停留时长", config.displaySeconds, 2..8, "秒") {
                    onChange(config.copy(displaySeconds = it))
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DesignTokens.Border.copy(alpha = 0.75f))
        Text(
            text = "今日云端请求 $requestCount 次（含画面 $frameCount 次）",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            fontFamily = FontFamily.Monospace
        )
        cloudConfig?.let { ServiceConnectionTest(it) }
        if (!embedded) PojianTextButton(onClick = onCaptureTools) { Text("实时记录") }
    }
}

@Composable
private fun MonitorSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = DesignTokens.InnerShape, color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("monitor-switch-$title")
                .toggleable(checked, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
                if (title == "云端分析" || title == "允许云端分析" || title == "夜间免打扰") {
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            PojianSwitch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun MonitorSlider(title: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(
                text = "${draft.toInt()} $unit",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
        }
        PojianSlider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = "${draft.toInt()} $unit"
            }
        )
    }
}

@Composable
fun MonitorGatewayDialog(
    config: TextServiceConfig,
    onDismiss: () -> Unit,
    onSave: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit
) {
    var enabled by remember { mutableStateOf(config.enabled) }
    var endpoint by remember { mutableStateOf(config.endpoint) }
    var token by remember { mutableStateOf(config.token) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    PojianDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("云端分析接口") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MonitorSwitch("允许云端分析", "决定自动发送范围；关闭后只保留本地观察", enabled) { enabled = it }
                PojianTextField(
                    value = endpoint,
                    onValueChange = {
                        if (it.trim().trimEnd('/') != endpoint.trim().trimEnd('/')) token = ""
                        endpoint = it
                    },
                    label = { Text("HTTPS 服务地址") },
                    singleLine = true
                )
                PojianTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("访问令牌（非模型密钥）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            PojianTextButton(
                enabled = !saving,
                onClick = {
                    val validation = com.emerge.app.ui.connectionTestEndpointError(endpoint)
                    if (validation != null) error = validation
                    else {
                        saving = true
                        onSave(TextServiceConfig(enabled, endpoint, token), onDismiss) {
                            error = it
                            saving = false
                        }
                    }
                }
            ) {
                Text(if (saving) "保存中" else "保存")
            }
        },
        dismissButton = {
            PojianTextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        }
    )
}
