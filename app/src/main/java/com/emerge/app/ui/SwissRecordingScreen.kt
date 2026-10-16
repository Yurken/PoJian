package com.emerge.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.capture.PlatformTextCleaner
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText
import kotlin.math.abs
import kotlin.math.sin

/** Controls observe the existing recorder and capture scope; no separate recording state is stored. */
@Composable
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
    message: String? = null
) {
    val audioCapturing = audioState.task?.phase == AudioTaskPhase.CAPTURING
    val audioCanStart = audioState.modelState.status == AudioModelStatus.READY && audioState.task == null
    val captureLabel = when {
        selectedPackages.isEmpty() -> "未选择应用"
        !enabled -> "已暂停"
        !permission -> "未授权"
        !diagnostics.connected -> "未连接"
        else -> "记录中"
    }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-recording-screen"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("channels") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val content: @Composable (Modifier) -> Unit = { tile ->
                    RecordingChannelCard("屏幕文字识别", captureLabel, enabled,
                        selectedPackages.isNotEmpty(), onCapture, tile, "capture-toggle", "— ms/帧")
                    RecordingChannelCard("离线语音转文字", when {
                        audioCapturing && isAudioPaused -> "已暂停"
                        audioCapturing -> "记录中"
                        audioState.task != null -> "处理中"
                        audioState.modelState.status == AudioModelStatus.READY -> "就绪"
                        audioState.modelState.status == AudioModelStatus.DOWNLOADING -> "下载中"
                        else -> "模型未就绪"
                    }, audioCapturing, audioCanStart || audioCapturing,
                        { value -> if (value) onAudioCapture() else onCancelTask() }, tile, "audio-record-toggle", "RTF —")
                }
                if (maxWidth / LocalDensity.current.fontScale < 290.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content(Modifier.fillMaxWidth()) }
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { content(Modifier.weight(1f)) }
            }
            if (!permission || !diagnostics.connected) PojianTextButton(onClick = onOpenSettings,
                modifier = Modifier.testTag("recording-system-permission")) {
                Text(if (permission) "检查系统授权" else "前往系统授权")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        item("applications") { RecordingApplications(apps, selectedPackages, onPackagesChange, onChooseApps) }
        item("audio-controls") {
            WhiteCard(Modifier.testTag("audio-entry")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("语音模型", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (audioState.modelState.status == AudioModelStatus.READY) "就绪" else "未就绪",
                        color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                if (audioState.modelState.status != AudioModelStatus.READY)
                    ModelPreparation(audioState.modelState, onDownloadModel, onCancelDownload, compact = true)
                audioState.task?.let {
                    AudioTaskProgress(it, onCancelTask, compact = true, onPauseResume, isAudioPaused, showTranscript = false)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PojianSecondaryButton(onClick = onImport, enabled = audioCanStart,
                        modifier = Modifier.weight(1f).testTag("audio-import")) { Text("导入音频") }
                    PojianSecondaryButton(onClick = onOpenAudioRecords, modifier = Modifier.weight(1f)) { Text("转写记录") }
                }
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
private fun RecordingChannelCard(title: String, status: String, checked: Boolean, enabled: Boolean,
    onChange: (Boolean) -> Unit, modifier: Modifier, tag: String, metric: String) {
    WhiteCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.labelMedium)
            PojianSwitch(checked, onChange, enabled = enabled,
                modifier = Modifier.testTag(tag).semantics { contentDescription = title })
        }
        Text(metric, color = Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

private val recordingPlatforms = listOf(
    "com.ss.android.ugc.aweme" to "抖音", "com.xingin.xhs" to "小红书",
    "tv.danmaku.bili" to "B站", "com.zhihu.android" to "知乎"
)

@Composable
private fun RecordingApplications(apps: List<CaptureApp>, selected: Set<String>,
    onChange: (Set<String>) -> Unit, onChooseApps: () -> Unit) {
    val availablePackages = remember(apps) { apps.map { it.packageName }.toSet() }
    WhiteCard(Modifier.testTag("recording-app-scope")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("记录应用范围", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            PojianTextButton(onClick = onChooseApps) { Text("全部应用") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recordingPlatforms.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (packageName, label) ->
                            val checked = packageName in selected
                            val available = packageName in availablePackages || checked
                            Surface(Modifier.weight(1f).testTag("recording-app-$packageName")
                                .toggleable(checked, enabled = available, role = Role.Switch) { value ->
                                    onChange(if (value) selected + packageName else selected - packageName)
                                }, shape = RoundedCornerShape(12.dp),
                                color = if (checked) Ink else MaterialTheme.colorScheme.surface,
                                contentColor = if (checked) Color.White else if (available) Ink else Muted,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.heightIn(min = 88.dp).padding(horizontal = 4.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(22.dp))
                                    else if (available) SourceAppIcon(packageName, size = 22.dp)
                                    else Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Text("—") }
                                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    if (!available) Text("未发现", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
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
    val claims = report?.claims.orEmpty().filter { it.eventId in related }.distinctBy { it.quote }
    WhiteCard(Modifier.testTag("recording-materials")) {
        SectionTitle("观点与论据提炼")
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原始屏幕文字", color = Muted, style = MaterialTheme.typography.labelMedium)
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
        if (claims.isEmpty()) Text("暂无提炼结果", color = Muted, style = MaterialTheme.typography.bodySmall)
        else claims.take(3).forEach { claim ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(claim.quote, style = MaterialTheme.typography.bodyMedium)
                claim.evidenceDetails.take(2).forEach { evidence ->
                    Text(evidence.text, color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (evidence.historyMatches.isNotEmpty()) Text("同源线索 ${evidence.historyMatches.size}",
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val speech = events.filter { it.id in related && it.sourceType.startsWith("audio_") }
        if (speech.isNotEmpty()) {
            Text("关联口播", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(speech.joinToString("\n") { it.contentText }, style = MaterialTheme.typography.bodySmall,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecordingTranscriptStream(state: AudioUiState, sessions: List<AudioSessionUi>, paused: Boolean) {
    val active = state.task?.phase == AudioTaskPhase.CAPTURING && !paused
    val recent = remember(sessions) { sessions.sortedByDescending { it.createdAt }.take(3) }
    WhiteCard(Modifier.testTag("recording-transcript-stream")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("视频语音实时转写", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            RecordingActivity(active)
        }
        state.task?.partialText?.takeIf { it.isNotBlank() }?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(audioTimestamp(state.task.elapsedMillis), color = Muted,
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("audio-live-transcript"))
            }
        }
        val hasSaved = recent.any { session -> session.segments.any { (it.correctedText ?: it.originalText).isNotBlank() } }
        if (!hasSaved && state.task?.partialText.isNullOrBlank())
            Text(if (active) "等待转写…" else "暂无转写记录", color = Muted, style = MaterialTheme.typography.bodySmall)
        recent.forEach { session ->
            session.segments.filter { (it.correctedText ?: it.originalText).isNotBlank() }.takeLast(3).forEach { segment ->
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
}

private fun appsLabel(packageName: String): String = recordingPlatforms.firstOrNull { it.first == packageName }?.second ?: packageName

/** Decorative recording activity, deliberately unrelated to measured sound amplitude. */
@Composable
private fun RecordingActivity(active: Boolean) {
    val motionEnabled = rememberSystemMotionEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = active && motionEnabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "recording-activity")
        val value by transition.animateFloat(0f, 6.2832f,
            infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "recording-wave")
        value
    } else 0f
    val color = if (active) Ink else MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(36.dp, 24.dp)) {
        repeat(5) { index ->
            val fraction = if (animate) .3f + .65f * abs(sin(phase + index)) else .25f
            val x = size.width * (index + .5f) / 5
            drawLine(color, Offset(x, size.height * (1 - fraction) / 2),
                Offset(x, size.height * (1 + fraction) / 2), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
