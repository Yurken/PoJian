package com.emerge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emerge.app.BuildConfig
import com.emerge.app.audioanalysis.RemoteServiceProbe
import com.emerge.app.audioanalysis.ServiceProbeResult
import com.emerge.app.audioanalysis.TextServiceConfig

@Composable
internal fun ServiceConnectionTest(config: TextServiceConfig) {
    var attempt by remember(config.endpoint, config.token) { mutableIntStateOf(0) }
    var result by remember(config.endpoint, config.token) { mutableStateOf<ServiceProbeResult?>(null) }
    val testing = attempt > 0 && result == null
    // Leaving settings or changing credentials cancels the request and discards its result.
    LaunchedEffect(config.endpoint, config.token, attempt) {
        if (attempt > 0) result = RemoteServiceProbe.check(config, BuildConfig.DEBUG)
    }
    PojianTextButton(enabled = !testing, modifier = Modifier.testTag("service-connection-test"), onClick = {
        result = null
        attempt++
    }) { Text(if (testing) "测试中…" else "测试连接") }
    result?.let { response ->
        Column(Modifier.fillMaxWidth().testTag("service-connection-result")
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(response.message, style = MaterialTheme.typography.bodySmall,
                color = if (response.capabilities == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            response.capabilities?.let { capabilities ->
                ConnectionStatus("监测接口", if (capabilities.monitor) "支持" else "不支持")
                ConnectionStatus("文字模型", if (capabilities.text) "已配置" else "未配置")
                ConnectionStatus("画面模型", if (capabilities.vision) "已配置" else "未配置")
                ConnectionStatus("事实检索", if (capabilities.retrieval) "已配置" else "未配置")
            }
        }
    }
}

@Composable
private fun ConnectionStatus(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
