package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.data.LocalStorageUsage
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.monitoring.MonitorConfig
import com.emerge.app.monitoring.MonitorSettingsSection

@Composable
fun SwissSettingsScreen(
    storageUsage: LocalStorageUsage,
    canClear: Boolean,
    onClear: () -> Unit,
    onSaveStorageLimit: suspend (Long) -> Unit,
    cloudConfig: TextServiceConfig,
    onSaveCloud: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit,
    monitorConfig: MonitorConfig,
    onMonitorChange: (MonitorConfig) -> Unit,
    requestCount: Int,
    frameCount: Int,
    onCaptureTools: () -> Unit,
    onReports: () -> Unit,
    onClearThumbnails: suspend () -> Unit,
    modifier: Modifier = Modifier,
    audioSettingsContent: (@Composable () -> Unit)? = null,
    transcriptContent: (@Composable () -> Unit)? = null,
    updateContent: (@Composable () -> Unit)? = null,
    captureSettingsContent: (@Composable () -> Unit)? = null,
    onSaveStorageRetention: (suspend (Int) -> Unit)? = null
) {
    var cloudToggleError by remember { mutableStateOf<String?>(null) }
    val activeMonitors = listOf(
        monitorConfig.realtime,
        monitorConfig.visual,
        monitorConfig.cocoon,
        monitorConfig.emotion
    ).count { it }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("swiss-settings-screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item("capture-permissions") {
            SwissSettingsSection(
                index = "01",
                title = "采集与系统权限",
                subtitle = "CAPTURE & SCOPE"
            ) {
                captureSettingsContent?.invoke() ?: SettingsActionRow("实时记录", onClick = onCaptureTools)
                SettingsHairline()
                SettingsDisclosure(
                    title = "提醒与隐私",
                    value = "已启用 $activeMonitors 项",
                    tag = "settings-monitor-options"
                ) {
                    MonitorSettingsSection(
                        config = monitorConfig,
                        onChange = onMonitorChange,
                        requestCount = requestCount,
                        frameCount = frameCount,
                        onCloud = {},
                        onCaptureTools = onCaptureTools,
                        embedded = true,
                        includeCloudControls = false,
                        includeBubbleControl = captureSettingsContent == null
                    )
                }
                SettingsHairline()
                LocalPrivacyStatusCard(embedded = true)
            }
        }
        item("analysis-engine") {
            SwissSettingsSection(
                index = "02",
                title = "分析引擎与模型",
                subtitle = "ENGINE & MODELS"
            ) {
                SettingsSwitchRow(
                    title = "云端分析",
                    checked = cloudConfig.enabled,
                    onChange = { value ->
                        onSaveCloud(
                            cloudConfig.copy(enabled = value),
                            { cloudToggleError = null },
                            { cloudToggleError = it }
                        )
                    },
                    modifier = Modifier.testTag("monitor-switch-云端分析"),
                    status = "发送文字；开启画面分析时发送采样画面"
                )
                cloudToggleError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                SettingsHairline()
                CloudConnectionSettingsCard(cloudConfig, embedded = true, onSave = onSaveCloud)
                audioSettingsContent?.let {
                    SettingsHairline()
                    it()
                }
                SettingsHairline()
                ConfidentialComputingCard(embedded = true)
            }
        }
        item("storage-retention") {
            SwissSettingsSection(
                index = "03",
                title = "数据与存储管理",
                subtitle = "STORAGE & DATA"
            ) {
                LocalStorageSettingsCard(
                    usage = storageUsage,
                    canClear = canClear,
                    onClear = onClear,
                    onSaveLimit = onSaveStorageLimit,
                    onClearThumbnails = onClearThumbnails,
                    embedded = true,
                    onSaveRetention = onSaveStorageRetention
                )
                SettingsHairline()
                LocalThumbnailControl(embedded = true)
                transcriptContent?.let { content ->
                    SettingsHairline()
                    SettingsDisclosure(
                        title = "录音转文字",
                        value = "转写记录",
                        tag = "settings-transcripts-options"
                    ) { content() }
                }
                SettingsHairline()
                SettingsActionRow(
                    title = "浏览报告",
                    modifier = Modifier.testTag("settings-reports"),
                    value = "预览报告",
                    onClick = onReports
                )
            }
        }
        item("system-about") {
            SwissSettingsSection(
                index = "04",
                title = "系统与关于",
                subtitle = "SYSTEM & VERSION"
            ) {
                updateContent?.invoke() ?: SettingsDataRow("当前版本", "v${com.emerge.app.BuildConfig.VERSION_NAME}")
            }
        }
    }
}

