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
    onSaveLimit: suspend (Long) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    WhiteCard(Modifier.testTag("local-storage")) {
        SectionTitle("本地存储", help = "按容量上限保留记录，超出后删除最早记录。删除后分析同步更新，导出文件需自行删除。\n\n浏览目的会保留；浏览回顾重启后需重新生成。音频回顾随转写保存，修订后需重新分析。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("已用空间", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text(if (usage.loading) "—" else formatStorageBytes(usage.usedBytes), color = Ink,
                style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("storage-limit")
            .clickable(role = Role.Button) { editing = true }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("容量上限", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text("${storageLimitGbText(usage.limitBytes)} GB", color = Ink, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
        }
        usage.status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
