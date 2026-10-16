package com.emerge.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.ContentWord
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.RecentContentGroup
import com.emerge.app.analysis.RecentContentPoint
import com.emerge.app.analysis.recentContentCategories
import com.emerge.app.analysis.recentContentGroups
import com.emerge.app.analysis.recentContentWords
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class RecentCloudRequest(
    val events: List<BrowseEvent>,
    val period: StatisticsPeriod,
    val now: Long,
    val semantics: List<EventSemantics>,
    val category: String?
)

private data class RecentCloudContent(
    val request: RecentCloudRequest,
    val categories: List<String>,
    val category: String?,
    val words: List<ContentWord>
)

@Composable
internal fun RecentContentCloud(
    events: List<BrowseEvent>,
    period: StatisticsPeriod,
    now: Long,
    semantics: List<EventSemantics>,
    appNames: Map<String, String> = emptyMap(),
    onWordSelected: ((String) -> Unit)? = null
) {
    var category by rememberSaveable(period) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(period, category) { mutableStateOf(false) }
    var selected by rememberSaveable(period, category) { mutableStateOf<String?>(null) }
    val request = remember(events, period, now, semantics, category) {
        RecentCloudRequest(events, period, now, semantics, category)
    }
    val content by produceState<RecentCloudContent?>(null, request) {
        value = withContext(Dispatchers.Default) {
            val categories = recentContentCategories(request.events, request.period, request.now, request.semantics)
            val active = request.category?.takeIf { it in categories }
            RecentCloudContent(
                request = request,
                categories = categories,
                category = active,
                words = recentContentWords(request.events, request.period, request.now, request.semantics, category = active)
            )
        }
    }
    // Keep the previous layout only within its requested or resolved filter scope.
    // The resolved scope also covers a removed category falling back to all content.
    val current = content?.takeIf {
        it.request.period == period && (it.request.category == category || it.category == category)
    }
    val isCurrent = content?.request == request
    val collapsedLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 3
    LaunchedEffect(content, request) {
        if (isCurrent && current != null) {
            category = current.category
            if (current.words.none { it.text == selected }) selected = null
        }
    }
    WhiteCard(Modifier.testTag("recent-content-card").animateContentSize(tween(220))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = DesignTokens.Ink,
                    contentColor = Color.White
                ) {
                    Text(
                        text = "05",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    text = "近期热词",
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }
                )
            }
            if (current != null && current.words.size > collapsedLines) {
                PojianTextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("recent-content-expand")
                ) {
                    Text(if (expanded) "收起" else "展开（${current.words.size}）")
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Text(
                    text = "KEYWORDS",
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }
        if (current != null && current.categories.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PojianFilterChip(
                    selected = current.category == null,
                    onClick = { category = null },
                    modifier = Modifier.testTag("recent-content-category-all"),
                    label = { Text("全部") }
                )
                current.categories.forEach { title ->
                    PojianFilterChip(
                        selected = current.category == title,
                        onClick = { category = title },
                        modifier = Modifier.widthIn(max = 200.dp).testTag("recent-content-category-$title"),
                        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
        when {
            current == null -> Text("正在整理近期内容…", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            current.words.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("暂无关键词", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            else -> ContentWordCloud(current.words, expanded, enabled = isCurrent) {
                if (onWordSelected != null) onWordSelected(it.text) else selected = it.text
            }
        }
    }
    selected?.let { word ->
        val detailCategory = if (current != null) current.category else category
        ContentWordDetails(word, events, period, now, semantics, appNames, detailCategory) { selected = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentWordCloud(
    words: List<ContentWord>,
    expanded: Boolean,
    enabled: Boolean,
    onWord: (ContentWord) -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    val maxCount = words.maxOfOrNull { it.recordCount }?.coerceAtLeast(1) ?: 1
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chipWidth = minOf(maxWidth, 208.dp)
        // FlowRow drops whole overflowing rows: no clipped labels or partial touch targets.
        FlowRow(
            modifier = Modifier.fillMaxWidth().testTag("recent-content-cloud"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            maxLines = if (expanded) Int.MAX_VALUE else if (fontScale > 1.3f) 2 else 3
        ) {
            words.forEach { word ->
                key(word.text) {
                    ContentWordChip(
                        word = word,
                        ratio = word.recordCount.toFloat() / maxCount,
                        modifier = Modifier.widthIn(max = chipWidth),
                        enabled = enabled
                    ) { onWord(word) }
                }
            }
        }
    }
}

@Composable
private fun ContentWordChip(
    word: ContentWord,
    ratio: Float,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dotColor = when {
        ratio >= .75f -> DesignTokens.Rose
        ratio >= .45f -> DesignTokens.Blue
        else -> DesignTokens.MutedText
    }
    // Size the painted chip from its contents, while keeping the taller touch area outside it.
    Box(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .heightIn(min = 48.dp)
            .clip(DesignTokens.ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看相关内容", onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "${word.text}，${word.recordCount} 条记录"
                role = Role.Button
                if (enabled) onClick(label = "查看相关内容") { onClick(); true } else disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = scheme.surfaceVariant.copy(alpha = .65f),
            shape = DesignTokens.SegmentShape,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f))
        ) {
            Row(
                modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
                Text(
                    text = word.text,
                    modifier = Modifier.weight(1f, fill = false),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 13.sp,
                    fontWeight = if (ratio >= .75f) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = word.recordCount.toString(),
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentWordDetails(
    word: String,
    events: List<BrowseEvent>,
    period: StatisticsPeriod,
    now: Long,
    semantics: List<EventSemantics>,
    appNames: Map<String, String>,
    category: String?,
    onDismiss: () -> Unit
) {
    val groups by key(word, events, period, now, semantics, category) {
        produceState<List<RecentContentGroup>?>(null) {
            value = withContext(Dispatchers.Default) {
                recentContentGroups(word, events, period, now, semantics, category = category)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                PojianTextButton(onClick = onDismiss) { Text("关闭") }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("recent-content-details"),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val current = groups
                item {
                    MetadataText(
                        when {
                            current == null -> "正在整理相关内容…"
                            current.isEmpty() -> "暂无相关内容"
                            else -> "${period.label} · ${current.flatMap { it.records }.distinctBy { it.id }.size} 条相关记录"
                        }
                    )
                }
                items(current.orEmpty(), key = { it.key }) { group ->
                    RelatedContentGroup(group, word, appNames)
                }
            }
        }
    }
}

@Composable
private fun RelatedContentGroup(
    group: RecentContentGroup,
    word: String,
    appNames: Map<String, String>
) {
    var expanded by rememberSaveable(word, group.key) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, DesignTokens.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("recent-content-group-${group.key}"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = group.title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                MetadataText("${group.records.size} 条记录")
            }
            (if (expanded) group.points else group.points.take(3)).forEachIndexed { index, point ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
                key(point.key) { RelatedContentPoint(point, word, group.key, appNames) }
            }
            if (group.points.size > 3) {
                PojianTextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(if (expanded) "收起" else "展开全部（${group.points.size}）")
                }
            }
        }
    }
}

@Composable
private fun RelatedContentPoint(
    point: RecentContentPoint,
    word: String,
    groupKey: String,
    appNames: Map<String, String>
) {
    var raw by rememberSaveable(word, groupKey, point.key) { mutableStateOf(false) }
    var sourceLimit by rememberSaveable(word, groupKey, point.key) { mutableIntStateOf(3) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(point.quote, color = Ink, style = MaterialTheme.typography.bodyMedium)
        point.records.groupBy { it.packageName to it.sourceType }.forEach { (_, records) ->
            val event = records.first()
            SourceAppText(
                packageName = event.packageName.takeUnless { event.sourceType == "audio_import" },
                text = "${appNames[event.packageName] ?: event.packageName} · ${records.size} 条记录"
            )
        }
        PojianTextButton(
            onClick = { raw = !raw; sourceLimit = 3 },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(if (raw) "收起原文" else "查看原文 ›")
        }
        if (raw) {
            point.records.take(sourceLimit).forEach { event ->
                Column(
                    modifier = Modifier.testTag("recent-content-original-${event.id}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val time = Instant.ofEpochMilli(event.timestamp).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                    SourceAppText(
                        packageName = event.packageName.takeUnless { event.sourceType == "audio_import" },
                        text = "${appNames[event.packageName] ?: event.packageName} · $time"
                    )
                    QuoteBlock(rememberOriginalText(event), fromAudio = event.sourceType.startsWith("audio_"))
                }
            }
            if (sourceLimit < point.records.size) {
                PojianTextButton(onClick = { sourceLimit += 3 }) { Text("更多原文") }
            }
        }
    }
}
