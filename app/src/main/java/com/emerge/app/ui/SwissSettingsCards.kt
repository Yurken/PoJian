package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
    captureSettingsContent: (@Composable () -> Unit)? = null
) {
    var cloudToggleError by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-settings-screen"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item("capture-permissions") {
            SwissSettingsSection("01", "采集与系统权限") {
                captureSettingsContent?.invoke() ?: SettingsActionRow("实时记录", onClick = onCaptureTools)
                SettingsHairline()
                SettingsDisclosure("提醒频率与隐私安全", tag = "settings-monitor-options") {
                    MonitorSettingsSection(monitorConfig, onMonitorChange, requestCount, frameCount,
                        onCloud = {}, onCaptureTools = onCaptureTools,
                        embedded = true, includeCloudControls = false, includeBubbleControl = captureSettingsContent == null)
                }
                SettingsHairline()
                LocalPrivacyStatusCard(embedded = true)
            }
        }
        item("analysis-engine") {
            SwissSettingsSection("02", "分析引擎与模型") {
                SettingsSwitchRow("云端分析", cloudConfig.enabled, { value ->
                    onSaveCloud(cloudConfig.copy(enabled = value), { cloudToggleError = null }, { cloudToggleError = it })
                }, Modifier.testTag("monitor-switch-云端分析"),
                    status = "发送文字；开启画面分析时发送采样画面")
                cloudToggleError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
                SettingsHairline()
                CloudConnectionSettingsCard(cloudConfig, embedded = true, onSave = onSaveCloud)
                audioSettingsContent?.let { SettingsHairline(); it() }
                SettingsHairline()
                ConfidentialComputingCard(embedded = true)
            }
        }
        item("storage-retention") {
            SwissSettingsSection("03", "数据与存储管理") {
                LocalStorageSettingsCard(storageUsage, canClear, onClear, onSaveStorageLimit, onClearThumbnails, embedded = true)
                SettingsHairline()
                LocalThumbnailControl(embedded = true)
                transcriptContent?.let { content ->
                    SettingsHairline()
                    SettingsDisclosure("录音转文字", tag = "settings-transcripts-options") { content() }
                }
                SettingsHairline()
                SettingsActionRow("浏览报告", Modifier.testTag("settings-reports"), "预览报告", onClick = onReports)
            }
        }
        item("system-about") {
            SwissSettingsSection("04", "系统与关于") {
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
        SettingsDataRow("RFC 9334 / Attested TLS", "未接入")
        SettingsDataRow("Enclave 度量指纹", "—")
        SettingsDataRow("服务运营方明文权限", "未验证")
        SettingsDataRow("密钥绑定", "未验证")
        PojianSecondaryButton(onClick = {}, enabled = false,
            modifier = Modifier.fillMaxWidth().testTag("tee-reverify")) { Text("重新验证远程证明与密钥绑定") }
    }
    SettingsContentFrame(embedded, Modifier.testTag("tee-verification")) {
        if (embedded) SettingsDisclosure("可验证机密计算", "未配置", "settings-tee-options", content = details)
        else { SectionTitle("可验证机密计算"); details() }
    }
}

@Composable
internal fun LocalPrivacyStatusCard(embedded: Boolean = false) {
    val details: @Composable ColumnScope.() -> Unit = {
        SettingsDataRow("密码与输入框", "自动跳过")
        SettingsDataRow("支付页面识别", "未接入")
        SettingsDataRow("保存的令牌与任务队列", "AES-GCM")
        SettingsDataRow("本地数据库加密", "未启用")
    }
    SettingsContentFrame(embedded, Modifier.testTag("privacy-status")) {
        if (embedded) SettingsDisclosure("隐私安全", tag = "settings-privacy-options", content = details)
        else { SectionTitle("隐私安全"); details() }
    }
}

