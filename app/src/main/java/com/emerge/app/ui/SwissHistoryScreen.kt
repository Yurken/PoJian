@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SwissHistoryPage(val index: Int, val count: Int, val records: List<BrowseEvent>)

internal fun swissHistoryPage(records: List<BrowseEvent>, requestedIndex: Int): SwissHistoryPage {
    val pageSize = 20
    val count = ((records.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val index = requestedIndex.coerceIn(0, count - 1)
    val start = index * pageSize
    return SwissHistoryPage(index, count, records.subList(start, minOf(start + pageSize, records.size)))
}

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
    PojianFilterChip(selected = selected, onClick = onClick, modifier = modifier,
        label = { Text(label) })
}

@Composable
internal fun SwissHistoryScreen(events: List<BrowseEvent>, semantics: List<EventSemantics>, report: CognitiveReport?,
    appNames: Map<String, String>, keyword: String?, onClearKeyword: () -> Unit,
    onDelete: (BrowseEvent) -> Unit, modifier: Modifier = Modifier) {
    var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pageIndex by rememberSaveable(appFilter, keyword, query) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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
    val page = remember(filtered, pageIndex) { swissHistoryPage(filtered, pageIndex) }
    // A pending refresh is not an empty result and must not erase the selected page.
    LaunchedEffect(data, page.index) { if (data != null) pageIndex = page.index }
    fun changePage(index: Int) {
        pageIndex = index
        scope.launch { listState.scrollToItem(0) }
    }
    val model = data?.model.orEmpty()
    val format = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault()) }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-history-screen"), state = listState, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SwissFilterChip("全部", appFilter == null, { appFilter = null }, Modifier.testTag("history-app-all"))
                    apps.forEach { (pkg, label) -> SwissFilterChip(label, appFilter == pkg, { appFilter = pkg },
                        Modifier.testTag("history-app-$pkg")) }
                }
                if (!keyword.isNullOrBlank()) InputChip(selected = true, onClick = onClearKeyword,
                    label = { Text("热词：$keyword") },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "清除热词筛选", Modifier.size(16.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("history-keyword-clear"))
                PojianTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("history-search"),
                    label = { Text("搜索原文或应用") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (query.isNotBlank()) PojianIconButton({ query = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } })
            }
        }
        item("section-header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
                .semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(5.dp), color = DesignTokens.Ink, contentColor = Color.White) {
                    Text("01", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall)
                }
                Text("浏览记录明细", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = Ink)
                Text(if (data == null) "—" else "${filtered.size} 条", style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
        if (page.count > 1) item("pages-top") {
            SwissChartSelectionHeader("${page.index + 1} / ${page.count}", "上一页", "下一页",
                page.index > 0, page.index < page.count - 1,
                { changePage(page.index - 1) }, { changePage(page.index + 1) }, "history-pages-top")
        }
        if (filtered.isEmpty()) item("empty") {
            SwissCard { Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text(if (data == null) "整理中" else "暂无记录", color = Muted,
                    style = MaterialTheme.typography.bodyMedium)
            } }
        }
        items(page.records, key = { it.id }) { event ->
            val claims = model[event.id]?.takeIf { it.validFor(event) }?.claims.orEmpty()
            val matches = data?.matches?.get(event.id).orEmpty()
            SwissCard(onClick = { detailId = event.id }, modifier = Modifier.testTag("history-record-${event.id}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SwissPlatformTag(appNames[event.packageName] ?: swissKnownApps[event.packageName] ?: event.packageName)
                    if (matches.isNotEmpty()) SwissRepeatedEvidenceBadge()
                    Spacer(Modifier.weight(1f))
                    Text("${format.format(Instant.ofEpochMilli(event.timestamp))} · ${swissDuration(event)}",
                        style = MaterialTheme.typography.labelSmall, color = Muted, fontFamily = FontFamily.Monospace)
                }
                Text(event.contentText.lineSequence().firstOrNull { it.isNotBlank() } ?: "内容为空",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.6f), thickness = 0.75.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(claims.map { it.stance }.filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { "观点未识别" },
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                            color = DesignTokens.Blue)
                        Text(if (model[event.id]?.validFor(event) == true) {
                            "${claims.map { it.evidence }.filter(String::isNotBlank).distinct().size} 条论据"
                        } else "论据未提取",
                            style = MaterialTheme.typography.labelSmall, color = Muted, fontFamily = FontFamily.Monospace)
                        Text(swissRecordSource(event), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = Muted)
                }
            }
        }
        if (page.count > 1) item("pages-bottom") {
            SwissChartSelectionHeader("${page.index + 1} / ${page.count}", "上一页", "下一页",
                page.index > 0, page.index < page.count - 1,
                { changePage(page.index - 1) }, { changePage(page.index + 1) }, "history-pages-bottom")
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
        Text(text, Modifier.widthIn(max = 180.dp).padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SwissRepeatedEvidenceBadge() {
    val color = DesignTokens.Amber
    Surface(color = color.copy(alpha = .08f), shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, color.copy(alpha = .22f))) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(color))
            Text("论据重复", color = color,
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
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
    val numberStyle = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum")
    val numberMeasurer = rememberTextMeasurer()
    val numberWidth = with(LocalDensity.current) {
        numberMeasurer.measure(evidence.size.coerceAtLeast(1).toString().padStart(2, '0'), numberStyle)
            .size.width.toDp()
    }.coerceAtLeast(24.dp)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 720.dp).testTag("swiss-work-detail"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwissPlatformTag(appNames[event.packageName] ?: swissKnownApps[event.packageName] ?: event.packageName)
                    if (matches.isNotEmpty()) SwissRepeatedEvidenceBadge()
                    Spacer(Modifier.weight(1f))
                    if (onDelete != null) PojianIconButton(onClick = onDelete, modifier = Modifier.testTag("history-detail-delete")) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除记录", tint = DesignTokens.Rose)
                    }
                    PojianIconButton(onClick = onDismiss, modifier = Modifier.testTag("history-detail-close")) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(event.contentText.lineSequence().firstOrNull { it.isNotBlank() } ?: "内容为空",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Ink,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            item("claims-emotions") {
                SwissCard {
                    Text("核心观点", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val quotes = (analyzed.map { it.quote } + observations.map { it.quote }).distinct()
                    if (quotes.isEmpty()) Text("未识别", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    else quotes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink) }
                    HorizontalDivider(color = DesignTokens.Border)
                    Text("情绪与语境", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (emotions.isEmpty()) Text("未识别", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    emotions.forEach { emotion ->
                        Text("${emotion.category.label} · ${emotion.level.label}", color = DesignTokens.Amber,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(emotion.evidence, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        emotion.trigger?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    analyzed.map { it.conditions }.filter(String::isNotBlank).distinct().forEach {
                        Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item("evidence-title") { Text("核心论据与历史对照", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold) }
            if (evidence.isEmpty()) item("evidence-empty") {
                Text("暂无论据", color = Muted, style = MaterialTheme.typography.bodyMedium) }
            items(evidence.size) { index ->
                val fragment = evidence[index]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((index + 1).toString().padStart(2, '0'), color = Muted, style = numberStyle,
                        textAlign = TextAlign.End, modifier = Modifier.width(numberWidth).alignByBaseline())
                    Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fragment.text, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        fragment.historyMatches.distinct().forEach { match ->
                            events.firstOrNull { it.id == match.eventId && it.timestamp < event.timestamp && it.visibleText.contains(match.quote) }?.let { source ->
                                Surface(onClick = { onRelated(source) }, color = DesignTokens.Amber.copy(alpha = .07f),
                                    shape = DesignTokens.InnerShape, border = BorderStroke(1.dp, DesignTokens.Amber.copy(alpha = 0.22f)),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("history-related-${source.id}")) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("历史记录 #${source.id}", style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                            color = DesignTokens.Amber)
                                        Text(match.quote, style = MaterialTheme.typography.bodySmall, color = Ink)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val audio = attached.filter { it.sourceType.startsWith("audio_") }
            if (audio.isNotEmpty()) item("audio") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.width(numberWidth))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = DesignTokens.Border)
                        Text("语音转文字摘录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        audio.forEach { Text(it.contentText, style = MaterialTheme.typography.bodyMedium, color = Ink) }
                    }
                }
            }
            item("original") { WorkOriginalContent(event) }
            item("raw-title") { Text("原文", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(attached, key = { "raw:${it.id}" }) { row ->
                Text(swissRecordSource(row), style = MaterialTheme.typography.labelSmall, color = Muted)
                QuoteBlock(rememberOriginalText(row), fromAudio = row.sourceType.startsWith("audio_"))
            }
        }
    }
}
