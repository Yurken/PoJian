package com.emerge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation-only values; the capture and storage layers remain independent of Compose. */
enum class AudioModelStatus { MISSING, DOWNLOADING, READY, ERROR }

data class AudioModelUiState(
    val status: AudioModelStatus = AudioModelStatus.MISSING,
    val progress: Float? = null,
    val detail: String? = null
)

enum class AudioTaskPhase { PREPARING, CAPTURING, TRANSCRIBING }

data class AudioTaskUiState(
    val phase: AudioTaskPhase,
    val title: String = "",
    val progress: Float? = null,
    val partialText: String = "",
    val elapsedMillis: Long = 0
)

data class AudioUiState(
    val modelState: AudioModelUiState = AudioModelUiState(),
    val task: AudioTaskUiState? = null,
    val sessionCount: Int = 0,
    val message: String? = null,
    val cloudLabel: String = "未启用云端分析"
)

data class AudioSegmentUi(
    val id: Long,
    val startMillis: Long,
    val endMillis: Long,
    val originalText: String,
    val correctedText: String? = null
)

data class AudioSessionUi(
    val id: String,
    val title: String,
    val sourceLabel: String,
    val createdAt: Long,
    val durationMillis: Long,
    val segments: List<AudioSegmentUi> = emptyList(),
    val statusLabel: String = "已转写",
    val errorMessage: String? = null,
    val analysisLabel: String? = null,
    val analysisSummary: String? = null,
    val claims: List<AudioClaimUi> = emptyList(),
    val analyzing: Boolean = false
)

data class AudioClaimUi(
    val claim: String,
    val evidence: String? = null,
    val relation: String = "",
    val novelty: String = "",
    val historyLabel: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null
)

@Composable
fun AudioEntryCard(
    state: AudioUiState,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelTask: () -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelDownload: () -> Unit = onCancelTask,
    compact: Boolean = false,
    onPauseResume: (() -> Unit)? = null,
    isPaused: Boolean = false
) {
    val canStart = state.modelState.status == AudioModelStatus.READY && state.task == null
    WhiteCard(modifier.testTag("audio-entry")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("播放声音", style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.SansSerif, color = Ink, modifier = Modifier.weight(1f))
            AudioStatusLabel(when {
                state.task?.phase == AudioTaskPhase.CAPTURING && isPaused -> "已暂停"
                state.task?.phase == AudioTaskPhase.CAPTURING -> "记录中"
                state.task?.phase == AudioTaskPhase.TRANSCRIBING -> "转写中"
                state.task != null -> "准备中"
                state.modelState.status == AudioModelStatus.READY -> "本地转写"
                state.modelState.status == AudioModelStatus.DOWNLOADING -> "下载中"
                else -> "待准备"
            })
        }
        if (state.task == null || state.modelState.status != AudioModelStatus.READY) {
            ModelPreparation(state.modelState, onDownloadModel, onCancelDownload, compact = true)
        }
        state.task?.let {
            AudioTaskProgress(it, onCancelTask, compact, onPauseResume, isPaused)
        }
        if (state.task == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCapture, enabled = canStart, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("audio-capture"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                    Text("记录播放声音")
                }
                OutlinedButton(onClick = onImport, enabled = canStart, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("audio-import"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                    Text("导入音频")
                }
            }
        }
        state.message?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetadataText("不保留原声", Modifier.weight(1f))
            TextButton(onClick = onOpenRecords, modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (state.sessionCount > 0) "音频记录 ${state.sessionCount} ›" else "音频记录 ›")
            }
        }
    }
}

