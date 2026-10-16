package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.data.LocalStorageUsage
import com.emerge.app.data.STORAGE_RETENTION_DAYS
import com.emerge.app.data.formatStorageBytes
import com.emerge.app.data.parseStorageLimitGb
import com.emerge.app.data.shortensStorageRetention
import com.emerge.app.data.storageLimitGbText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LocalStorageSettingsCard(
    usage: LocalStorageUsage,
    canClear: Boolean,
    onClear: () -> Unit,
    onSaveLimit: suspend (Long) -> Unit,
    onClearThumbnails: (suspend () -> Unit)? = null,
    embedded: Boolean = false,
    onSaveRetention: (suspend (Int) -> Unit)? = null
) {
    var editingLimit by rememberSaveable { mutableStateOf<String?>(null) }
    var editingRetention by rememberSaveable { mutableStateOf(false) }
    var clearingThumbnails by remember { mutableStateOf(false) }
    var thumbnailResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val content: @Composable ColumnScope.() -> Unit = {
        Surface(
            shape = DesignTokens.InnerShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val usedValue: @Composable (Modifier) -> Unit = { modifier ->
                        StorageUsageValue(
                            label = "已用空间",
                            value = if (usage.loading) "—" else formatStorageBytes(usage.usedBytes),
                            modifier = modifier
                        )
                    }
                    val limitValue: @Composable (Modifier) -> Unit = { modifier ->
                        StorageUsageValue(
                            label = "容量上限",
                            value = "${storageLimitGbText(usage.limitBytes)} GB",
                            modifier = modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .testTag("storage-limit")
                                .clickable(role = Role.Button) { editingLimit = storageLimitGbText(usage.limitBytes) },
                            editable = true
                        )
                    }
                    if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                val progressRatio = if (usage.loading || usage.limitBytes <= 0) 0f
                else (usage.usedBytes.toDouble() / usage.limitBytes).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("storage-usage-progress"),
                    color = if (progressRatio > 0.85f) DesignTokens.Rose else Ink,
                    trackColor = DesignTokens.Border
                )
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = onSaveRetention != null, role = Role.Button) { editingRetention = true }
                        .heightIn(min = 48.dp)
                        .testTag("storage-retention"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("保留周期", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text(storageRetentionLabel(usage.retentionDays), color = Ink,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    if (onSaveRetention != null) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
                    }
                }
            }
        }

        usage.status?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        onClearThumbnails?.let { clear ->
            SettingsHairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !clearingThumbnails, role = Role.Button) {
                        clearingThumbnails = true
                        scope.launch {
                            try {
                                clear()
                                thumbnailResult = "已清理并关闭缩略图"
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                thumbnailResult = "清理失败，请重试"
                            } finally {
                                clearingThumbnails = false
                            }
                        }
                    }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp)
                    .testTag("clear-thumbnails"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (clearingThumbnails) "清理中…" else "清理并关闭缩略图",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (clearingThumbnails) Muted else Ink
                )
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
            }
            thumbnailResult?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        SettingsHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canClear, role = Role.Button, onClick = onClear)
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "清空全部记录",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (canClear) MaterialTheme.colorScheme.error else Muted
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (canClear) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else Muted
            )
        }
    }
    SettingsContentFrame(embedded, Modifier.testTag("local-storage")) {
        if (!embedded) SectionTitle("本地存储空间")
        content()
    }
    editingLimit?.let { draft ->
        StorageLimitDialog(usage, initialDraft = draft, onDismiss = { editingLimit = null }, onSave = onSaveLimit)
    }
    if (editingRetention && onSaveRetention != null) {
        StorageRetentionDialog(usage, onDismiss = { editingRetention = false }, onSave = onSaveRetention)
    }
}

@Composable
private fun StorageUsageValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    editable: Boolean = false
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            if (editable) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(15.dp), tint = Muted)
            }
        }
        Text(
            text = value,
            color = Ink,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StorageLimitDialog(
    usage: LocalStorageUsage,
    initialDraft: String,
    onDismiss: () -> Unit,
    onSave: suspend (Long) -> Unit
) {
    var draft by rememberSaveable(initialDraft) { mutableStateOf(initialDraft) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val bytes = parseStorageLimitGb(draft)
    val needsCleanup = bytes != null && !usage.loading && bytes < usage.usedBytes
    val scope = rememberCoroutineScope()
    PojianDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("容量上限", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; saveError = null },
                    modifier = Modifier.fillMaxWidth().testTag("storage-limit-input"),
                    label = { Text("容量上限（GB）") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = bytes == null,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                    supportingText = if (bytes == null) ({ Text("请输入不小于 0.1 的 GB 数值") }) else null
                )
                if (needsCleanup) StorageCleanupWarning("超出上限的最早记录将被删除。")
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            StorageDialogActions(
                enabled = bytes != null && !saving && !usage.loading,
                saving = saving,
                needsCleanup = needsCleanup,
                onDismiss = onDismiss,
                onSave = save@{
                    val limit = bytes ?: return@save
                    saving = true
                    scope.launch {
                        try {
                            onSave(limit)
                            onDismiss()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            saveError = "保存失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                }
            )
        }
    )
}

private fun storageRetentionLabel(days: Int): String = if (days == 0) "按容量保留" else "${days}天"

@Composable
private fun StorageRetentionDialog(usage: LocalStorageUsage, onDismiss: () -> Unit, onSave: suspend (Int) -> Unit) {
    var draft by rememberSaveable { mutableIntStateOf(usage.retentionDays) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val needsCleanup = shortensStorageRetention(usage.retentionDays, draft)
    val scope = rememberCoroutineScope()
    PojianDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("保留周期", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    STORAGE_RETENTION_DAYS.forEach { days ->
                        val selected = draft == days
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .selectable(selected, enabled = !saving, role = Role.RadioButton,
                                onClick = { draft = days; saveError = null })
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("storage-retention-option-$days"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null, enabled = !saving)
                            Text(storageRetentionLabel(days), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                if (needsCleanup) StorageCleanupWarning("超出保留周期的记录将被删除。")
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            StorageDialogActions(enabled = !saving && !usage.loading && draft != usage.retentionDays,
                saving = saving, needsCleanup = needsCleanup, onDismiss = onDismiss,
                onSave = {
                    saving = true
                    scope.launch {
                        try {
                            onSave(draft)
                            onDismiss()
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            saveError = "保存失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                })
        }
    )
}

@Composable
private fun StorageCleanupWarning(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StorageDialogActions(enabled: Boolean, saving: Boolean, needsCleanup: Boolean,
    onDismiss: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PojianSecondaryButton(onClick = onDismiss, enabled = !saving,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
        PojianButton(onClick = onSave, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (needsCleanup) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (needsCleanup) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)) {
            Text(if (saving) "保存中…" else if (needsCleanup) "保存并清理" else "保存")
        }
    }
}
