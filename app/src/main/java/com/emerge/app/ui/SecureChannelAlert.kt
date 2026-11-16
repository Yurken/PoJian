package com.emerge.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.emerge.app.monitoring.SecureChannelTelemetry

/** Acknowledging the alert never resets a checkpoint or re-enables a rejected request. */
@Composable
internal fun SecureChannelAlert() {
    val state by SecureChannelTelemetry.state.collectAsState()
    val message = state.blocked
    var acknowledged by remember(message) { mutableStateOf(false) }
    if (message != null && !acknowledged) AlertDialog(onDismissRequest = {},
        modifier = Modifier.testTag("security-blocked-dialog"),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        title = { Text("安全阻断", color = MaterialTheme.colorScheme.onErrorContainer) },
        text = { Text(message, color = MaterialTheme.colorScheme.onErrorContainer) },
        confirmButton = { PojianTextButton(onClick = { acknowledged = true }) { Text("知道了") } })
}
