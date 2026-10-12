package com.emerge.app.monitoring

import com.emerge.app.ui.PojianSecondaryButton
import com.emerge.app.ui.PojianTextButton
import com.emerge.app.ui.PojianSegmentedTabs
import com.emerge.app.ui.SourceAppText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.MetadataText
import com.emerge.app.ui.PojianActionChip
import com.emerge.app.ui.QuoteBlock
import com.emerge.app.ui.WhiteCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorDiscoverScreen(state: MonitorState, appNames: Map<String, String>, modifier: Modifier = Modifier,
    onRecords: () -> Unit, events: List<BrowseEvent> = emptyList()) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val repetitions = state.findings.filter { it.kind == "cocoon" }
    val facts = state.findings.filter { it.kind == "fact" }
    val displayed = if (filter == 0) repetitions else facts
    // Resolve by identity on every update: revised results and deleted entries never leave stale sheets.
    val selected = state.findings.firstOrNull { it.id == selectedId }
    LaunchedEffect(selected) { if (selected == null) selectedId = null }
    Column(modifier.fillMaxSize()) {
        PojianSegmentedTabs(listOf("重复观点", "事实核查"), filter, onSelect = { filter = it },
            counts = listOf(repetitions.size, facts.size),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("monitor-discovery-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.pending > 0) item { MetadataText("正在处理 ${state.pending} 项") }
            if (displayed.isEmpty()) item {
                Text(if (filter == 0) "暂未发现重复观点" else "暂无核查结果",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(displayed.sortedByDescending { it.capturedAt }, key = { it.id }) { finding ->
                DiscoveryCard(finding) { selectedId = finding.id }
            }
            item { PojianTextButton(onClick = onRecords) { Text("全部记录") } }
        }
    }
    selected?.let { finding ->
        DiscoveryDetail(finding, events, appNames, onDismiss = { selectedId = null },
            onRecords = { selectedId = null; onRecords() })
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
    Surface(shape = RoundedCornerShape(8.dp), color = bgColor, contentColor = textColor) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(textColor, RoundedCornerShape(50)))
            Text(discoveryVerdict(verdict), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FactCheckCard(finding: MonitorFinding, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("discovery-${finding.id}"),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VerdictBadge(finding.verdict)
                Text(
                    text = "“${finding.claim}”",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (finding.explanation.isNotBlank()) {
                    Text(
                        text = finding.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp),
                color = scheme.outlineVariant.copy(alpha = 0.6f))
            PojianTextButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(0.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text("查看核查依据", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DiscoveryCard(finding: MonitorFinding, onOpen: () -> Unit) {
    val repetition = finding.kind == "cocoon"
    if (!repetition) {
        FactCheckCard(finding, onOpen)
        return
    }
    WhiteCard(
        modifier = Modifier.fillMaxWidth().testTag("discovery-${finding.id}"),
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = finding.topic.ifBlank { "重复观点" },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Text(
            text = "“${finding.claim}”",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        if (finding.explanation.isNotBlank()) {
            Text(
                text = finding.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PojianActionChip(
                text = "查看原文与对比",
                onClick = onOpen
            )
        }
    }
}

private fun discoveryVerdict(verdict: String): String = when (verdict) {
    "supported" -> "有资料支持"
    "contradicted" -> "与资料不符"
    "conflicting" -> "资料有分歧"
    "insufficient" -> "证据不足"
    "opinion" -> "个人观点"
    "not_checked" -> "尚未核查"
    else -> "待核查"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryDetail(finding: MonitorFinding, events: List<BrowseEvent>, appNames: Map<String, String>,
    onDismiss: () -> Unit, onRecords: () -> Unit) {
    val repetition = finding.kind == "cocoon"
    val originals = remember(finding, events) {
        events.filter { it.packageName == finding.packageName || repetition }.filter {
            finding.claim.isNotBlank() && it.visibleText.contains(finding.claim)
        }.sortedByDescending { it.timestamp }
    }
    val uri = LocalUriHandler.current
    var linkError by remember(finding.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = if (repetition) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (!repetition) {
            FactCheckDetailContent(finding, originals, appNames, linkError,
                onDismiss = onDismiss, onRecords = onRecords,
                onOpenSource = { source -> linkError = runCatching { uri.openUri(source.url) }.isFailure })
            return@ModalBottomSheet
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (repetition) "观点对比" else "事实核查", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                PojianTextButton(onClick = onDismiss) { Text("关闭") }
            }
            LazyColumn(Modifier.weight(1f).testTag("discovery-detail"),
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetadataText(if (repetition) "反复出现的说法" else "核查的说法")
                        QuoteBlock(finding.claim)
                        Text(if (repetition) "为什么提醒你" else discoveryVerdict(finding.verdict),
                            style = MaterialTheme.typography.titleMedium)
                        Text(finding.explanation, style = MaterialTheme.typography.bodyMedium)
                        SourceAppText(finding.packageName,
                            "${appNames[finding.packageName] ?: finding.packageName} · ${discoveryTime(finding.capturedAt)}")
                    }
                }
                if (finding.sources.isNotEmpty()) item {
                    Text("参考资料", style = MaterialTheme.typography.titleMedium)
                }
                items(finding.sources) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        QuoteBlock(source.quote)
                        PojianTextButton(enabled = source.valid(), onClick = {
                            linkError = runCatching { uri.openUri(source.url) }.isFailure
                        }) { Text("打开来源") }
                    }
                }
                if (linkError) item { Text("无法打开链接，请稍后重试", color = MaterialTheme.colorScheme.error) }
                item {
                    HorizontalDivider()
                    Text(if (originals.isEmpty()) "对应原文暂不可用" else "包含这句话的记录 · ${originals.size} 条",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                }
                items(originals, key = { it.id }) { event ->
                    var expanded by rememberSaveable(finding.id, event.id) { mutableStateOf(false) }
                    Column(Modifier.testTag("discovery-original-${event.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceAppText(event.packageName.takeUnless { event.sourceType == "audio_import" },
                            "${appNames[event.packageName] ?: event.packageName} · ${discoveryTime(event.timestamp)}")
                        Text(event.visibleText, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        PojianTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "展开原文") }
                    }
                }
                item { PojianTextButton(onClick = onRecords) { Text("全部记录") } }
            }
        }
    }
}

@Composable
private fun FactCheckDetailContent(finding: MonitorFinding, originals: List<BrowseEvent>,
    appNames: Map<String, String>, linkError: Boolean, onDismiss: () -> Unit,
    onRecords: () -> Unit, onOpenSource: (WebEvidence) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("事实核查", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            PojianTextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text("关闭") }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discovery-detail"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                FactDetailCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetadataText("核查的说法")
                        SelectionContainer {
                            Text(finding.claim, style = MaterialTheme.typography.titleMedium,
                                fontSize = 17.sp, lineHeight = 26.sp)
                        }
                        SourceAppText(finding.packageName,
                            "${appNames[finding.packageName] ?: finding.packageName} · ${discoveryTime(finding.capturedAt)}")
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                        VerdictBadge(finding.verdict)
                        if (finding.explanation.isNotBlank()) {
                            Text(finding.explanation, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (finding.sources.isNotEmpty()) item {
                Text("参考资料", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            }
            items(finding.sources) { source ->
                FactDetailCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        QuoteBlock(source.quote)
                    }
                    FactDetailAction("打开来源", enabled = source.valid(), onClick = { onOpenSource(source) })
                }
            }
            if (linkError) item {
                Surface(shape = RoundedCornerShape(12.dp), color = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer) {
                    Text("无法打开链接，请稍后重试", modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Text(if (originals.isEmpty()) "对应原文暂不可用" else "包含这句话的记录 · ${originals.size} 条",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            }
            items(originals, key = { it.id }) { event ->
                var expanded by rememberSaveable(finding.id, event.id) { mutableStateOf(false) }
                FactDetailCard(Modifier.testTag("discovery-original-${event.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SourceAppText(event.packageName.takeUnless { event.sourceType == "audio_import" },
                            "${appNames[event.packageName] ?: event.packageName} · ${discoveryTime(event.timestamp)}")
                        Text(event.visibleText, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    }
                    FactDetailAction(if (expanded) "收起原文" else "展开原文",
                        onClick = { expanded = !expanded }, expanded = expanded)
                }
            }
            item {
                PojianSecondaryButton(onClick = onRecords, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, scheme.outlineVariant)) {
                    Text("全部记录", modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun FactDetailCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(content = content)
    }
}

@Composable
private fun FactDetailAction(text: String, onClick: () -> Unit, enabled: Boolean = true, expanded: Boolean? = null) {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    PojianTextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(0.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(12.dp))
        Icon(when (expanded) {
            true -> Icons.Outlined.KeyboardArrowUp
            false -> Icons.Outlined.KeyboardArrowDown
            null -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
        }, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

private fun discoveryTime(at: Long): String = SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(Date(at))
