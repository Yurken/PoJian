package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.data.LocalStorageUsage
import com.emerge.app.data.formatStorageBytes
import com.emerge.app.data.parseStorageLimitGb
import com.emerge.app.data.storageLimitGbText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LocalStorageSettingsCard(usage: LocalStorageUsage, canClear: Boolean, onClear: () -> Unit,
    onSaveLimit: suspend (Long) -> Unit, onClearThumbnails: (suspend () -> Unit)? = null,
    embedded: Boolean = false) {
    var editingLimit by rememberSaveable { mutableStateOf<String?>(null) }
    var clearingThumbnails by remember { mutableStateOf(false) }
    var thumbnailResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val content: @Composable ColumnScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val usedValue: @Composable (Modifier) -> Unit = { modifier ->
                StorageUsageValue("已用空间", if (usage.loading) "—" else formatStorageBytes(usage.usedBytes), modifier)
            }
            val limitValue: @Composable (Modifier) -> Unit = { modifier ->
                StorageUsageValue("容量上限", "${storageLimitGbText(usage.limitBytes)} GB",
                    modifier.heightIn(min = 48.dp).testTag("storage-limit")
                        .clickable(role = Role.Button) { editingLimit = storageLimitGbText(usage.limitBytes) },
                    editable = true)
            }
            if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    usedValue(Modifier.fillMaxWidth())
                    limitValue(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    usedValue(Modifier.weight(1f))
                    limitValue(Modifier.weight(1f))
                }
            }
        }
        LinearProgressIndicator(progress = { if (usage.loading || usage.limitBytes <= 0) 0f
            else (usage.usedBytes.toDouble() / usage.limitBytes).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp).testTag("storage-usage-progress"),
            color = Ink, trackColor = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 2 else 4
            val presets = listOf("256 MB" to 256_000_000L, "512 MB" to 512_000_000L,
                "1 GB" to 1_000_000_000L, "2 GB" to 2_000_000_000L)
            Column(Modifier.fillMaxWidth().clip(DesignTokens.InnerShape)
                .border(1.dp, DesignTokens.Border, DesignTokens.InnerShape)) {
                presets.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (label, bytes) ->
                            val isSelected = usage.limitBytes == bytes
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .background(if (isSelected) DesignTokens.Ink else DesignTokens.Surface)
                                .semantics { selected = isSelected }
                                .clickable(role = Role.Button) { editingLimit = storageLimitGbText(bytes) }
                                .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (isSelected) DesignTokens.Surface else DesignTokens.SecondaryText,
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("保留周期", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text("按容量保留", color = Ink, style = MaterialTheme.typography.bodyMedium)
        }
        usage.status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        onClearThumbnails?.let { clear ->
            HorizontalDivider(color = DesignTokens.Border)
            PojianTextButton(onClick = {
                clearingThumbnails = true
                scope.launch {
                    try { clear(); thumbnailResult = "已清理并关闭缩略图" }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        thumbnailResult = "清理失败，请重试"
                    } finally { clearingThumbnails = false }
                }
            }, enabled = !clearingThumbnails, modifier = Modifier.fillMaxWidth().testTag("clear-thumbnails"),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                Text(if (clearingThumbnails) "清理中…" else "清理并关闭缩略图", Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
            }
            thumbnailResult?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        PojianTextButton(onClick = onClear, enabled = canClear,
            modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            Text("清空全部记录", Modifier.weight(1f), color = if (canClear) MaterialTheme.colorScheme.error else Muted)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
        }
    }
    SettingsContentFrame(embedded, Modifier.testTag("local-storage")) {
        if (!embedded) SectionTitle("本地存储空间")
        content()
    }
    editingLimit?.let { draft ->
        StorageLimitDialog(usage, initialDraft = draft, onDismiss = { editingLimit = null }, onSave = onSaveLimit)
    }
}

@Composable
private fun StorageUsageValue(label: String, value: String, modifier: Modifier = Modifier, editable: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            if (editable) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = Muted)
        }
        Text(value, color = Ink, fontSize = 28.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StorageLimitDialog(usage: LocalStorageUsage, initialDraft: String,
    onDismiss: () -> Unit, onSave: suspend (Long) -> Unit) {
    var draft by rememberSaveable(initialDraft) { mutableStateOf(initialDraft) }
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
