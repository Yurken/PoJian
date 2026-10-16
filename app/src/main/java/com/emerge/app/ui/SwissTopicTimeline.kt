package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SwissTopicRow(val title: String, val workCount: Int, val counts: List<Int>)
internal data class SwissTopicTimelineData(val buckets: List<TopicTimeBucket>, val rows: List<SwissTopicRow>)

@Composable
internal fun SwissTopicTimeline(events: List<BrowseEvent>, semantics: List<EventSemantics>, period: StatisticsPeriod,
    now: Long, appNames: Map<String, String>, report: CognitiveReport? = null) {
    val zone = ZoneId.systemDefault()
    val request = remember(events, semantics, period, now, report, zone) { Any() }
    var failed by remember(request) { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val prepared by produceState<Pair<Any, SwissTopicTimelineData>?>(null, request) {
        value = null
        try {
            value = request to withContext(Dispatchers.Default) {
                val buckets = TopicTimeline.build(events, period, now, zone, report, semantics)
                currentCoroutineContext().ensureActive()
                val byTopic = buckets.flatMap { it.topics }.groupBy { it.title }
                val countsByBucket = buckets.map { bucket -> bucket.topics.associate { it.title to it.works.size } }
                val rows = byTopic.map { (title, groups) ->
                    currentCoroutineContext().ensureActive()
                    SwissTopicRow(title, groups.flatMap { it.works }.map { it.workIdentity }.distinct().size,
                        countsByBucket.map { it[title] ?: 0 })
                }.sortedWith(compareByDescending<SwissTopicRow> { it.workCount }.thenBy { it.title })
                SwissTopicTimelineData(buckets, rows)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val data = prepared?.takeIf { it.first === request }?.second
    if (data == null) WhiteCard {
        Text("分时主题分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(if (failed) "分析暂不可用" else "加载中", color = Muted, style = MaterialTheme.typography.bodySmall)
    } else SwissTopicTimelineContent(data, period, appNames, zone) { detailId = it.id }
    detailId?.let { id -> events.firstOrNull { it.id == id }?.let { event ->
        SwissWorkDetailSheet(event, events, semantics, report, appNames,
            onDismiss = { detailId = null }, onRelated = { detailId = it.id })
    } }
}

@Composable
internal fun SwissTopicTimelineContent(data: SwissTopicTimelineData, period: StatisticsPeriod,
    appNames: Map<String, String> = emptyMap(), zone: ZoneId = ZoneId.systemDefault(), onWork: (BrowseEvent) -> Unit = {}) {
    val buckets = data.buckets
    var selectedTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var chartPage by rememberSaveable { mutableIntStateOf(0) }
    val chartPageCount = ((data.rows.size + 3) / 4).coerceAtLeast(1)
    val safeChartPage = chartPage.coerceIn(0, chartPageCount - 1)
    val activeIndex = buckets.indexOfFirst { it.start == selectedTime }.takeIf { it >= 0 }
        ?: buckets.indexOfLast { it.works.isNotEmpty() }.coerceAtLeast(0)
    val active = buckets.getOrNull(activeIndex)
    val border = DesignTokens.Border
    val ink = DesignTokens.Blue
    fun select(index: Int, topic: String? = selectedTopic) {
        buckets.getOrNull(index)?.let { selectedTime = it.start; selectedTopic = topic }
    }
    fun interval(bucket: TopicTimeBucket): String {
        val start = Instant.ofEpochMilli(bucket.start).atZone(zone)
        return if (period.dailyBuckets) start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        else start.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " – " +
            Instant.ofEpochMilli(bucket.end).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    WhiteCard(Modifier.testTag("swiss-topic-timeline")) {
        Text("分时主题分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (data.rows.isEmpty()) Text("暂无话题", color = Muted, style = MaterialTheme.typography.bodySmall)
        else {
            data.rows.drop(safeChartPage * 4).take(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(84.dp)) {
                        Text(row.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${row.workCount}篇", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    val maximum = row.counts.maxOrNull()?.coerceAtLeast(1) ?: 1
                    Canvas(Modifier.weight(1f).height(48.dp).testTag("swiss-topic-plot-${row.title}")
                        .pointerInput(data, row.title) { detectTapGestures { point ->
                            swissChartSelection(point.x, size.width.toFloat(), buckets.size)?.let { select(it, row.title) }
                        } }.semantics {
                            contentDescription = row.title
                            stateDescription = active?.let { "${interval(it)}，${row.counts.getOrElse(activeIndex) { 0 }}篇" }.orEmpty()
                            onClick("查看时段内容") { select(activeIndex, row.title); true }
                            customActions = buildList {
                                if (activeIndex > 0) add(CustomAccessibilityAction("上一时段") { select(activeIndex - 1, row.title); true })
                                if (activeIndex < buckets.lastIndex) add(CustomAccessibilityAction("下一时段") { select(activeIndex + 1, row.title); true })
                            }
                        }) {
                        if (buckets.isNotEmpty()) {
                            val step = size.width / buckets.size
                            val gap = minOf(2.dp.toPx(), step * .15f)
                            row.counts.forEachIndexed { index, count ->
                                val position = Offset(index * step + gap / 2, 8.dp.toPx())
                                val cell = Size(step - gap, size.height - 16.dp.toPx())
                                drawRect(if (count == 0) border.copy(alpha = .35f)
                                    else ink.copy(alpha = .25f + .75f * count / maximum), position, cell)
                                if (index == activeIndex) drawRect(DesignTokens.Ink, position, cell, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.width(84.dp))
                Box(Modifier.weight(1f)) { SwissChartAxis(buckets.map { it.label to null }, activeIndex) }
            }
            if (chartPageCount > 1) SwissChartSelectionHeader("${safeChartPage + 1} / $chartPageCount", "上一页", "下一页",
                safeChartPage > 0, safeChartPage < chartPageCount - 1,
                { chartPage = safeChartPage - 1 }, { chartPage = safeChartPage + 1 }, "swiss-topic-pages")
        }
        if (active != null && data.rows.isNotEmpty()) {
            HorizontalDivider(color = border)
            SwissChartSelectionHeader(interval(active), "上一时段", "下一时段", activeIndex > 0, activeIndex < buckets.lastIndex,
                { select(activeIndex - 1) }, { select(activeIndex + 1) }, "swiss-topic-time")
            Text("${active.works.size}篇", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("swiss-topic-work-count"))
            val topicTitle = selectedTopic?.takeIf { selected -> data.rows.any { it.title == selected } }
                ?: active.topics.firstOrNull()?.title ?: data.rows.first().title
            val topicIndex = data.rows.indexOfFirst { it.title == topicTitle }
            val topic = active.topics.firstOrNull { it.title == topicTitle }
            val works = topic?.works.orEmpty()
            if (data.rows.size > 1) SwissChartSelectionHeader("$topicTitle · ${works.size}篇 · ${topicIndex + 1}/${data.rows.size}",
                "上一主题", "下一主题", topicIndex > 0, topicIndex < data.rows.lastIndex,
                { selectedTopic = data.rows[topicIndex - 1].title },
                { selectedTopic = data.rows[topicIndex + 1].title }, "swiss-topic-selection")
            else Text("$topicTitle · ${works.size}篇", style = MaterialTheme.typography.titleSmall)
            if (works.isEmpty()) Text("该时段暂无内容", color = Muted, style = MaterialTheme.typography.bodySmall)
            else {
                var workPage by remember(active, topic) { mutableIntStateOf(0) }
                val workPages = ((works.size + 4) / 5).coerceAtLeast(1)
                works.drop(workPage * 5).take(5).forEach { work ->
                    Surface(onClick = { onWork(work.record) }, shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, border), color = DesignTokens.Surface,
                        modifier = Modifier.fillMaxWidth().testTag("swiss-topic-work-${work.record.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(work.record.contentText.ifBlank { work.record.visibleText }, maxLines = 3,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(appNames[work.record.packageName] ?: platformName(work.record.packageName),
                                color = Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (workPages > 1) SwissChartSelectionHeader("${workPage + 1} / $workPages", "上一页", "下一页",
                    workPage > 0, workPage < workPages - 1, { workPage-- }, { workPage++ }, "swiss-topic-works")
            }
        }
    }
}
