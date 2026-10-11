package com.emerge.app.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.HelpButton
import com.emerge.app.ui.MetadataText
import com.emerge.app.ui.QuoteBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MonitorDiscoverScreen(state: MonitorState, appNames: Map<String, String>, modifier: Modifier = Modifier,
    onRecords: () -> Unit, onClear: () -> Unit, events: List<BrowseEvent> = emptyList()) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val repetitions = state.findings.filter { it.kind == "cocoon" }
    val facts = state.findings.filter { it.kind == "fact" }
    val displayed = if (filter == 0) repetitions else facts
    // Resolve by identity on every update: revised results and deleted entries never leave stale sheets.
    val selected = state.findings.firstOrNull { it.id == selectedId }
    LaunchedEffect(selected) { if (selected == null) selectedId = null }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("发现", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            HelpButton("发现", "重复观点：找出你反复接触的说法，比较依据是否变化。\n\n事实核查：对照外部资料核查具体说法。证据不足与尚未核查会单独标明。")
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "发现更多操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("清空发现") }, enabled = state.findings.isNotEmpty(),
                        onClick = { menu = false; confirm = true })
                }
            }
        }
        TabRow(selectedTabIndex = filter, containerColor = MaterialTheme.colorScheme.background) {
            listOf("重复观点" to repetitions.size, "事实核查" to facts.size).forEachIndexed { index, (label, count) ->
                Tab(selected = filter == index, onClick = { filter = index }, text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label)
                        if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall)
                    }
                })
            }
        }
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
            item { TextButton(onClick = onRecords) { Text("全部记录") } }
        }
    }
    selected?.let { finding ->
        DiscoveryDetail(finding, events, appNames, onDismiss = { selectedId = null },
            onRecords = { selectedId = null; onRecords() })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清空全部发现？") },
        text = { Text("清空重复观点、核查结果与对比材料，保留浏览记录。无法撤销。") },
        confirmButton = { TextButton(onClick = { onClear(); confirm = false },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
private fun DiscoveryCard(finding: MonitorFinding, onOpen: () -> Unit) {
    val repetition = finding.kind == "cocoon"
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("discovery-${finding.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (repetition) finding.topic.ifBlank { "重复观点" } else discoveryVerdict(finding.verdict),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("“${finding.claim}”", style = MaterialTheme.typography.titleMedium,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (finding.explanation.isNotBlank()) Text(finding.explanation,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (repetition) "查看原文与对比" else "查看核查依据")
            }
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (repetition) "观点对比" else "事实核查", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
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
                        MetadataText("${appNames[finding.packageName] ?: finding.packageName} · ${discoveryTime(finding.capturedAt)}")
                    }
                }
                if (finding.sources.isNotEmpty()) item {
                    Text("参考资料", style = MaterialTheme.typography.titleMedium)
                }
                items(finding.sources) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        QuoteBlock(source.quote)
                        TextButton(enabled = source.valid(), onClick = {
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
                        MetadataText("${appNames[event.packageName] ?: event.packageName} · ${discoveryTime(event.timestamp)}")
                        Text(event.visibleText, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "展开原文") }
                    }
                }
                item { TextButton(onClick = onRecords) { Text("全部记录") } }
            }
        }
    }
}

private fun discoveryTime(at: Long): String = SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(Date(at))
