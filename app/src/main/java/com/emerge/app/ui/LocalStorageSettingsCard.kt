package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
internal fun LocalStorageSettingsCard(
    usage: LocalStorageUsage,
    canClear: Boolean,
    onClear: () -> Unit,
    onSaveLimit: suspend (Long) -> Unit,
    onClearThumbnails: (suspend () -> Unit)? = null,
    embedded: Boolean = false
) {
    var editingLimit by rememberSaveable { mutableStateOf<String?>(null) }
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("保留周期", color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text("按容量保留", color = Ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 2 else 4
            val presets = listOf(
                "256 MB" to 256_000_000L,
                "512 MB" to 512_000_000L,
                "1 GB" to 1_000_000_000L,
                "2 GB" to 2_000_000_000L
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DesignTokens.InnerShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .border(1.dp, DesignTokens.Border, DesignTokens.InnerShape)
                    .padding(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                presets.chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        row.forEach { (label, bytes) ->
                            val isSelected = usage.limitBytes == bytes
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) DesignTokens.Ink else Color.Transparent)
                                    .semantics { selected = isSelected }
                                    .clickable(role = Role.Button) { editingLimit = storageLimitGbText(bytes) }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) DesignTokens.Surface else DesignTokens.SecondaryText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
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
        title = { Text("容量上限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PojianTextField(
                    value = draft,
                    onValueChange = { draft = it; saveError = null },
                    modifier = Modifier.fillMaxWidth().testTag("storage-limit-input"),
                    label = { Text("容量上限（GB）") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = bytes == null,
                    supportingText = if (bytes == null) ({ Text("请输入不小于 0.1 的 GB 数值") }) else null
                )
                if (needsCleanup) Text("超出上限的最早记录将被删除。", style = MaterialTheme.typography.bodyMedium)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            PojianButton(
                enabled = bytes != null && !saving && !usage.loading,
                onClick = {
                    val limit = bytes ?: return@PojianButton
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
            ) {
                Text(if (saving) "保存中…" else if (needsCleanup) "保存并清理" else "保存")
            }
        },
        dismissButton = {
            PojianTextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        }
    )
}
