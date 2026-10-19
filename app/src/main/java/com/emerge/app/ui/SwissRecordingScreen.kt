package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.capture.PlatformTextCleaner
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText

/** Controls observe the existing recorder and capture scope; no separate recording state is stored. */
@Composable
@Suppress("UNUSED_PARAMETER") // Download callbacks remain source-compatible; model management lives in settings.
fun SwissRecordingScreen(
    permission: Boolean,
    enabled: Boolean,
    diagnostics: CaptureStatus,
    apps: List<CaptureApp>,
    selectedPackages: Set<String>,
    events: List<BrowseEvent>,
    report: CognitiveReport?,
    audioState: AudioUiState,
    audioSessions: List<AudioSessionUi>,
    onCapture: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onPackagesChange: (Set<String>) -> Unit,
    onChooseApps: () -> Unit,
    onImport: () -> Unit,
    onAudioCapture: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelTask: () -> Unit,
    onOpenAudioRecords: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelDownload: () -> Unit = onCancelTask,
    onPauseResume: (() -> Unit)? = null,
    isAudioPaused: Boolean = false,
    message: String? = null,
    onSettings: () -> Unit = {}
) {
    val audioCapturing = audioState.task?.phase == AudioTaskPhase.CAPTURING
    val audioCanStart = audioState.modelState.status == AudioModelStatus.READY && audioState.task == null
    val captureLabel = when {
        selectedPackages.isEmpty() -> "未选择应用"
        !permission -> "未授权"
        !diagnostics.connected -> "未连接"
        !enabled -> "已暂停"
        else -> "记录中"
    }
    val audioLabel = when {
        audioCapturing && isAudioPaused -> "已暂停"
        audioCapturing -> "记录中"
        audioState.task != null -> "处理中"
        audioState.modelState.status == AudioModelStatus.READY -> "就绪"
        audioState.modelState.status == AudioModelStatus.DOWNLOADING -> "下载中"
        audioState.modelState.status == AudioModelStatus.ERROR -> "模型下载失败"
        else -> "模型未就绪"
    }
    val active = enabled && permission && diagnostics.connected && selectedPackages.isNotEmpty()
    val statusColor = when {
        active -> DesignTokens.Emerald
        !permission || selectedPackages.isEmpty() -> DesignTokens.Amber
        else -> Muted
    }
    val audioStatusColor = when {
        audioState.task != null -> DesignTokens.Rose
        audioState.modelState.status == AudioModelStatus.READY -> DesignTokens.Emerald
        audioState.modelState.status == AudioModelStatus.DOWNLOADING -> DesignTokens.Amber
        else -> Muted
    }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-recording-screen"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("channels") {
            WhiteCard(Modifier.testTag("audio-entry")) {
                SwissSectionHeader(number = "01", title = "实时内容记录", eyebrow = "LIVE CAPTURE")
                Surface(shape = DesignTokens.InnerShape, color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.75f)),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(captureLabel, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(
                                text = if (permission && diagnostics.connected) {
                                    "无障碍服务已连接 · 仅记录下方勾选的应用"
                                } else {
                                    "未连接无障碍服务 · 需在系统设置中开启权限"
                                },
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        PojianSwitch(checked = enabled, onCheckedChange = onCapture,
                            enabled = selectedPackages.isNotEmpty() || enabled,
                            modifier = Modifier.testTag("capture-toggle")
                                .semantics { contentDescription = "屏幕文字识别" })
                    }
                }
                Surface(shape = DesignTokens.InnerShape, color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已选监测应用", style = MaterialTheme.typography.labelSmall, color = Muted)
                            Text("${selectedPackages.size} 个", style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Ink)
                        }
                        VerticalDivider(Modifier.fillMaxHeight(), color = DesignTokens.Border)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("本地留存快照", style = MaterialTheme.typography.labelSmall, color = Muted)
                            Text("${events.size} 条", style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Ink)
                        }
                    }
                }
                if (!permission || !diagnostics.connected) {
                    PojianPrimaryButton(
                        text = if (!permission) "前往系统授权" else "检查系统授权",
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("recording-system-permission"))
                }
                message?.takeIf { it.isNotBlank() }?.let { msg ->
                    Surface(shape = DesignTokens.InnerShape, color = DesignTokens.Amber.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, DesignTokens.Amber.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(msg, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309))
                    }
                }
                HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.7f), thickness = 0.75.dp)
                RecordingApplications(apps, selectedPackages, onPackagesChange, onChooseApps)
            }
        }
        item("audio-card") {
            WhiteCard(Modifier.testTag("recording-audio-card")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwissSectionHeader(number = "02", title = "本地语音转文字", eyebrow = "LOCAL ASR",
                        modifier = Modifier.weight(1f))
                    if (audioState.modelState.status != AudioModelStatus.READY) {
                        PojianIconButton(onClick = onSettings,
                            modifier = Modifier.testTag("recording-model-settings")) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Muted)
                        }
                    }
                }
                Surface(shape = DesignTokens.InnerShape, color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.75f)),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(audioStatusColor))
                        Text(audioLabel, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = Ink,
                            modifier = Modifier.weight(1f))
                        Text("已存 ${audioSessions.size} 段", style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = Muted)
                        PojianSwitch(checked = audioCapturing,
                            onCheckedChange = { value -> if (value) onAudioCapture() else onCancelTask() },
                            enabled = audioCanStart || audioCapturing,
                            modifier = Modifier.testTag("audio-record-toggle")
                                .semantics { contentDescription = "离线语音转文字" })
                    }
                }
                if (audioState.task == null) {
                    MetadataText("音频仅在设备端离线识别为文本，不上传原始录音文件。")
                }
                if (audioState.modelState.status == AudioModelStatus.ERROR) {
                    audioState.modelState.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                audioState.task?.let {
                    AudioTaskProgress(it, onCancelTask, compact = true, onPauseResume, isAudioPaused, showTranscript = false)
                }
                RecordingAudioActions(audioCanStart, onImport, onOpenAudioRecords)
                audioState.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item("materials") { RecordingMaterials(events, report) }
        item("transcript") { RecordingTranscriptStream(audioState, audioSessions, isAudioPaused) }
    }
}

