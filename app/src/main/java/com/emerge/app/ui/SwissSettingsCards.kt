package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.data.LocalStorageUsage
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
    updateContent: (@Composable () -> Unit)? = null
) {
    var cloudToggleError by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-settings-screen"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("confidential-computing") { ConfidentialComputingCard() }
        item("local-storage") {
            LocalStorageSettingsCard(storageUsage, canClear, onClear, onSaveStorageLimit, onClearThumbnails)
        }
        item("cloud-connection") { CloudConnectionSettingsCard(cloudConfig, onSaveCloud) }
        item("privacy-alerts") {
            WhiteCard {
                MonitorSettingsSection(monitorConfig, onMonitorChange, requestCount, frameCount,
                    onCloud = {}, onCaptureTools = onCaptureTools, cloudEnabled = cloudConfig.enabled,
                    onCloudEnabledChange = { value -> onSaveCloud(cloudConfig.copy(enabled = value),
                        { cloudToggleError = null }, { cloudToggleError = it }) })
                cloudToggleError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        }
        item("privacy-status") { LocalPrivacyStatusCard() }
        item("thumbnails") { LocalThumbnailControl() }
        item("reports") {
            WhiteCard {
                SectionTitle("浏览报告")
                PojianSecondaryButton(onClick = onReports, modifier = Modifier.fillMaxWidth().testTag("settings-reports")) {
                    Text("预览报告")
                }
            }
        }
        audioSettingsContent?.let { content -> item("audio-settings") { WhiteCard { content() } } }
        transcriptContent?.let { content -> item("transcripts") { content() } }
        updateContent?.let { content -> item("update") { content() } }
    }
}

/** A verification screen cannot manufacture an attestation or certify operator access. */
@Composable
internal fun ConfidentialComputingCard() {
    WhiteCard(Modifier.testTag("tee-verification")) {
        SectionTitle("可验证机密计算")
        SettingsDataRow("TEE 远程证明", "未配置")
        SettingsDataRow("RFC 9334 / Attested TLS", "未接入")
        SettingsDataRow("Enclave 度量指纹", "—")
        SettingsDataRow("服务运营方明文权限", "未验证")
        SettingsDataRow("密钥绑定", "未验证")
        PojianSecondaryButton(onClick = {}, enabled = false,
            modifier = Modifier.fillMaxWidth().testTag("tee-reverify")) { Text("重新验证远程证明与密钥绑定") }
    }
}

@Composable
internal fun LocalPrivacyStatusCard() {
    WhiteCard(Modifier.testTag("privacy-status")) {
        SectionTitle("隐私安全")
        SettingsDataRow("密码与输入框", "自动跳过")
        SettingsDataRow("支付页面识别", "未接入")
        SettingsDataRow("保存的令牌与任务队列", "AES-GCM")
        SettingsDataRow("本地数据库加密", "未启用")
    }
}

@Composable
internal fun CloudConnectionSettingsCard(config: TextServiceConfig,
    onSave: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit) {
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var token by remember(config.endpoint, config.token) { mutableStateOf(config.token) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val validation = if (endpoint.isNotBlank()) connectionTestEndpointError(endpoint) else null
    val draft = TextServiceConfig(config.enabled, endpoint, token)
    WhiteCard(Modifier.testTag("cloud-connection-settings")) {
        SectionTitle("云端分析接口")
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
}

@Composable
internal fun SettingsDataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Ink, style = MaterialTheme.typography.bodySmall)
    }
}