/** A verification screen cannot manufacture an attestation or certify operator access. */
@Composable
internal fun ConfidentialComputingCard(embedded: Boolean = false) {
    val details: @Composable ColumnScope.() -> Unit = {
        SettingsDataRow("TEE 远程证明", "未配置")
        SettingsHairline()
        SettingsDataRow("RFC 9334 / Attested TLS", "未接入")
        SettingsHairline()
        SettingsDataRow("Enclave 度量指纹", "—")
        SettingsHairline()
        SettingsDataRow("服务运营方明文权限", "未验证")
        SettingsHairline()
        SettingsDataRow("密钥绑定", "未验证")
        Spacer(Modifier.height(2.dp))
        PojianSecondaryButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().testTag("tee-reverify")
        ) {
            Text("重新验证远程证明与密钥绑定")
        }
    }
    SettingsContentFrame(embedded, Modifier.testTag("tee-verification")) {
        if (embedded) {
            SettingsDisclosure("可验证机密计算", "未配置", "settings-tee-options", content = details)
        } else {
            SectionTitle("可验证机密计算")
            details()
        }
    }
}

@Composable
internal fun LocalPrivacyStatusCard(embedded: Boolean = false) {
    val details: @Composable ColumnScope.() -> Unit = {
        SettingsDataRow("密码与输入框", "自动跳过")
        SettingsHairline()
        SettingsDataRow("支付页面识别", "未接入")
        SettingsHairline()
        SettingsDataRow("保存的令牌与任务队列", "AES-GCM")
        SettingsHairline()
        SettingsDataRow("本地数据库加密", "未启用")
    }
    SettingsContentFrame(embedded, Modifier.testTag("privacy-status")) {
        if (embedded) {
            SettingsDisclosure("隐私安全", "本地边界", tag = "settings-privacy-options", content = details)
        } else {
            SectionTitle("隐私安全")
            details()
        }
    }
}

