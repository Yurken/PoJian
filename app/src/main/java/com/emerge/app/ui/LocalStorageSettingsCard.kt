package com.emerge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.emerge.app.data.LocalStorageUsage
import com.emerge.app.data.formatStorageBytes
import com.emerge.app.data.parseStorageLimitGb
import com.emerge.app.data.storageLimitGbText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LocalStorageSettingsCard(usage: LocalStorageUsage, canClear: Boolean, onClear: () -> Unit,
    onSaveLimit: suspend (Long) -> Unit, onClearThumbnails: (suspend () -> Unit)? = null) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var clearingThumbnails by remember { mutableStateOf(false) }
    var thumbnailResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    WhiteCard(Modifier.testTag("local-storage")) {
        SectionTitle("本地存储空间")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("已用空间", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text(if (usage.loading) "—" else formatStorageBytes(usage.usedBytes), color = Ink,
                style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        LinearProgressIndicator(progress = { if (usage.loading || usage.limitBytes <= 0) 0f
            else (usage.usedBytes.toDouble() / usage.limitBytes).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).testTag("storage-usage-progress"),
            color = Ink, trackColor = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("storage-limit")
            .clickable(role = Role.Button) { editing = true }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("容量上限", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text("${storageLimitGbText(usage.limitBytes)} GB", color = Ink, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("保留周期", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text("按容量保留", color = Ink, style = MaterialTheme.typography.bodyMedium)
        }
        usage.status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        onClearThumbnails?.let { clear ->
            PojianSecondaryButton(onClick = {
                clearingThumbnails = true
                scope.launch {
                    try { clear(); thumbnailResult = "已清理并关闭缩略图" }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        thumbnailResult = "清理失败，请重试"
                    } finally { clearingThumbnails = false }
                }
            }, enabled = !clearingThumbnails, modifier = Modifier.fillMaxWidth().testTag("clear-thumbnails")) {
                Text(if (clearingThumbnails) "清理中…" else "清理并关闭缩略图")
            }
            thumbnailResult?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        PojianTextButton(onClick = onClear, enabled = canClear,
            modifier = Modifier.heightIn(min = 48.dp).align(Alignment.Start)) {
            Text("清空全部记录", color = if (canClear) MaterialTheme.colorScheme.error else Muted)
        }
    }
    if (editing) StorageLimitDialog(usage, onDismiss = { editing = false }, onSave = onSaveLimit)
}

@Composable
private fun StorageLimitDialog(usage: LocalStorageUsage, onDismiss: () -> Unit, onSave: suspend (Long) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(storageLimitGbText(usage.limitBytes)) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val bytes = parseStorageLimitGb(draft)
    val needsCleanup = bytes != null && !usage.loading && bytes < usage.usedBytes
    val scope = rememberCoroutineScope()
    PojianDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("容量上限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PojianTextField(draft, { draft = it; saveError = null },
                    modifier = Modifier.fillMaxWidth().testTag("storage-limit-input"),
                    label = { Text("容量上限（GB）") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = bytes == null,
                    supportingText = if (bytes == null) ({ Text("请输入不小于 0.1 的 GB 数值") }) else null)
                if (needsCleanup) Text("超出上限的最早记录将被删除。", style = MaterialTheme.typography.bodyMedium)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            PojianButton(enabled = bytes != null && !saving && !usage.loading, onClick = {
                val limit = bytes ?: return@PojianButton
                saving = true
                scope.launch {
                    try {
                        onSave(limit)
                        onDismiss()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        saveError = "保存失败，请重试"
                    } finally { saving = false }
                }
            }) { Text(if (saving) "保存中…" else if (needsCleanup) "保存并清理" else "保存") }
        },
        dismissButton = { PojianTextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } })
}
