package com.emerge.app.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.emerge.app.EmergeApplication
import com.emerge.app.analysis.*
import com.emerge.app.data.ObservedWork
import com.emerge.app.data.sourceLabel
import com.emerge.app.data.WorkThumbnail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseWorkCards(items: List<ViewpointItem>, start: Long, end: Long, names: Map<String, String>,
    zone: ZoneId = ZoneId.systemDefault(), onScrolling: (Boolean) -> Unit = {}) {
    val listState = rememberLazyListState()
    val scrollingCallback by rememberUpdatedState(onScrolling)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrollingCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { scrollingCallback(false) } }
    val synthetic = LocalSyntheticDataset.current
    val app = (LocalContext.current.applicationContext as? EmergeApplication).takeIf { synthetic == null }
    val works = (synthetic?.works ?: app?.repository?.works?.collectAsState(initial = emptyList())?.value.orEmpty()).associateBy { it.workKey }
    val thumbnails = app?.workThumbnails?.thumbnails?.collectAsState()?.value.orEmpty()
    val consent = app?.captureSettings?.localThumbnails?.collectAsState()?.value == true
    val packages = app?.captureSettings?.allowedPackages?.collectAsState()?.value.orEmpty()
    val selected = remember(items) { ViewpointTimeline.details(items) }
    var detail by rememberSaveable(start) { mutableStateOf<String?>(null) }
    val window = remember(start, end, zone) { ObservationWindow(start, end, zone) }
    val topics = remember(selected, window) { WorkTopicPresentation.topics(selected, window) }
    val time = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    // Bounded nested viewport: only visible thumbnails are decoded, at most two on background threads.
    if (selected.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("work-card-list"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        topics.forEach { topic ->
        item(key = "topic:${topic.title}") {
            Row(Modifier.fillMaxWidth().testTag("work-topic-${topic.title}"), verticalAlignment = Alignment.CenterVertically) {
                Text(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                MetadataText("${topic.works.size} 个作品")
            }
        }
        items(topic.works, key = { it.workIdentity }) { item ->
            val thumbnail = thumbnails[item.record.workKey]?.takeIf { consent && it.packageName in packages }
            val duration = item.durationMillis(window)
            val impact = WorkTopicPresentation.impact(item.claims, duration)
            val accent = impactColor(impact)
            Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = .035f),
                border = BorderStroke(1.dp, accent.copy(alpha = .25f)),
                modifier = Modifier.fillMaxWidth().testTag("work-card-${item.workIdentity}")
                    .clickable(role = Role.Button) { detail = item.workIdentity }
                    .semantics { stateDescription = "影响提示${impact.label}" }) {
            Row(Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (thumbnail != null) WorkImage(thumbnail, app, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SourceAppText(item.record.packageName, synthetic?.mediaTypes?.get(item.record.workKey)
                            ?: names[item.record.packageName] ?: item.record.packageName)
                        MetadataText(time.format(Instant.ofEpochMilli(WorkTopicPresentation.firstSeen(item, window))))
                    }
                    val claims = item.claims.distinctBy { it.key }.take(3)
                    if (claims.isEmpty()) Text("观点未提取", style = MaterialTheme.typography.bodyMedium)
                    claims.forEach { claim ->
                        val claimAccent = impactColor(WorkTopicPresentation.impact(listOf(claim), duration))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(claimAccent))
                            Text(WorkTopicPresentation.phrase(claim), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = Ink)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetadataText(duration?.let(::formatDuration) ?: "时长未统计")
                        Text("影响提示${impact.label}", color = accent, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            }
        }
        }
    }
    selected.firstOrNull { it.workIdentity == detail }?.let { item ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            val format = remember(zone) { DateTimeFormatter.ofPattern("M/d HH:mm:ss").withZone(zone) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).testTag("work-detail-content"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(workTitle(works[item.record.workKey], item), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        PojianTextButton(onClick = { detail = null }) { Text("关闭") }
                    }
                }
                item {
                    val image = thumbnails[item.record.workKey]?.takeIf { consent && it.packageName in packages }
                    if (image != null) WorkImage(image, app)
                    WorkAnalysisContent(item, window)
                }
                item { Text("原文", style = MaterialTheme.typography.titleSmall) }
                items(item.records.sortedWith(compareBy({ it.timestamp }, { it.id })), key = { "record:${it.id}" }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider()
                        val source = when (row.sourceType) {
                            "accessibility" -> "观看"
                            "screen_ocr" -> "画面文字"
                            "visual_model" -> "画面分析"
                            else -> "转写"
                        }
                        MetadataText("$source · ${format.format(Instant.ofEpochMilli(row.timestamp))}")
                        QuoteBlock(row.visibleText)
                    }
                }
            }
        }
    }
}