/** Dedicated recording destination; the same state and actions also power the overview card. */
@Composable
fun RecordingScreen(
    state: AudioUiState,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelTask: () -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelDownload: () -> Unit = onCancelTask,
    onPauseResume: (() -> Unit)? = null,
    isPaused: Boolean = false
) {
    LazyColumn(modifier.fillMaxSize().testTag("recording-screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("声音记录", style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.SansSerif, color = Ink,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        item {
            AudioEntryCard(state, onImport, onCapture, onDownloadModel, onCancelTask,
                onOpenRecords, onCancelDownload = onCancelDownload,
                onPauseResume = onPauseResume, isPaused = isPaused)
        }
        item {
            ExpandableNote("录制与导入说明",
                "记录所选应用播放的声音，单次最长 5 分钟；也可以导入音频或视频音轨。" +
                    "每次录制需要系统授权，部分应用不允许录制。转写在本机完成，只保存文字和时间。")
        }
    }
}

@Composable
private fun AudioStatusLabel(label: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

@Composable
private fun ModelPreparation(state: AudioModelUiState, onDownload: () -> Unit, onCancel: () -> Unit,
    compact: Boolean = false) {
    when (state.status) {
        AudioModelStatus.READY -> if (!compact) MetadataText("本地语音模型已就绪")
        AudioModelStatus.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("正在下载语音模型", style = MaterialTheme.typography.bodyMedium,
                    color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("audio-cancel-download")) { Text("取消下载") }
            }
            AudioProgress(state.progress, "模型下载进度")
            state.detail?.takeIf { it.isNotBlank() }?.let { MetadataText(it) }
        }
        AudioModelStatus.MISSING, AudioModelStatus.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.status == AudioModelStatus.ERROR) "模型下载失败" else "首次使用需下载模型",
                    style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("audio-download-model")) {
                    Text(if (state.status == AudioModelStatus.ERROR) "重新下载" else "下载模型")
                }
            }
            state.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (state.status == AudioModelStatus.ERROR) MaterialTheme.colorScheme.error else Muted)
            }
            if (!compact && state.detail.isNullOrBlank()) MetadataText("下载后即可在本机转写。")
        }
    }
}

@Composable
private fun AudioTaskProgress(task: AudioTaskUiState, onStop: () -> Unit, compact: Boolean,
    onPauseResume: (() -> Unit)?, isPaused: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("audio-task"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title.ifBlank {
                when (task.phase) {
                    AudioTaskPhase.PREPARING -> "正在准备"
                    AudioTaskPhase.CAPTURING -> "正在记录播放声音"
                    AudioTaskPhase.TRANSCRIBING -> "正在转写音频"
                }
            }, style = MaterialTheme.typography.bodyMedium, color = Ink,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (task.phase == AudioTaskPhase.CAPTURING || task.elapsedMillis > 0) {
                Text(audioTimestamp(task.elapsedMillis), color = Accent,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                if (task.phase == AudioTaskPhase.CAPTURING) MetadataText("/ 05:00")
            }
        }
        if (task.phase == AudioTaskPhase.CAPTURING) {
            // Capture has no amplitude measurements. Show elapsed time against its actual limit.
            LinearProgressIndicator(progress = { (task.elapsedMillis / 300_000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "录制进度，已记录 ${audioTimestamp(task.elapsedMillis)}，最长 5 分钟"
                })
        } else AudioProgress(task.progress, "音频处理进度")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task.phase == AudioTaskPhase.CAPTURING && onPauseResume != null) {
                Button(onClick = onPauseResume, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("audio-pause-resume")) {
                    Text(if (isPaused) "继续记录" else "暂停记录")
                }
            }
            OutlinedButton(onClick = onStop, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("audio-stop-task")) {
                Text(if (task.phase == AudioTaskPhase.CAPTURING) "停止记录" else "停止处理")
            }
        }
        if (task.partialText.isNotBlank()) {
            if (compact) Surface(shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(task.partialText, color = Muted, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(10.dp).testTag("audio-live-transcript"))
            } else {
                MetadataText("实时转写")
                QuoteBlock(task.partialText, Modifier.testTag("audio-live-transcript"))
            }
        } else MetadataText(if (task.phase == AudioTaskPhase.CAPTURING)
            "等待播放声音…" else "等待识别结果…")
    }
}