@Composable
internal fun CloudConnectionSettingsCard(
    config: TextServiceConfig,
    embedded: Boolean = false,
    onSave: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit
) {
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var token by remember(config.endpoint, config.token) { mutableStateOf(config.token) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val validation = if (endpoint.isNotBlank()) connectionTestEndpointError(endpoint) else null
    val draft = TextServiceConfig(config.enabled, endpoint, token)
    val editor: @Composable ColumnScope.() -> Unit = {
        PojianTextField(
            value = endpoint,
            onValueChange = { value ->
                // A credential for one service is never silently carried to another address.
                if (value.trim().trimEnd('/') != endpoint.trim().trimEnd('/')) token = ""
                endpoint = value
                result = null
            },
            modifier = Modifier.fillMaxWidth().testTag("service-endpoint"),
            label = { Text("HTTPS 服务地址") },
            singleLine = true,
            enabled = !saving,
            isError = validation != null,
            supportingText = validation?.let { error -> ({ Text(error) }) }
        )
        PojianTextField(
            value = token,
            onValueChange = { token = it; result = null },
            modifier = Modifier.fillMaxWidth().testTag("service-token"),
            label = { Text("访问令牌") },
            singleLine = true,
            enabled = !saving,
            visualTransformation = PasswordVisualTransformation()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PojianButton(
                onClick = {
                    val error = connectionTestEndpointError(endpoint)
                    if (error != null) result = error
                    else {
                        saving = true
                        onSave(
                            draft,
                            { saving = false; result = "已保存" },
                            { message -> saving = false; result = message }
                        )
                    }
                },
                enabled = !saving && endpoint.isNotBlank() && validation == null,
                modifier = Modifier.testTag("service-save")
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
            ServiceConnectionTest(draft)
        }
        result?.let {
            Text(
                text = it,
                color = if (it == "已保存") DesignTokens.Emerald else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    SettingsContentFrame(embedded, Modifier.testTag("cloud-connection-settings")) {
        if (embedded) {
            SettingsDisclosure(
                title = "云端分析接口",
                value = if (config.endpoint.isBlank()) "未配置" else "已配置",
                tag = "settings-endpoint-options",
                content = editor
            )
        } else {
            SectionTitle("云端分析接口")
            editor()
        }
    }
}

@Composable
internal fun SwissCaptureSettings(
    permission: Boolean,
    enabled: Boolean,
    diagnostics: CaptureStatus,
    selectedAppCount: Int,
    audioState: AudioUiState,
    isAudioPaused: Boolean,
    bubbleEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onCapture: (Boolean) -> Unit,
    onChooseApps: () -> Unit,
    onBubble: (Boolean) -> Unit,
    onAudioCapture: () -> Unit,
    onStopAudio: () -> Unit,
    message: String?
) {
    SettingsActionRow(
        title = "系统授权",
        modifier = Modifier.testTag("setting_row_accessibility"),
        value = when {
            !permission -> "未授权"
            !diagnostics.connected -> "未连接"
            else -> "已就绪"
        },
        onClick = onOpenSettings
    )
    SettingsHairline()
    SettingsActionRow(
        title = "记录应用范围",
        modifier = Modifier.testTag("settings-app-scope"),
        value = "$selectedAppCount 个应用",
        onClick = onChooseApps
    )
    SettingsHairline()
    SettingsSwitchRow(
        title = "屏幕文字识别",
        checked = enabled,
        onChange = onCapture,
        modifier = Modifier.testTag("settings-capture-toggle"),
        enabled = selectedAppCount > 0 || enabled,
        status = if (selectedAppCount == 0 && !enabled) "请先选择记录应用范围" else null
    )
    message?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    SettingsHairline()
    SettingsSwitchRow(
        title = "悬浮球",
        checked = bubbleEnabled,
        onChange = onBubble,
        modifier = Modifier.testTag("monitor-switch-悬浮球"),
        status = "浏览时显示边缘实时状态胶囊"
    )
    SettingsHairline()
    val capturing = audioState.task?.phase == AudioTaskPhase.CAPTURING
    SettingsSwitchRow(
        title = "离线语音转文字",
        checked = capturing,
        onChange = { if (it) onAudioCapture() else onStopAudio() },
        modifier = Modifier.testTag("setting_row_audio_toggle"),
        enabled = capturing || (audioState.task == null && audioState.modelState.status == AudioModelStatus.READY),
        status = when {
            capturing && isAudioPaused -> "已暂停"
            capturing -> "记录中"
            audioState.task != null -> "处理中"
            audioState.modelState.status == AudioModelStatus.READY -> "就绪"
            audioState.modelState.status == AudioModelStatus.DOWNLOADING -> "下载中"
            else -> "模型未就绪"
        }
    )
}

@Composable
internal fun SwissSpeechModelSettings(
    state: AudioUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    SettingsDisclosure(
        title = "离线语音转写模型",
        value = when (state.modelState.status) {
            AudioModelStatus.READY -> "就绪"
            AudioModelStatus.DOWNLOADING -> "下载中"
            AudioModelStatus.ERROR -> "下载失败"
            AudioModelStatus.MISSING -> "未就绪"
        },
        tag = "settings-speech-model"
    ) {
        ModelPreparation(state.modelState, onDownload, onCancel, compact = true)
        if (state.modelState.status == AudioModelStatus.READY) {
            SettingsDataRow("Sherpa-ONNX", "本地语音模型已就绪")
        }
    }
}

@Composable
internal fun SettingsDataRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val valueWidth = maxWidth * .6f
        if (maxWidth / LocalDensity.current.fontScale < 260.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = value,
                    color = Ink,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = value,
                    modifier = Modifier.widthIn(max = valueWidth),
                    color = Ink,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
