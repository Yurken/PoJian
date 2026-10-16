@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Display-only filtering. A missing or invalid analysis never hides retained content. */
internal fun swissHistoryRecords(events: List<BrowseEvent>, semantics: List<EventSemantics>,
    report: CognitiveReport?, packageName: String?, keyword: String?): List<BrowseEvent> {
    val needle = keyword.orEmpty().trim()
    val model = semantics.groupBy { it.eventId }.mapValues { (_, rows) -> rows.maxBy { it.analyzedAt } }
    val observed = report?.claims.orEmpty().groupBy { it.eventId }
    return events.filter { event ->
        (packageName == null || event.packageName == packageName) && (needle.isBlank() ||
            event.contentText.contains(needle, ignoreCase = true) ||
            model[event.id]?.takeIf { it.validFor(event) }?.claims.orEmpty().any {
                listOf(it.topic, it.subject, it.quote, it.evidence).any { text -> text.contains(needle, ignoreCase = true) }
            } || observed[event.id].orEmpty().filter { it.packageName == event.packageName && event.visibleText.contains(it.quote) }.any {
                listOf(it.topic, it.subject, it.quote).any { text -> text.contains(needle, ignoreCase = true) }
            })
    }.sortedWith(compareByDescending<BrowseEvent> { it.timestamp }.thenByDescending { it.id })
}

internal fun swissHistoryMatches(event: BrowseEvent, report: CognitiveReport?, events: List<BrowseEvent>): List<EvidenceHistoryMatch> {
    val byId = events.associateBy { it.id }
    return report?.claims.orEmpty().filter { it.eventId == event.id && it.packageName == event.packageName && event.visibleText.contains(it.quote) }
        .flatMap { it.evidenceDetails }.filter { event.visibleText.contains(it.text) }
        .flatMap { it.historyMatches }.filter { match ->
            byId[match.eventId]?.let { it.timestamp < event.timestamp && it.visibleText.contains(match.quote) } == true
        }.distinct()
}

private data class SwissHistoryData(val records: List<BrowseEvent>, val model: Map<Long, EventSemantics>,
    val matches: Map<Long, List<EvidenceHistoryMatch>>)

private fun swissHistoryData(events: List<BrowseEvent>, semantics: List<EventSemantics>, report: CognitiveReport?,
    packageName: String?, keyword: String?): SwissHistoryData {
    val records = swissHistoryRecords(events, semantics, report, packageName, keyword)
    val byId = events.associateBy { it.id }
    val matches = report?.claims.orEmpty().filter { claim -> byId[claim.eventId]?.let {
        it.packageName == claim.packageName && it.visibleText.contains(claim.quote)
    } == true }.groupBy { it.eventId }.mapValues { (id, claims) ->
        val event = byId.getValue(id)
        claims.flatMap { it.evidenceDetails }.filter { event.visibleText.contains(it.text) }.flatMap { it.historyMatches }
            .filter { match -> byId[match.eventId]?.let { it.timestamp < event.timestamp && it.visibleText.contains(match.quote) } == true }.distinct()
    }
    return SwissHistoryData(records, semantics.groupBy { it.eventId }.mapValues { (_, rows) -> rows.maxBy { it.analyzedAt } }, matches)
}

internal val swissKnownApps = linkedMapOf(
    "com.ss.android.ugc.aweme" to "抖音", "com.xingin.xhs" to "小红书",
    "tv.danmaku.bili" to "B站", "com.zhihu.android" to "知乎")

@Composable
internal fun SwissFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(selected = selected, onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = DesignTokens.PillShape, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(containerColor = Color.White, labelColor = DesignTokens.Ink,
            selectedContainerColor = DesignTokens.Ink, selectedLabelColor = Color.White),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
            borderColor = DesignTokens.Border, selectedBorderColor = DesignTokens.Ink))
}