@Composable
internal fun CloudConnectionSettingsCard(config: TextServiceConfig, embedded: Boolean = false,
    onSave: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit) {
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var token by remember(config.endpoint, config.token) { mutableStateOf(config.token) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val validation = if (endpoint.isNotBlank()) connectionTestEndpointError(endpoint) else null
    val draft = TextServiceConfig(config.enabled, endpoint, token)
    val editor: @Composable ColumnScope.() -> Unit = {
        PojianTextField(endpoint, { value ->
            // A credential for one service is never silently carried to another address.
            if (value.trim().trimEnd('/') != endpoint.trim().trimEnd('/')) token = ""
            endpoint = value
            result = null
        }, modifier = Modifier.fillMaxWidth().testTag("service-endpoint"),
            label = { Text("HTTPS 服务地址") }, singleLine = true, enabled = !saving,
            isError = validation != null, supportingText = validation?.let { error -> ({ Text(error) }) })
        PojianTextField(token, { token = it; result = null },
            modifier = Modifier.fillMaxWidth().testTag("service-token"),
            label = { Text("访问令牌") }, singleLine = true, enabled = !saving,
            visualTransformation = PasswordVisualTransformation())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            PojianSecondaryButton(onClick = {
                val error = connectionTestEndpointError(endpoint)
                if (error != null) result = error
                else {
                    saving = true
                    onSave(draft, { saving = false; result = "已保存" }, { message -> saving = false; result = message })
                }
            }, enabled = !saving && endpoint.isNotBlank() && validation == null,
                modifier = Modifier.testTag("service-save")) { Text(if (saving) "保存中…" else "保存") }
        }
        ServiceConnectionTest(draft)
        result?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
    SettingsContentFrame(embedded, Modifier.testTag("cloud-connection-settings")) {
        if (embedded) SettingsDisclosure("云端分析接口", if (config.endpoint.isBlank()) "未配置" else "已配置",
            tag = "settings-endpoint-options", content = editor)
        else { SectionTitle("云端分析接口"); editor() }
    }
}

@Composable
internal fun SwissCaptureSettings(permission: Boolean, enabled: Boolean, diagnostics: CaptureStatus,
    selectedAppCount: Int, audioState: AudioUiState, isAudioPaused: Boolean, bubbleEnabled: Boolean,
    onOpenSettings: () -> Unit, onCapture: (Boolean) -> Unit, onChooseApps: () -> Unit,
    onBubble: (Boolean) -> Unit, onAudioCapture: () -> Unit, onStopAudio: () -> Unit, message: String?) {
    SettingsActionRow("系统授权", Modifier.testTag("setting_row_accessibility"),
        when { !permission -> "未授权"; !diagnostics.connected -> "未连接"; else -> "已就绪" }, onClick = onOpenSettings)
    SettingsHairline()
    SettingsSwitchRow("屏幕文字识别", enabled, onCapture, Modifier.testTag("settings-capture-toggle"),
        enabled = selectedAppCount > 0 || enabled)
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    SettingsHairline()
    SettingsActionRow("记录应用范围", Modifier.testTag("settings-app-scope"), "$selectedAppCount 个应用", onClick = onChooseApps)
    SettingsHairline()
    SettingsSwitchRow("悬浮球", bubbleEnabled, onBubble, Modifier.testTag("monitor-switch-悬浮球"))
    SettingsHairline()
    val capturing = audioState.task?.phase == AudioTaskPhase.CAPTURING
    SettingsSwitchRow("离线语音转文字", capturing, { if (it) onAudioCapture() else onStopAudio() },
        Modifier.testTag("setting_row_audio_toggle"),
        enabled = capturing || (audioState.task == null && audioState.modelState.status == AudioModelStatus.READY),
        status = when {
            capturing && isAudioPaused -> "已暂停"
            capturing -> "记录中"
            audioState.task != null -> "处理中"
            audioState.modelState.status == AudioModelStatus.READY -> "就绪"
            audioState.modelState.status == AudioModelStatus.DOWNLOADING -> "下载中"
            else -> "模型未就绪"
        })
}

@Composable
internal fun SwissSpeechModelSettings(state: AudioUiState, onDownload: () -> Unit, onCancel: () -> Unit) {
    SettingsDisclosure("离线语音转写模型", when (state.modelState.status) {
        AudioModelStatus.READY -> "就绪"
        AudioModelStatus.DOWNLOADING -> "下载中"
        AudioModelStatus.ERROR -> "下载失败"
        AudioModelStatus.MISSING -> "未就绪"
    }, "settings-speech-model") {
        ModelPreparation(state.modelState, onDownload, onCancel, compact = true)
        if (state.modelState.status == AudioModelStatus.READY) SettingsDataRow("Sherpa-ONNX", "本地语音模型已就绪")
    }
}

@Composable
internal fun SettingsDataRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val valueWidth = maxWidth * .6f
        if (maxWidth / LocalDensity.current.fontScale < 260.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(value, color = Ink, style = MaterialTheme.typography.bodySmall)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, Modifier.widthIn(max = valueWidth), color = Ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}
