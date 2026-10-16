@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorPolicy
import com.emerge.app.monitoring.SupplementKnownArgument
import com.emerge.app.monitoring.SupplementRequest
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SwissViewpointsData(val workCount: Int, val topics: List<String>, val shares: List<ViewpointStanceShare>,
    val candidates: List<ViewpointShare>, val trend: List<SwissStanceDay>, val requests: List<Pair<TopicDigest, SupplementRequest>>,
    val repeatedWorks: Map<String, Int>)

private fun prepareSwissViewpoints(events: List<BrowseEvent>, semantics: List<EventSemantics>, report: CognitiveReport?,
    period: StatisticsPeriod, now: Long, zone: ZoneId, selectedTopic: String?): SwissViewpointsData {
    val buckets = ViewpointTimeline.build(events, period, now, zone, report, semantics)
    val works = ViewpointTimeline.details(buckets.flatMap { it.items })
    val topics = (works.flatMap { it.claims }.map { it.topic }.filter(String::isNotBlank) + listOfNotNull(selectedTopic)).distinct()
    val selected = swissTopicItems(works, selectedTopic)
    val groups = ViewpointTimeline.groups(selected)
    val shares = viewpointSpectrum(ViewpointDistribution.topics(groups))
    val candidates = groups.filter { it.items.any { item -> item.claims.any { claim -> claim.quote.isNotBlank() } } }.sortedByDescending { it.items.size }
    val week = ViewpointTimeline.build(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics)
    val trend = week.map { swissStanceDay(it, selectedTopic, zone) }
    val byId = events.associateBy { it.id }
    val repeatedRecords = report?.claims.orEmpty().filter { claim ->
        val event = byId[claim.eventId]
        event != null && event.packageName == claim.packageName && event.visibleText.contains(claim.quote) &&
            claim.evidenceDetails.any { fragment -> event.visibleText.contains(fragment.text) && fragment.historyMatches.any { match ->
                byId[match.eventId]?.let { it.timestamp < event.timestamp && it.visibleText.contains(match.quote) } == true
            } }
    }.map { it.eventId }.toSet()
    val repeatedWorks = candidates.associate { group -> group.key to group.items.count { item -> item.records.any { it.id in repeatedRecords } } }
    val requests = report?.topicDigests.orEmpty().filter { selectedTopic == null || it.topic == selectedTopic || it.subject == selectedTopic }
        .mapNotNull { topic ->
            val viewpoint = topic.viewpoints.firstOrNull() ?: return@mapNotNull null
            val known = viewpoint.observations.filter { claim -> byId[claim.eventId]?.let {
                it.packageName == claim.packageName && it.visibleText.contains(claim.quote)
            } == true }.distinctBy { it.quote to it.evidence }.take(6)
            if (known.isEmpty()) return@mapNotNull null
            topic to SupplementRequest(MonitorPolicy.hash("${report!!.generatedAt}|${report.start}|${report.end}|${topic.key}|${viewpoint.key}"),
                topic.topic, topic.subject, viewpoint.dimension, viewpoint.conditions,
                known.map { SupplementKnownArgument(it.quote, it.evidenceDetails.firstOrNull()?.text.orEmpty()) })
        }
    return SwissViewpointsData(selected.size, topics, shares, candidates, trend, requests, repeatedWorks)
}