@Composable
private fun AudioProgress(progress: Float?, label: String) {
    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()
        .semantics { contentDescription = label })
    else {
        val fraction = progress.coerceIn(0f, 1f)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f)
                .semantics { contentDescription = label })
            MetadataText("${(fraction * 100).toInt()}%")
        }
    }
}

@Composable
fun AudioRecordsEntry(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
        .heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("音频记录", color = Ink, style = MaterialTheme.typography.titleMedium)
            MetadataText(if (count > 0) "$count 段转写 · 查看与修订" else "导入音频或记录播放声音",
                Modifier.padding(top = 4.dp))
        }
        Text("查看 ›", color = Accent, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AudioRecordsScreen(
    sessions: List<AudioSessionUi>,
    onSession: (AudioSessionUi) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    canImport: Boolean = true
) {
    LazyColumn(modifier.fillMaxSize().testTag("audio-record-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ 返回") }
            Text("音频记录", style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.SansSerif, color = Ink)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MetadataText("本机已保存 ${sessions.size} 段转写", Modifier.weight(1f))
                TextButton(onClick = onImport, enabled = canImport, modifier = Modifier.heightIn(min = 48.dp)) { Text("导入音频") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (sessions.isEmpty()) item {
            WhiteCard {
                SectionTitle("还没有音频记录")
                MetadataText("导入音频，或从录制页开始记录播放声音。")
                OutlinedButton(onClick = onImport, enabled = canImport,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("导入音频")
                }
            }
        }
        items(sessions.sortedByDescending { it.createdAt }, key = { it.id }) { session ->
            WhiteCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看音频转写") { onSession(session) }
                .testTag("audio-session-${session.id}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(session.title, style = MaterialTheme.typography.titleMedium, color = Ink,
                        modifier = Modifier.weight(1f).padding(end = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AudioStatusLabel(session.statusLabel)
                }
                MetadataText("${audioDate(session.createdAt)} · ${session.sourceLabel} · 音频 ${audioTimestamp(session.durationMillis)}")
                val excerpt = session.segments.joinToString(" ") { it.correctedText ?: it.originalText }
                Text(excerpt.ifBlank { session.errorMessage ?: "尚未识别到文字" }, color = if (excerpt.isBlank()) Muted else Ink,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                session.analysisLabel?.let { MetadataText(it) }
                Text("查看转写 ›", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSessionDetailSheet(
    session: AudioSessionUi,
    onDismiss: () -> Unit,
    onSaveSegment: (Long, String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onAnalyze: () -> Unit = {},
    message: String? = null
) {
    var editingId by rememberSaveable(session.id) { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    var confirmDelete by rememberSaveable(session.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.weight(1f).testTag("audio-detail-segments"),
                contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(session.title, style = MaterialTheme.typography.headlineSmall, color = Ink)
                        MetadataText("${session.sourceLabel} · ${audioDate(session.createdAt)}")
                        MetadataText("音频时长 ${audioTimestamp(session.durationMillis)} · ${session.segments.size} 段转写 · ${session.statusLabel}")
                        ExpandableNote("转写说明", "音频长度不代表实际观看或收听时长。识别可能有误，可以对照语境修订文字。")
                        session.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium) }
                        session.analysisLabel?.let { MetadataText(it) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item(key = "analysis") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("音频回顾")
                        Button(onClick = onAnalyze, enabled = session.segments.isNotEmpty() && !session.analyzing,
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("audio-analyze-session")) {
                            Text(if (session.analyzing) "正在分析" else if (session.analysisSummary != null || session.claims.isNotEmpty())
                                "更新音频回顾" else "生成音频回顾")
                        }
                        if (session.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth()
                            .semantics { contentDescription = "音频回顾分析进度" })
                        message?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().testTag("audio-detail-message")
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        session.analysisSummary?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Ink, style = MaterialTheme.typography.bodyMedium)
                        }
                        session.claims.forEach { claim ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(claim.claim, color = Ink, style = MaterialTheme.typography.titleSmall)
                                if (!claim.evidence.isNullOrBlank()) {
                                    MetadataText(if (claim.startMillis != null && claim.endMillis != null)
                                        "依据出处 · 音频 ${audioTimestamp(claim.startMillis)} — ${audioTimestamp(claim.endMillis)}"
                                        else "来自转写的依据")
                                    QuoteBlock(claim.evidence)
                                } else MetadataText("没有识别到可回查的依据")
                                val comparison = listOf(claim.relation, claim.novelty).filter { it.isNotBlank() }.joinToString(" · ")
                                if (comparison.isNotBlank()) MetadataText(comparison)
                                claim.historyLabel?.takeIf { it.isNotBlank() }?.let { MetadataText(it) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                if (session.segments.isEmpty()) item { MetadataText("这段音频还没有识别到文字。") }
                else item(key = "segments-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SectionTitle("转写片段", "${session.segments.size} 段 · 识别可能有误，点时间段可以修订")
                    }
                }
                items(session.segments, key = { it.id }) { segment ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (editingId == segment.id || session.analyzing) Modifier
                                else Modifier.clickable(role = Role.Button, onClickLabel = "修订这段转写") {
                                    editingId = segment.id
                                    draft = segment.correctedText ?: segment.originalText
                                })
                            .testTag("audio-edit-action-${segment.id}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${audioTimestamp(segment.startMillis)} — ${audioTimestamp(segment.endMillis)}",
                                style = MaterialTheme.typography.labelLarge, color = Accent,
                                modifier = Modifier.weight(1f))
                            if (editingId != segment.id) Text("修订 ›", color = Accent,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        MetadataText("原始转写")
                        QuoteBlock(segment.originalText)
                        if (!segment.correctedText.isNullOrBlank() && segment.correctedText != segment.originalText) {
                            MetadataText("修订后（回顾时使用）")
                            QuoteBlock(segment.correctedText)
                        }
                        if (editingId == segment.id) {
                            OutlinedTextField(value = draft, onValueChange = { draft = it },
                                label = { Text("修订转写") }, minLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("audio-edit-${segment.id}"),
                                shape = RoundedCornerShape(12.dp))
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = {
                                    onSaveSegment(segment.id, draft.trim())
                                    editingId = null
                                    draft = ""
                                }, enabled = draft.isNotBlank() && !session.analyzing, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("audio-save-${segment.id}")) { Text("保存修订") }
                                TextButton(onClick = { editingId = null; draft = "" }, modifier = Modifier.align(Alignment.End)
                                    .heightIn(min = 48.dp)) { Text("取消修订") }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item(key = "delete") {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("audio-delete-session")) {
                        Text("删除这条音频记录", color = MaterialTheme.colorScheme.error)
                    }
                    MetadataText("破茧只保存转写文字和时间，不保留原声。")
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("关闭") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这条音频记录？") },
        text = { Text("这条记录的转写和修订文字将被删除。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

@Composable
fun AudioSettingsSection(
    state: AudioUiState,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onConfigureCloud: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("语音与分析")
        ModelPreparation(state.modelState, onDownloadModel, onCancelDownload)
        MetadataText("本机转写 · 只保存文字和时间")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("云端文字分析", color = Ink, style = MaterialTheme.typography.titleMedium)
                MetadataText(state.cloudLabel, Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onConfigureCloud, modifier = Modifier.heightIn(min = 48.dp)) { Text("配置 ›") }
        }
        ExpandableNote("记录播放声音的授权与边界", "每次记录播放声音都需要系统授权。部分应用不允许录制，耳机、设备和系统版本也可能影响效果；录不到声音时可以改用导入音频。\n\n" +
            "语音转写可能漏字或识别错误，音乐和环境声不一定能识别。识别出的观点和依据，请回到原内容核实。")
    }
}

internal fun audioTimestamp(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

private fun audioDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))
