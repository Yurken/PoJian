package com.emerge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emerge.app.audioanalysis.EndpointPolicy
import com.emerge.app.audioanalysis.RemoteServiceProbe
import com.emerge.app.audioanalysis.ServiceProbeResult
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.monitoring.SecureChannelConfig
import com.emerge.app.monitoring.SecureChannelTelemetry
import com.emerge.app.monitoring.SecureSemanticClient
import com.emerge.app.monitoring.SecurityAttestationException
import kotlinx.coroutines.CancellationException

@Composable
internal fun ServiceConnectionTest(config: TextServiceConfig) {
    val context = LocalContext.current
    val security by SecureChannelTelemetry.state.collectAsState()
    val secure = SecureChannelConfig.applies(config)
    val telemetryCurrent = security.serviceIdentity == SecureChannelConfig.identity(config)
    var attempt by remember(config.endpoint, config.token) { mutableIntStateOf(0) }
    var result by remember(config.endpoint, config.token) { mutableStateOf<ServiceProbeResult?>(null) }
    val testing = attempt > 0 && result == null
    // Leaving settings or changing credentials cancels the request and discards its result.
    LaunchedEffect(config.endpoint, config.token, attempt) {
        if (attempt > 0) result = connectionTestEndpointError(config.endpoint)?.let { ServiceProbeResult(it) }
            ?: if (secure) try {
                SecureSemanticClient(context).probe(config)
                ServiceProbeResult("连接与验签通过")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ServiceProbeResult(if (error is SecurityAttestationException) error.message.orEmpty() else "安全服务连接失败")
            } else RemoteServiceProbe.check(config, allowLocalHttp = false)
    }
    PojianTextButton(enabled = !testing, modifier = Modifier.testTag("service-connection-test"), onClick = {
        result = null
        attempt++
    }) { Text(if (testing) "测试中…" else "连接测试") }
    Column(Modifier.fillMaxWidth().testTag("secure-channel-status"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConnectionStatus("信道协议", if (secure) "X25519 + AES-256-GCM" else "HTTPS")
        if (secure) {
            ConnectionStatus("程序度量", security.measurement.takeIf { telemetryCurrent }.orEmpty().take(16).ifEmpty { "尚未验签" })
            ConnectionStatus("验签状态", if (telemetryCurrent && security.verified) "已通过白名单验签" else "未通过")
            ConnectionStatus("状态链", if (telemetryCurrent) "v${security.version} ${security.stateHash.take(12)}" else "尚未同步")
        }
        ConnectionStatus("端侧脱敏", "${security.piiCount} 项")
        security.blocked?.takeIf { telemetryCurrent }?.let { message ->
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().testTag("security-blocked-banner")) {
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    result?.let { response ->
        Column(Modifier.fillMaxWidth().testTag("service-connection-result")
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(response.message, style = MaterialTheme.typography.bodySmall,
                color = if (response.capabilities == null && !(secure && telemetryCurrent && security.verified)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            response.capabilities?.let { capabilities ->
                ConnectionStatus("监测接口", if (capabilities.monitor) "支持" else "不支持")
                ConnectionStatus("文字模型", if (capabilities.text) "已配置" else "未配置")
                ConnectionStatus("画面模型", if (capabilities.vision) "已配置" else "未配置")
                ConnectionStatus("事实检索", if (capabilities.retrieval) "已配置" else "未配置")
            }
        }
    }
}

/** This user-facing connection check only accepts HTTPS, including in debug builds. */
internal fun connectionTestEndpointError(endpoint: String): String? {
    val scheme = runCatching { java.net.URI(endpoint.trim()).scheme }.getOrNull()
    if (!scheme.equals("https", ignoreCase = true)) return "仅支持 HTTPS"
    return runCatching { EndpointPolicy.validate(endpoint, allowLocalHttp = false) }
        .exceptionOrNull()?.let { "服务地址格式不正确" }
}

@Composable
private fun ConnectionStatus(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