private fun workTitle(work: ObservedWork?, item: ViewpointItem): String = work?.title?.takeIf { it.isNotBlank() }
    ?: item.record.visibleText.lineSequence().map(String::trim).firstOrNull { it.length >= 8 } ?: "作品"

@Composable
private fun WorkImage(row: WorkThumbnail?, app: EmergeApplication?, imageSize: Dp = 88.dp) {
    val bitmap by produceState<Bitmap?>(null, row?.fileName, app) {
        value = null
        if (row != null && app != null) value = app.workThumbnails.load(row)
    }
    Column(Modifier.width(imageSize), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(imageSize).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if (loaded != null && row != null) Image(loaded.asImageBitmap(), contentDescription = row.sourceLabel,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("—", color = Muted)
        }
        if (bitmap != null && row != null) Text(row.sourceLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

internal fun impactColor(level: ImpactLevel): Color = when (level) {
    ImpactLevel.HIGH -> Color(0xFFAF625C)
    ImpactLevel.MEDIUM -> Color(0xFF907729)
    ImpactLevel.LOW -> Color(0xFF4B8569)
    ImpactLevel.UNKNOWN -> Color(0xFF737B82)
}

@Composable
internal fun WorkAnalysisContent(item: ViewpointItem, window: ObservationWindow) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("分析", help = "颜色按已保存的内容分析与本时段有效停留时长计算，不是心理诊断或风险概率。高信号累计停留至少 1 分钟、或中信号至少 3 分钟时为红色；其余已识别中高信号为黄色，完整低信号为绿色。缺少依据或时长为未评估。单条内容不能证明已经形成信息茧房。")
        val duration = item.durationMillis(window)
        MetadataText("影响提示${WorkTopicPresentation.impact(item.claims, duration).label} · ${duration?.let(::formatDuration) ?: "时长未统计"}")
        if (item.claims.isEmpty()) MetadataText("暂无大模型分析")
        item.claims.distinctBy { it.key }.forEach { claim ->
            Text(WorkTopicPresentation.phrase(claim), style = MaterialTheme.typography.titleSmall)
            val source = when {
                claim.inputKind == "synthetic_annotation" -> "AI 合成分析（未人工复核）"
                claim.analysisSource == "structured_model" -> "大模型分析"
                else -> "本地识别"
            }
            MetadataText(source)
            val presentation = claim.presentation
            if (presentation == null) {
                if (claim.analysisSource == "structured_model") {
                    listOf(claim.stance, claim.conditions, claim.evidence).filter(String::isNotBlank).distinct().forEach { QuoteBlock(it) }
                    MetadataText("影响分析未生成")
                } else MetadataText("暂无大模型分析")
            } else PresentationAnalysis(presentation)
        }
    }
}

@Composable
internal fun PresentationAnalysis(presentation: ClaimPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("焦虑影响" to presentation.anxiety, "茧房倾向" to presentation.cocoon,
            "认知影响" to presentation.cognition).forEach { (label, signal) ->
            Text("$label · ${signal.level.label}", color = impactColor(signal.level), style = MaterialTheme.typography.labelLarge)
            Text(signal.reason, style = MaterialTheme.typography.bodyMedium)
            if (signal.evidence.isNotBlank()) QuoteBlock(signal.evidence)
        }
        Text("建议", style = MaterialTheme.typography.titleSmall)
        presentation.suggestions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun LocalThumbnailControl() {
    val app = LocalContext.current.applicationContext as? EmergeApplication ?: return
    val enabled by app.captureSettings.localThumbnails.collectAsState()
    val status by app.workThumbnails.status.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    WhiteCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本地缩略图", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            PojianSwitch(checked = enabled, enabled = Build.VERSION.SDK_INT >= 30,
                onCheckedChange = { if (it) confirm = true else app.captureSettings.setLocalThumbnails(false) },
                modifier = Modifier.testTag("local-thumbnail-toggle"))
        }
        if (Build.VERSION.SDK_INT < 30) MetadataText("当前系统仅显示文字")
        if (enabled && status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("保存本地缩略图") },
        text = { Text("将已观察画面压缩保存在本机，用于作品缩略图。关闭后删除已保存缩略图，不会因此上传图片。") },
        confirmButton = { TextButton(onClick = { app.captureSettings.setLocalThumbnails(true); confirm = false }) { Text("允许保存") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