@Composable
internal fun SwissViewpointsScreen(events: List<BrowseEvent>, semantics: List<EventSemantics>, report: CognitiveReport?,
    period: StatisticsPeriod, now: Long, appNames: Map<String, String>, selectedTopic: String?,
    onTopicSelected: (String?) -> Unit, onSupplement: (SupplementRequest) -> Unit, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val scopedReport = report?.takeIf { it.period == period.label }
    val inputKey = remember(events, semantics, scopedReport, period, now, selectedTopic) { Any() }
    val prepared by produceState<Pair<Any, SwissViewpointsData>?>(null, inputKey) {
        value = null
        value = inputKey to withContext(Dispatchers.Default) {
            prepareSwissViewpoints(events, semantics, scopedReport, period, now, zone, selectedTopic)
        }
    }
    val data = prepared?.takeIf { it.first === inputKey }?.second
    val topics = (data?.topics.orEmpty() + listOfNotNull(selectedTopic)).distinct()
    val shares = data?.shares.orEmpty()
    val candidates = data?.candidates.orEmpty()
    val trend = data?.trend.orEmpty()
    val requests = data?.requests.orEmpty()
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    LazyColumn(modifier.fillMaxSize().testTag("swiss-viewpoints-screen"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SwissFilterChip("全部", selectedTopic == null, { onTopicSelected(null) }, Modifier.testTag("viewpoints-topic-all"))
                topics.forEach { topic -> SwissFilterChip(topic, selectedTopic == topic, { onTopicSelected(topic) },
                    Modifier.testTag("viewpoints-topic-$topic")) }
            }
        }
        item {
            SwissCard {
                Text(selectedTopic ?: "观点分布", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(DesignTokens.InnerShape)
                    .background(DesignTokens.Border.copy(alpha = .22f)).padding(vertical = 16.dp, horizontal = 8.dp)) {
                    SwissViewpointMetric("已浏览内容", data?.let { "${it.workCount} 篇" } ?: "—", Modifier.weight(1f), emphasized = true)
                    VerticalDivider(Modifier.fillMaxHeight(), color = DesignTokens.Border)
                    SwissViewpointMetric("独立新闻源", "未核实", Modifier.weight(1f))
                }
                SwissStanceDistribution(shares)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwissRepresentative("高频接收观点", candidates.firstOrNull(), DesignTokens.Rose,
                        candidates.firstOrNull()?.let { data?.repeatedWorks?.get(it.key) } ?: 0, Modifier.weight(1f)) { detailId = it.record.id }
                    SwissRepresentative("较少接触观点", candidates.lastOrNull()?.takeIf { candidates.size > 1 }, DesignTokens.Blue,
                        candidates.lastOrNull()?.let { data?.repeatedWorks?.get(it.key) } ?: 0, Modifier.weight(1f)) { detailId = it.record.id }
                }
            }
        }
        item { SwissStanceTimeline(trend) }
        item {
            SwissCard {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(DesignTokens.Ink, RoundedCornerShape(2.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("信息盲区与缺失事实补全", style = MaterialTheme.typography.titleSmall)
                        if (requests.isEmpty()) Text("暂无可核查观点", style = MaterialTheme.typography.bodySmall, color = Muted)
                        requests.forEach { (topic, request) ->
                            PojianTextButton(onClick = { onSupplement(request) }, modifier = Modifier.fillMaxWidth()
                                .testTag("viewpoints-supplement-${topic.key}")) {
                                Text(topic.topic + topic.subject.takeIf { it.isNotBlank() && it != topic.topic }?.let { " · $it" }.orEmpty(),
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "补全缺失事实", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        items(candidates, key = { it.key }) { group ->
            SwissCard(onClick = { detailId = group.items.firstOrNull()?.record?.id }, modifier = Modifier.testTag("viewpoints-claim-${group.key}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("核心观点", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("${group.items.size} 篇", color = Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                Text(group.items.firstNotNullOfOrNull { it.quote } ?: group.label, style = MaterialTheme.typography.bodyMedium)
                val example = group.items.firstOrNull()?.claims?.firstOrNull()
                example?.evidence?.takeIf(String::isNotBlank)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                val sources = group.items.map { appNames[it.record.packageName] ?: swissKnownApps[it.record.packageName] ?: it.record.packageName }.distinct()
                Text(sources.joinToString(" · "), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    events.firstOrNull { it.id == detailId }?.let { event ->
        SwissWorkDetailSheet(event, events, semantics, scopedReport, appNames, { detailId = null }, { detailId = it.id })
    }
}

@Composable
private fun SwissViewpointMetric(label: String, value: String, modifier: Modifier, emphasized: Boolean = false) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = if (emphasized) DesignTokens.Ink else Muted)
    }
}

internal fun swissStanceColor(stance: ViewpointStance): Color = when (stance) {
    ViewpointStance.SUPPORT -> DesignTokens.Blue
    ViewpointStance.NEUTRAL -> Color(0xFFA1A1AA)
    ViewpointStance.QUESTION -> DesignTokens.Rose
    ViewpointStance.MIXED -> DesignTokens.Amber
    ViewpointStance.UNKNOWN -> DesignTokens.Border
}

@Composable
internal fun SwissStanceDistribution(shares: List<ViewpointStanceShare>) {
    val order = listOf(ViewpointStance.QUESTION, ViewpointStance.NEUTRAL, ViewpointStance.SUPPORT,
        ViewpointStance.MIXED, ViewpointStance.UNKNOWN)
    val sorted = shares.sortedBy { order.indexOf(it.stance) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(DesignTokens.Border),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            sorted.forEach { share -> Box(Modifier.weight(share.count.toFloat().coerceAtLeast(1f)).fillMaxHeight()
                .background(swissStanceColor(share.stance))) }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            sorted.forEach { share ->
                Text("${share.stance.label} ${share.percent.roundToInt()}%", color = if (share.stance == ViewpointStance.UNKNOWN) Muted else swissStanceColor(share.stance),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            if (shares.isEmpty()) Text("暂无观点", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwissRepresentative(label: String, share: ViewpointShare?, accent: Color, repeated: Int, modifier: Modifier,
    onOpen: (ViewpointItem) -> Unit) {
    val item = share?.items?.firstOrNull()
    Surface(color = accent.copy(alpha = .045f), shape = DesignTokens.InnerShape, modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            if (item == null) Text("暂无其他观点", style = MaterialTheme.typography.bodySmall, color = Muted)
            else {
                Text(item.quote ?: "未识别", style = MaterialTheme.typography.bodySmall)
                Text(if (repeated > 0) "$repeated 篇论据重复" else "来源未核实", color = Muted, style = MaterialTheme.typography.labelSmall)
                PojianTextButton(onClick = { onOpen(item) }, contentPadding = PaddingValues(0.dp)) {
                    Text("${share.items.size} 篇", color = accent, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "查看原文", Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun SwissStanceTimeline(days: List<SwissStanceDay>) {
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val selectedIndex = days.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 } ?: days.lastIndex
    val values = days.map { it.value }
    fun select(index: Int) { days.getOrNull(index)?.let { selectedDate = it.date } }
    SwissCard(modifier = Modifier.testTag("swiss-stance-timeline")) {
        Text("7日观点倾向变化", style = MaterialTheme.typography.titleSmall)
        if (days.isEmpty()) {
            Text("加载中", color = Muted, style = MaterialTheme.typography.bodySmall)
            return@SwissCard
        }
        val selected = days[selectedIndex]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("质疑 −1", color = DesignTokens.Rose, style = MaterialTheme.typography.labelSmall)
            Text("支持 +1", color = DesignTokens.Blue, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(136.dp).testTag("swiss-stance-plot").pointerInput(days) {
            detectTapGestures { point ->
                val inset = 6.dp.toPx()
                swissChartSelection(point.x - inset, size.width - 2 * inset, days.size, edgePoints = true)?.let(::select)
            }
        }.semantics {
            contentDescription = "${selected.date} ${selected.value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "未识别"}；" +
                selected.counts.entries.joinToString("；") { (stance, count) -> "${stance.label} $count 篇" }
            customActions = buildList {
                if (selectedIndex > 0) add(CustomAccessibilityAction("前一天") { select(selectedIndex - 1); true })
                if (selectedIndex < days.lastIndex) add(CustomAccessibilityAction("后一天") { select(selectedIndex + 1); true })
            }
        }) {
            val center = size.height / 2
            val inset = 6.dp.toPx()
            val width = size.width - 2 * inset
            val amplitude = center - inset
            drawLine(DesignTokens.Border, Offset(0f, center), Offset(size.width, center),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            drawLine(DesignTokens.Border.copy(alpha = .5f), Offset(0f, inset), Offset(size.width, inset))
            drawLine(DesignTokens.Border.copy(alpha = .5f), Offset(0f, size.height - inset), Offset(size.width, size.height - inset))
            fun point(index: Int, value: Float) = Offset(inset + index * width / (values.size - 1).coerceAtLeast(1), center - value * amplitude)
            val selectedX = point(selectedIndex, 0f).x
            drawLine(DesignTokens.Ink.copy(alpha = .18f), Offset(selectedX, 0f), Offset(selectedX, size.height),
                1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            val areaBrush = Brush.verticalGradient(0f to DesignTokens.Blue.copy(alpha = .16f),
                .5f to DesignTokens.Rose.copy(alpha = .025f), 1f to DesignTokens.Rose.copy(alpha = .16f), endY = size.height)
            val lineBrush = Brush.verticalGradient(listOf(DesignTokens.Blue, DesignTokens.Rose), endY = size.height)
            values.forEachIndexed { index, value ->
                if (value == null) return@forEachIndexed
                val current = point(index, value)
                if (index > 0 && values[index - 1] != null) {
                    val previous = point(index - 1, values[index - 1]!!)
                    val middle = (previous.x + current.x) / 2
                    val line = Path().apply { moveTo(previous.x, previous.y); cubicTo(middle, previous.y, middle, current.y, current.x, current.y) }
                    val area = Path().apply { addPath(line); lineTo(current.x, center); lineTo(previous.x, center); close() }
                    drawPath(area, areaBrush)
                    drawPath(line, lineBrush, style = Stroke(2.dp.toPx()))
                }
                val color = if (value >= 0) DesignTokens.Blue else DesignTokens.Rose
                if (index == selectedIndex) {
                    drawCircle(color.copy(alpha = .15f), 8.dp.toPx(), current)
                    drawCircle(Color.White, 5.dp.toPx(), current)
                }
                drawCircle(color, if (index == selectedIndex) 4.dp.toPx() else 3.dp.toPx(), current)
            }
        }
        SwissChartAxis(days.map { day -> day.date.format(DateTimeFormatter.ofPattern("M/d")) to
            (day.value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—") }, selectedIndex)
        SwissChartSelectionHeader(selected.date.toString(), "前一天", "后一天", selectedIndex > 0, selectedIndex < days.lastIndex,
            { select(selectedIndex - 1) }, { select(selectedIndex + 1) }, "swiss-stance")
        FlowRow(Modifier.fillMaxWidth().testTag("swiss-stance-readout").semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ViewpointStance.entries.forEach { stance ->
                SwissLegend("${stance.label} ${selected.counts.getValue(stance)} 篇", swissStanceColor(stance))
            }
        }
    }
}