@Composable
internal fun SwissHistoryScreen(events: List<BrowseEvent>, semantics: List<EventSemantics>, report: CognitiveReport?,
    appNames: Map<String, String>, keyword: String?, onClearKeyword: () -> Unit,
    onDelete: (BrowseEvent) -> Unit, modifier: Modifier = Modifier) {
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val apps = remember(events, appNames) { swissKnownApps + events.map { it.packageName }.distinct()
        .filterNot { it in swissKnownApps }.associateWith { appNames[it] ?: it } }
    val inputKey = remember(events, semantics, report, appFilter, keyword, query, appNames) { Any() }
    val prepared by produceState<Pair<Any, SwissHistoryData>?>(null, inputKey) {
        value = null
        value = inputKey to withContext(Dispatchers.Default) {
            val source = swissHistoryData(events, semantics, report, appFilter, keyword)
            val search = query.trim()
            source.copy(records = source.records.filter { event ->
                search.isBlank() || event.contentText.contains(search, ignoreCase = true) ||
                    event.packageName.contains(search, ignoreCase = true) ||
                    (appNames[event.packageName] ?: swissKnownApps[event.packageName]).orEmpty().contains(search, ignoreCase = true)
            })
        }
    }
    val data = prepared?.takeIf { it.first === inputKey }?.second
    val filtered = data?.records.orEmpty()
    val model = data?.model.orEmpty()
    val format = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault()) }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-history-screen"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SwissFilterChip("全部", appFilter == null, { appFilter = null }, Modifier.testTag("history-app-all"))
                apps.forEach { (pkg, label) -> SwissFilterChip(label, appFilter == pkg, { appFilter = pkg },
                    Modifier.testTag("history-app-$pkg")) }
            }
            if (!keyword.isNullOrBlank()) InputChip(selected = true, onClick = onClearKeyword,
                label = { Text(keyword) }, trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "清除热词筛选", Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("history-keyword-clear"))
            PojianTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("history-search"),
                label = { Text("搜索原文或应用") }, singleLine = true,
                trailingIcon = { if (query.isNotBlank()) PojianIconButton({ query = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } })
        }
        if (filtered.isEmpty()) item { SwissCard { Text(if (data == null) "整理中" else "暂无记录", color = Muted) } }
        items(filtered, key = { it.id }) { event ->
            val claims = model[event.id]?.takeIf { it.validFor(event) }?.claims.orEmpty()
            val matches = data?.matches?.get(event.id).orEmpty()
            SwissCard(onClick = { detailId = event.id }, modifier = Modifier.testTag("history-record-${event.id}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SwissPlatformTag(appNames[event.packageName] ?: swissKnownApps[event.packageName] ?: event.packageName)
                    SwissSourceBadge(matches.isNotEmpty())
                    Spacer(Modifier.weight(1f))
                }
                Text(event.contentText.lineSequence().firstOrNull { it.isNotBlank() } ?: "内容为空",
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${format.format(Instant.ofEpochMilli(event.timestamp))} · ${swissDuration(event)}",
                    style = MaterialTheme.typography.labelSmall, color = Muted, fontFamily = FontFamily.Monospace)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(claims.map { it.stance }.filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { "观点未识别" },
                        style = MaterialTheme.typography.labelSmall, color = DesignTokens.Blue)
                    Text(if (model[event.id]?.validFor(event) == true) "${claims.map { it.evidence }.filter(String::isNotBlank).distinct().size} 条论据" else "论据未提取",
                        style = MaterialTheme.typography.labelSmall, color = Muted, fontFamily = FontFamily.Monospace)
                    Text(swissRecordSource(event), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    }
    events.firstOrNull { it.id == detailId }?.let { event ->
        SwissWorkDetailSheet(event, events, semantics, report, appNames, onDismiss = { detailId = null },
            onRelated = { detailId = it.id }, onDelete = { onDelete(event); detailId = null })
    }
}

private fun swissDuration(event: BrowseEvent): String {
    if (event.sourceType.startsWith("audio_")) {
        val length = event.mediaEndMillis - event.mediaStartMillis
        return if (length > 0) formatDuration(length) else "时长未统计"
    }
    val window = ObservationWindow(0, Long.MAX_VALUE, ZoneId.systemDefault())
    return if (event.sourceType == "accessibility" && window.durationKnown(event)) formatDuration(event.durationMillis) else "时长未统计"
}

private fun swissRecordSource(event: BrowseEvent): String = when (event.sourceType) {
    "screen_ocr" -> "画面文字"
    "visual_model" -> "画面分析"
    "audio_playback", "audio_import", "audio_recording" -> "语音转文字"
    else -> "屏幕文字"
}

@Composable
private fun SwissPlatformTag(text: String) {
    Surface(color = DesignTokens.Ink, contentColor = Color.White, shape = RoundedCornerShape(5.dp)) {
        Text(text, Modifier.widthIn(max = 180.dp).padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SwissSourceBadge(repeated: Boolean) {
    val color = if (repeated) DesignTokens.Amber else Muted
    Surface(color = color.copy(alpha = .08f), shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, color.copy(alpha = .2f))) {
        Text(if (repeated) "论据重复" else "来源未核实", color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable
internal fun SwissWorkDetailSheet(event: BrowseEvent, events: List<BrowseEvent>, semantics: List<EventSemantics>,
    report: CognitiveReport?, appNames: Map<String, String>, onDismiss: () -> Unit,
    onRelated: (BrowseEvent) -> Unit, onDelete: (() -> Unit)? = null) {
    val attached = remember(event, events) { events.filter { it.id == event.id ||
        (it.parentEventId == event.id && it.packageName == event.packageName && it.workKey == event.workKey) } }
    val analyzed = remember(attached, semantics) { attached.flatMap { row ->
        semantics.filter { it.eventId == row.id }.maxByOrNull { it.analyzedAt }?.takeIf { it.validFor(row) }?.claims.orEmpty()
    }.distinct() }
    val observations = remember(attached, report) { report?.claims.orEmpty().filter { claim ->
        attached.any { it.id == claim.eventId && it.packageName == claim.packageName && it.visibleText.contains(claim.quote) }
    } }
    val evidence = remember(analyzed, observations, attached) {
        (observations.flatMap { claim -> claim.evidenceDetails.filter { fragment ->
            attached.any { it.id == claim.eventId && it.visibleText.contains(fragment.text) }
        } } + analyzed.mapNotNull { it.evidence.takeIf(String::isNotBlank)?.let { value -> EvidenceFragment(value, EvidenceType.OTHER) } })
            .distinctBy { it.text }
    }
    val emotions = analyzed.flatMap { it.presentation?.emotions.orEmpty() }.distinct()
    val matches = remember(event, events, report) { swissHistoryMatches(event, report, events) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 720.dp).testTag("swiss-work-detail"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwissPlatformTag(appNames[event.packageName] ?: swissKnownApps[event.packageName] ?: event.packageName)
                        SwissSourceBadge(matches.isNotEmpty())
                    }
                    if (onDelete != null) PojianIconButton(onClick = onDelete, modifier = Modifier.testTag("history-detail-delete")) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除记录")
                    }
                    PojianIconButton(onClick = onDismiss, modifier = Modifier.testTag("history-detail-close")) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                Text(event.contentText.lineSequence().firstOrNull { it.isNotBlank() } ?: "内容为空",
                    style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            item {
                SwissCard {
                    Text("核心观点", style = MaterialTheme.typography.titleSmall)
                    val quotes = (analyzed.map { it.quote } + observations.map { it.quote }).distinct()
                    if (quotes.isEmpty()) Text("未识别", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    else quotes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    HorizontalDivider(color = DesignTokens.Border)
                    Text("情绪与语境", style = MaterialTheme.typography.titleSmall)
                    if (emotions.isEmpty()) Text("未识别", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    emotions.forEach { emotion ->
                        Text("${emotion.category.label} · ${emotion.level.label}", color = DesignTokens.Amber, style = MaterialTheme.typography.labelMedium)
                        Text(emotion.evidence, style = MaterialTheme.typography.bodyMedium)
                        emotion.trigger?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    analyzed.map { it.conditions }.filter(String::isNotBlank).distinct().forEach {
                        Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("核心论据与历史对照", style = MaterialTheme.typography.titleSmall) }
            if (evidence.isEmpty()) item { Text("暂无论据", color = Muted, style = MaterialTheme.typography.bodyMedium) }
            items(evidence.size) { index ->
                val fragment = evidence[index]
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((index + 1).toString().padStart(2, '0'), color = Muted, fontFamily = FontFamily.Monospace)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fragment.text, style = MaterialTheme.typography.bodyMedium)
                        fragment.historyMatches.distinct().forEach { match ->
                            events.firstOrNull { it.id == match.eventId && it.timestamp < event.timestamp && it.visibleText.contains(match.quote) }?.let { source ->
                                Surface(onClick = { onRelated(source) }, color = DesignTokens.Amber.copy(alpha = .07f),
                                    shape = DesignTokens.InnerShape, modifier = Modifier.testTag("history-related-${source.id}")) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("历史记录 #${source.id}", style = MaterialTheme.typography.labelSmall, color = DesignTokens.Amber)
                                        Text(match.quote, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val audio = attached.filter { it.sourceType.startsWith("audio_") }
            if (audio.isNotEmpty()) item {
                SwissCard {
                    Text("语音转文字摘录", style = MaterialTheme.typography.titleSmall)
                    audio.forEach { Text(it.contentText, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item { WorkOriginalContent(event) }
            item { Text("原文", style = MaterialTheme.typography.titleSmall) }
            items(attached, key = { "raw:${it.id}" }) { row ->
                Text(swissRecordSource(row), style = MaterialTheme.typography.labelSmall, color = Muted)
                QuoteBlock(rememberOriginalText(row), fromAudio = row.sourceType.startsWith("audio_"))
            }
        }
    }
}
