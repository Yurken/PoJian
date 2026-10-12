package com.emerge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ContentWord
import com.emerge.app.analysis.recentContentWords
import com.emerge.app.analysis.RecentContentGroup
import com.emerge.app.analysis.RecentContentPoint
import com.emerge.app.analysis.recentContentGroups
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RecentContentCloud(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, appNames: Map<String, String> = emptyMap()) {
    val words by produceState<List<ContentWord>?>(null, events, period, now, semantics) {
        value = null
        value = withContext(Dispatchers.Default) { recentContentWords(events, period, now, semantics) }
    }
    var selected by rememberSaveable(period) { mutableStateOf<String?>(null) }
    WhiteCard(Modifier.testTag("recent-content-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("近期接触", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        val current = words
        when {
            current == null -> Text("正在整理近期内容…", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            current.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("暂无关键词", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
            else -> ContentWordCloud(current) { selected = it.text }
        }
    }
    selected?.let { word ->
        ContentWordDetails(word, events, period, now, semantics, appNames) { selected = null }
    }
}

@Composable
private fun ContentWordCloud(words: List<ContentWord>, onWord: (ContentWord) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val colors = if (dark) listOf(Color(0xFF93C5FD), Color(0xFF5EEAD4), Color(0xFFFCD34D),
        Color(0xFFC4B5FD), Color(0xFF7DD3FC)) else listOf(Color(0xFF1E40AF), Color(0xFF0F766E),
        Color(0xFFB45309), Color(0xFF4338CA), Color(0xFF0369A1))
    val glow = scheme.primaryContainer.copy(alpha = if (dark) .65f else .8f)
    val edge = scheme.surfaceContainerLow.copy(alpha = .2f)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val limit = when {
            fontScale > 1.8f || maxWidth < 240.dp -> 4
            fontScale > 1.2f -> 6
            maxWidth < 280.dp -> 8
            else -> 12
        }
        val columns = if (limit == 12) 3 else 2
        // Counts are descending upstream; ties retain the existing recency order.
        val visible = words.take(limit)
        val rows = visible.chunked(columns)
        // Bounded rows replace the old expanding spiral. Full labels remain available
        // to accessibility and in the collection title when visual text is ellipsized.
        val height = (rows.size * (64f * fontScale).coerceIn(64f, 120f) + 16f).coerceIn(112f, 280f).dp
        Column(Modifier.fillMaxWidth().height(height).testTag("recent-content-cloud")
            .clip(RoundedCornerShape(14.dp)).drawWithCache {
                val brush = Brush.radialGradient(listOf(glow, edge),
                    center = Offset(size.width * .5f, size.height * .46f),
                    radius = maxOf(size.width, size.height) * .65f)
                onDrawBehind { drawRect(brush) }
            }.padding(8.dp)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { columnIndex, word ->
                        val index = rowIndex * columns + columnIndex
                        val wordColor = readableCloudColor(colors[index % colors.size], .9f,
                            glow.compositeOver(scheme.surface))
                        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = "查看相关内容") { onWord(word) }
                            .padding(horizontal = 6.dp, vertical = 4.dp).clearAndSetSemantics {
                                contentDescription = "${word.text}，${word.recordCount} 条记录"
                                role = Role.Button
                                onClick(label = "查看相关内容") { onWord(word); true }
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(word.recordCount.toString(), modifier = Modifier.align(Alignment.End),
                                color = wordColor, fontSize = 11.sp, lineHeight = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text(word.text, color = wordColor, fontSize = 14.sp, lineHeight = 18.sp,
                                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Preserve foreground/background depth without washing out small labels in either theme. */
private fun readableCloudColor(base: Color, preferredAlpha: Float, background: Color): Color {
    val backgroundLuminance = background.luminance()
    var alpha = preferredAlpha.coerceIn(0f, 1f)
    while (alpha < 1f) {
        val foregroundLuminance = base.copy(alpha = alpha).compositeOver(background).luminance()
        val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + .05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + .05f)
        if (contrast >= 4.5f) break
        alpha = (alpha + .05f).coerceAtMost(1f)
    }
    return base.copy(alpha = alpha)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentWordDetails(word: String, events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, appNames: Map<String, String>, onDismiss: () -> Unit) {
    val groups by produceState<List<RecentContentGroup>?>(null, word, events, period, now, semantics) {
        value = null
        value = withContext(Dispatchers.Default) { recentContentGroups(word, events, period, now, semantics) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(word, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                PojianTextButton(onClick = onDismiss) { Text("关闭") }
            }
            LazyColumn(Modifier.weight(1f).testTag("recent-content-details"),
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val current = groups
                item {
                    MetadataText(when {
                        current == null -> "正在整理相关内容…"
                        current.isEmpty() -> "暂无相关内容"
                        else -> "${period.label} · ${current.flatMap { it.records }.distinctBy { it.id }.size} 条相关记录"
                    })
                }
                items(current.orEmpty(), key = { it.key }) { group ->
                    RelatedContentGroup(group, word, appNames)
                }
            }
        }
    }
}

@Composable
private fun RelatedContentGroup(group: RecentContentGroup, word: String, appNames: Map<String, String>) {
    var expanded by rememberSaveable(word, group.key) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("recent-content-group-${group.key}"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.title, Modifier.weight(1f).semantics { heading() }, color = Ink,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                MetadataText("${group.records.size} 条记录")
            }
            (if (expanded) group.points else group.points.take(3)).forEachIndexed { index, point ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                key(point.key) { RelatedContentPoint(point, word, group.key, appNames) }
            }
            if (group.points.size > 3) PojianTextButton(onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End)) {
                Text(if (expanded) "收起" else "展开全部（${group.points.size}）")
            }
        }
    }
}

@Composable
private fun RelatedContentPoint(point: RecentContentPoint, word: String, groupKey: String, appNames: Map<String, String>) {
    var raw by rememberSaveable(word, groupKey, point.key) { mutableStateOf(false) }
    var sourceLimit by rememberSaveable(word, groupKey, point.key) { mutableIntStateOf(3) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(point.quote, color = Ink, style = MaterialTheme.typography.bodyMedium)
        point.records.groupBy { it.packageName to it.sourceType }.forEach { (_, records) ->
            val event = records.first()
            SourceAppText(event.packageName.takeUnless { event.sourceType == "audio_import" },
                "${appNames[event.packageName] ?: event.packageName} · ${records.size} 条记录")
        }
        PojianTextButton(onClick = { raw = !raw; sourceLimit = 3 }, modifier = Modifier.align(Alignment.End)) {
            Text(if (raw) "收起原文" else "查看原文 ›")
        }
        if (raw) {
            point.records.take(sourceLimit).forEach { event ->
                Column(Modifier.testTag("recent-content-original-${event.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val time = Instant.ofEpochMilli(event.timestamp).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                    SourceAppText(event.packageName.takeUnless { event.sourceType == "audio_import" },
                        "${appNames[event.packageName] ?: event.packageName} · $time")
                    QuoteBlock(event.visibleText)
                }
            }
            if (sourceLimit < point.records.size) PojianTextButton(onClick = { sourceLimit += 3 }) { Text("更多原文") }
        }
    }
}