@Composable
private fun SwissSectionHeader(number: String, title: String, eyebrow: String,
    modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(5.dp), color = DesignTokens.Ink, contentColor = Color.White) {
            Text(number, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Ink, modifier = Modifier.semantics { heading() })
            Text(eyebrow, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun RecordingAudioActions(canImport: Boolean, onImport: () -> Unit, onOpenRecords: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val actions: @Composable (Modifier) -> Unit = { modifier ->
            PojianSecondaryButton(onClick = onImport, enabled = canImport,
                modifier = modifier.testTag("audio-import")) { Text("导入音频") }
            PojianSecondaryButton(onClick = onOpenRecords, modifier = modifier) { Text("转写记录") }
        }
        if (maxWidth / LocalDensity.current.fontScale < 260.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun RecordingApplications(apps: List<CaptureApp>, selected: Set<String>,
    onChange: (Set<String>) -> Unit, onChooseApps: () -> Unit) {
    val selectedApps = remember(apps, selected) {
        val names = apps.associate { it.packageName to it.label }
        selected.map { packageName -> packageName to (names[packageName] ?: platformName(packageName)) }
    }
    Column(Modifier.fillMaxWidth().testTag("recording-app-scope")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("记录应用范围", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = Ink)
            PojianTextButton(onClick = onChooseApps) { Text("全部应用") }
        }
        if (selectedApps.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selectedApps.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (packageName, label) ->
                            Surface(Modifier.weight(1f).testTag("recording-app-$packageName")
                                .toggleable(true, role = Role.Switch) { onChange(selected - packageName) },
                                shape = RoundedCornerShape(12.dp),
                                color = Ink,
                                contentColor = Color.White,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.heightIn(min = 88.dp).padding(horizontal = 4.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SourceAppIcon(packageName, size = 28.dp)
                                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingMaterials(events: List<BrowseEvent>, report: CognitiveReport?) {
    val latest = remember(events) { events.filter { it.sourceType == "accessibility" }
        .maxWithOrNull(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id }) }
    val related = remember(events, latest) {
        latest?.let { screen -> events.filter { event -> event.id == screen.id || event.parentEventId == screen.id ||
            (screen.workKey != null && event.workKey == screen.workKey && event.packageName == screen.packageName) }
            .map { it.id }.toSet() }.orEmpty()
    }
    val claims = remember(report?.claims, related) {
        report?.claims.orEmpty().asSequence().filter { it.eventId in related }
            .distinctBy { it.quote }.take(3).toList()
    }
    val speech = remember(events, related) {
        events.filter { it.id in related && it.sourceType.startsWith("audio_") }
            .takeIf { it.isNotEmpty() }?.joinToString("\n") { it.contentText }
    }
    WhiteCard(Modifier.testTag("recording-materials")) {
        SectionTitle("观点与依据")
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("屏幕文字", color = Muted, style = MaterialTheme.typography.labelMedium)
                val raw = latest?.visibleText?.takeIf { it.isNotBlank() } ?: "暂无记录"
                val original = remember(raw) { buildAnnotatedString {
                    raw.lineSequence().forEachIndexed { index, line ->
                        if (index > 0) append('\n')
                        if (PlatformTextCleaner.isControlLine(line)) withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(line) }
                        else append(line)
                    }
                } }
                Text(original,
                    style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        if (claims.isEmpty()) Text("暂无分析结果", color = Muted, style = MaterialTheme.typography.bodySmall)
        else claims.forEach { claim ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(claim.quote, style = MaterialTheme.typography.bodyMedium)
                claim.evidenceDetails.take(2).forEach { evidence ->
                    Text(evidence.text, color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (evidence.historyMatches.isNotEmpty()) Text("重复线索 ${evidence.historyMatches.size}",
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (speech != null) {
            Text("关联语音", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(speech, style = MaterialTheme.typography.bodySmall,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecordingTranscriptStream(state: AudioUiState, sessions: List<AudioSessionUi>, paused: Boolean) {
    val active = state.task?.phase == AudioTaskPhase.CAPTURING && !paused
    val recentSegments = remember(sessions) {
        sessions.sortedByDescending { it.createdAt }.take(3).flatMap { session ->
            session.segments.filter { (it.correctedText ?: it.originalText).isNotBlank() }
                .takeLast(3).map { session to it }
        }
    }
    WhiteCard(Modifier.testTag("recording-transcript-stream")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("实时语音转写", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            RecordingActivity(active, state.task?.audioLevels.orEmpty())
        }
        state.task?.partialText?.takeIf { it.isNotBlank() }?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(audioTimestamp(state.task.elapsedMillis), color = Muted,
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("audio-live-transcript"))
            }
        }
        if (recentSegments.isEmpty() && state.task?.partialText.isNullOrBlank())
            Text(if (active) "等待转写…" else "暂无转写记录", color = Muted, style = MaterialTheme.typography.bodySmall)
        recentSegments.forEach { (session, segment) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(audioTimestamp(segment.startMillis), color = Muted,
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceAppText(session.sourcePackage,
                        session.sourcePackage?.let { name -> appsLabel(name) } ?: session.sourceLabel,
                        style = MaterialTheme.typography.labelSmall)
                    Text(segment.correctedText ?: segment.originalText, style = MaterialTheme.typography.bodySmall,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun appsLabel(packageName: String): String = platformName(packageName)

/** PCM measurements only; silence and inactive capture have zero height. */
@Composable
private fun RecordingActivity(active: Boolean, levels: List<Float>) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val showLevels = active && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val color = if (active) Ink else MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(36.dp, 24.dp)) {
        repeat(5) { index ->
            val fraction = if (showLevels) levels.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f else 0f
            val x = size.width * (index + .5f) / 5
            drawLine(color, Offset(x, size.height * (1 - fraction) / 2),
                Offset(x, size.height * (1 + fraction) / 2), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
