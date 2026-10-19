package com.emerge.app.monitoring

import com.emerge.app.ui.PojianSecondaryButton
import com.emerge.app.ui.PojianTextButton
import com.emerge.app.ui.PojianSegmentedTabs
import com.emerge.app.ui.SourceAppText
import com.emerge.app.ui.AudioSourceContent
import com.emerge.app.ui.DesignTokens
import com.emerge.app.ui.Ink
import com.emerge.app.ui.Muted

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import com.emerge.app.R
import com.emerge.app.analysis.EmotionEvidence
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.MetadataText
import com.emerge.app.ui.QuoteBlock
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorDiscoverScreen(
    state: MonitorState,
    appNames: Map<String, String>,
    modifier: Modifier = Modifier,
    onRecords: () -> Unit,
    events: List<BrowseEvent> = emptyList(),
    semantics: List<EventSemantics> = emptyList()
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val categories = remember(state.findings) {
        state.findings.sortedByDescending { it.capturedAt }.groupBy { it.kind }
    }
    val repetitions = categories["cocoon"].orEmpty()
    val facts = categories["fact"].orEmpty()
    val emotions = categories["emotion"].orEmpty()
    val displayed = when (filter) {
        0 -> repetitions
        1 -> facts
        else -> emotions
    }
    // Resolve by identity on every update: revised results and deleted entries never leave stale sheets.
    val selected = state.findings.firstOrNull { it.id == selectedId }
    LaunchedEffect(selected) { if (selected == null) selectedId = null }
    Column(modifier.fillMaxSize()) {
        PojianSegmentedTabs(
            labels = listOf("重复观点", "事实核查", "内容情绪"),
            selectedIndex = filter,
            onSelect = { filter = it },
            counts = listOf(repetitions.size, facts.size, emotions.size),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("monitor-discovery-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.pending > 0) {
                item("pending-status") {
                    Surface(
                        shape = DesignTokens.InnerShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Ink)
                            MetadataText("正在处理 ${state.pending} 项")
                        }
                    }
                }
            }
            if (displayed.isEmpty()) {
                item("empty-state") {
                    Surface(
                        shape = DesignTokens.CardShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, DesignTokens.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = when (filter) {
                                    0 -> "暂未发现重复观点"
                                    1 -> "暂无核查结果"
                                    else -> "暂无情绪提醒"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink
                            )
                        }
                    }
                }
            }
            items(displayed, key = { it.id }) { finding ->
                DiscoveryCard(finding, appNames) { selectedId = finding.id }
            }
            item("all-records-action") {
                PojianSecondaryButton(
                    onClick = onRecords,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    border = BorderStroke(1.dp, DesignTokens.Border)
                ) {
                    Text("全部记录", modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Muted
                    )
                }
            }
        }
    }
    selected?.let { finding ->
        DiscoveryDetail(
            finding = finding,
            events = events,
            appNames = appNames,
            semantics = semantics,
            onDismiss = { selectedId = null },
            onRecords = { selectedId = null; onRecords() }
        )
    }
}

@Composable
private fun VerdictBadge(verdict: String) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val (bgColor, textColor) = when (verdict) {
        "supported" -> if (dark) Color(0xFF143D2C) to Color(0xFF86EFAC)
        else Color(0xFFDCFCE7) to Color(0xFF15803D)
        "contradicted" -> scheme.errorContainer to scheme.onErrorContainer
        "conflicting" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        "opinion" -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bgColor, contentColor = textColor) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(textColor, RoundedCornerShape(50)))
            Text(
                text = discoveryVerdict(verdict),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DiscoveryCard(
    finding: MonitorFinding,
    appNames: Map<String, String>,
    onOpen: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val repetition = finding.kind == "cocoon"
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("discovery-${finding.id}"),
        shape = DesignTokens.CardShape,
        color = scheme.surface,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, DesignTokens.Border)
    ) {
        Column {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DiscoveryCardMetadata(finding, appNames)
                Text(
                    text = "“${finding.claim}”",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!repetition && finding.explanation.isNotBlank()) {
                    Text(
                        text = finding.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = DesignTokens.Border.copy(alpha = 0.75f),
                thickness = 0.75.dp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (finding.kind) {
                        "cocoon" -> "查看原文与对比"
                        "emotion" -> "查看原文"
                        else -> "查看核查依据"
                    },
                    modifier = Modifier.weight(1f),
                    color = scheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = scheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryCardMetadata(finding: MonitorFinding, appNames: Map<String, String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (finding.kind in setOf("cocoon", "emotion")) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text(
                    text = finding.topic.ifBlank {
                        if (finding.kind == "emotion") discoveryVerdict(finding.verdict) else "重复观点"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            VerdictBadge(finding.verdict)
        }
        SourceAppText(
            packageName = finding.packageName,
            text = "${appNames[finding.packageName] ?: finding.packageName} · ${discoveryTime(finding.capturedAt)}",
            modifier = Modifier.align(Alignment.CenterVertically),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun discoveryVerdict(verdict: String): String = when (verdict) {
    "supported" -> "有资料支持"
    "contradicted" -> "与资料不符"
    "conflicting" -> "资料有分歧"
    "insufficient" -> "证据不足"
    "opinion" -> "个人观点"
    "not_checked" -> "尚未核查"
    "emotional_exposure" -> "内容情绪"
    else -> "待核查"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryDetail(
    finding: MonitorFinding,
    events: List<BrowseEvent>,
    appNames: Map<String, String>,
    semantics: List<EventSemantics>,
    onDismiss: () -> Unit,
    onRecords: () -> Unit
) {
    val repetition = finding.kind == "cocoon"
    val originals = remember(finding, events) {
        events.filter { it.packageName == finding.packageName || repetition }.filter {
            finding.claim.isNotBlank() && it.visibleText.contains(finding.claim) &&
                (finding.kind != "emotion" || it.timestamp <= finding.capturedAt)
        }.sortedByDescending { it.timestamp }
    }
    val uri = LocalUriHandler.current
    val emotionEvidence = remember(finding, events, semantics) {
        DiscoveryEmotionEvidence.resolve(finding, events, semantics)
    }
    var linkError by remember(finding.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (repetition) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        if (finding.kind == "emotion") {
            EmotionDetailContent(finding, originals, emotionEvidence, appNames, onDismiss, onRecords)
            return@ModalBottomSheet
        }
        if (!repetition) {
            FactCheckDetailContent(
                finding = finding,
                originals = originals,
                appNames = appNames,
                linkError = linkError,
                onDismiss = onDismiss,
                onRecords = onRecords,
                onOpenSource = { source -> linkError = runCatching { uri.openUri(source.url) }.isFailure }
            )
            return@ModalBottomSheet
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            DiscoverySheetHeader("观点对比", onDismiss)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("discovery-detail"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    FactDetailCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetadataText("反复出现的说法")
                            SelectionContainer {
                                Text(
                                    text = finding.claim,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 17.sp,
                                    lineHeight = 26.sp
                                )
                            }
                            SourceAppText(
                                packageName = finding.packageName,
                                text = "${appNames[finding.packageName] ?: finding.packageName} · ${discoveryTime(finding.capturedAt)}"
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = if (originals.isEmpty()) "对应原文暂不可用" else "包含这句话的记录 · ${originals.size} 条",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() }
                    )
                }
                itemsIndexed(originals, key = { _, event -> event.id }) { index, event ->
                    DiscoveryOriginalRecord(
                        finding = finding,
                        event = event,
                        appNames = appNames,
                        repetition = true,
                        first = index == 0,
                        last = index == originals.lastIndex
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "为什么提醒你",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() }
                        )
                        Text(
                            text = finding.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (finding.sources.isNotEmpty()) {
                    item {
                        Text(
                            text = "参考资料",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() }
                        )
                    }
                }
                items(finding.sources) { source ->
                    DiscoverySourceCard(source) {
                        linkError = runCatching { uri.openUri(source.url) }.isFailure
                    }
                }
                if (linkError) {
                    item { Text("无法打开链接，请稍后重试", color = MaterialTheme.colorScheme.error) }
                }
                item { PojianTextButton(onClick = onRecords) { Text("全部记录") } }
            }
        }
    }
}

@Composable
private fun EmotionDetailContent(
    finding: MonitorFinding,
    originals: List<BrowseEvent>,
    evidence: List<EmotionEvidence>,
    appNames: Map<String, String>,
    onDismiss: () -> Unit,
    onRecords: () -> Unit
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        DiscoverySheetHeader(discoveryVerdict(finding.verdict), onDismiss)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("discovery-detail"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                FactDetailCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (finding.topic.isNotBlank()) Text(finding.topic, style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(finding.claim, style = MaterialTheme.typography.bodyLarge) }
                        if (finding.explanation.isNotBlank()) {
                            Text(
                                text = finding.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = if (originals.isEmpty()) "对应原文暂不可用" else "包含这句话的记录 · ${originals.size} 条",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }
            items(originals, key = { it.id }) { event ->
                DiscoveryOriginalRecord(
                    finding = finding,
                    event = event,
                    appNames = appNames,
                    repetition = false,
                    emotions = evidence.filter { it.eventId == event.id }
                )
            }
            item { PojianTextButton(onClick = onRecords) { Text("全部记录") } }
        }
    }
}

@Composable
private fun FactCheckDetailContent(
    finding: MonitorFinding,
    originals: List<BrowseEvent>,
    appNames: Map<String, String>,
    linkError: Boolean,
    onDismiss: () -> Unit,
    onRecords: () -> Unit,
    onOpenSource: (WebEvidence) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        DiscoverySheetHeader("事实核查", onDismiss)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("discovery-detail"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                FactDetailCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        VerdictBadge(finding.verdict)
                        MetadataText("核查的说法")
                        SelectionContainer {
                            Text(
                                text = finding.claim,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 17.sp,
                                lineHeight = 26.sp
                            )
                        }
                        SourceAppText(
                            packageName = finding.packageName,
                            text = "${appNames[finding.packageName] ?: finding.packageName} · ${discoveryTime(finding.capturedAt)}"
                        )
                        if (finding.explanation.isNotBlank()) {
                            Text(
                                text = finding.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (finding.sources.isNotEmpty()) {
                item {
                    Text(
                        text = "参考资料",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() }
                    )
                }
            }
            items(finding.sources) { source ->
                DiscoverySourceCard(source) { onOpenSource(source) }
            }
            if (linkError) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer
                    ) {
                        Text(
                            text = "无法打开链接，请稍后重试",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            item {
                Text(
                    text = if (originals.isEmpty()) "对应原文暂不可用" else "包含这句话的记录 · ${originals.size} 条",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() }
                )
            }
            items(originals, key = { it.id }) { event ->
                DiscoveryOriginalRecord(finding, event, appNames, repetition = false)
            }
            item {
                PojianSecondaryButton(
                    onClick = onRecords,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, scheme.outlineVariant)
                ) {
                    Text("全部记录", modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FactDetailCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DesignTokens.CardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, DesignTokens.Border)
    ) {
        Column(content = content)
    }
}

@Composable
private fun DiscoverySheetHeader(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
        }
    }
    HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.75f))
}

@Composable
private fun DiscoverySourceCard(source: WebEvidence, onOpen: () -> Unit) {
    val host = remember(source.url) { runCatching { URI(source.url).host }.getOrNull() }
    FactDetailCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.title, style = MaterialTheme.typography.titleSmall)
                    if (!host.isNullOrBlank()) {
                        Text(host, style = MaterialTheme.typography.labelSmall, color = Muted, fontFamily = FontFamily.Monospace)
                    }
                }
                IconButton(onClick = onOpen, enabled = source.valid(), modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = "打开来源",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            QuoteBlock(source.quote)
        }
    }
}

@Composable
private fun DiscoveryOriginalRecord(
    finding: MonitorFinding,
    event: BrowseEvent,
    appNames: Map<String, String>,
    repetition: Boolean,
    first: Boolean = true,
    last: Boolean = true,
    emotions: List<EmotionEvidence> = emptyList()
) {
    var expanded by rememberSaveable(finding.id, event.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val lineColor = scheme.outlineVariant
    val dotColor = scheme.onTertiaryContainer
    val highlightColor = scheme.tertiaryContainer
    val highlighted = remember(event.visibleText, finding.claim, repetition, highlightColor, dotColor) {
        buildAnnotatedString {
            append(event.visibleText)
            if (repetition && finding.claim.isNotBlank()) {
                var start = event.visibleText.indexOf(finding.claim)
                while (start >= 0) {
                    val end = start + finding.claim.length
                    addStyle(SpanStyle(color = dotColor, background = highlightColor), start, end)
                    start = event.visibleText.indexOf(finding.claim, end)
                }
            }
        }
    }
    val source: @Composable () -> Unit = {
        SourceAppText(
            packageName = event.packageName.takeUnless { event.sourceType == "audio_import" },
            text = "${appNames[event.packageName] ?: event.packageName} · ${discoveryTime(event.timestamp)}"
        )
    }
    val original: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (emotions.isEmpty()) 0.dp else 8.dp)
        ) {
            emotions.forEach { emotion ->
                MetadataText("${emotion.category.label} · ${emotion.level.label}")
                emotion.trigger?.let { trigger ->
                    MetadataText("诱因")
                    SelectionContainer { Text(trigger, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            AudioSourceContent(event.sourceType.startsWith("audio_")) {
                SelectionContainer {
                    Text(
                        text = highlighted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End).size(48.dp).semantics {
                    stateDescription = if (expanded) "已展开" else "已收起"
                }
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起原文" else "展开原文",
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    val tag = Modifier.fillMaxWidth().testTag("discovery-original-${event.id}")
    if (repetition) {
        Column(
            modifier = tag
                .drawBehind {
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 4.dp.toPx() else 4.dp.toPx()
                    val y = 9.dp.toPx()
                    if (!first || !last) {
                        drawLine(
                            color = lineColor,
                            start = Offset(x, if (first) y else 0f),
                            end = Offset(x, if (last) y else size.height + 16.dp.toPx()),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(x, y))
                }
                .padding(start = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            source()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = scheme.surface,
                border = BorderStroke(1.dp, DesignTokens.Border)
            ) { original() }
        }
    } else {
        FactDetailCard(tag) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { source() }
            original()
        }
    }
}

private fun discoveryTime(at: Long): String = SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(Date(at))
