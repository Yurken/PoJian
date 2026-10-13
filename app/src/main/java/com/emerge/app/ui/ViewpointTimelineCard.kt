package com.emerge.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

internal data class ViewpointTimelineUiState(val buckets: List<ViewpointBucket>, val index: Int,
    val hasSelection: Boolean, val select: (Int) -> Unit, val window: ObservationWindow,
    val groups: List<ViewpointShare>, val period: StatisticsPeriod, val zone: ZoneId, val clearSelection: () -> Unit)

@Composable
internal fun rememberViewpointTimelineState(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    report: CognitiveReport?, semantics: List<EventSemantics> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault()): ViewpointTimelineUiState {
    val buckets = remember(events, period, now, report, semantics, zone) { ViewpointTimeline.build(events, period, now, zone, report, semantics) }
    var selected by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    val latest = buckets.indexOfLast { it.items.isNotEmpty() }.coerceAtLeast(0)
    val index = (selected ?: latest).coerceIn(0, buckets.lastIndex)
    val window = remember(period, now, zone) { ObservationWindow.forPeriod(period, now, zone) }
    val groups = remember(buckets) { ViewpointTimeline.groups(buckets.flatMap { it.items }) }
    return ViewpointTimelineUiState(buckets, index, selected != null, { selected = it }, window, groups, period, zone, { selected = null })
}

/** The chart is a separate sticky item. A zero-height end marker releases it after the viewpoint section. */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.viewpointTimeline(state: ViewpointTimelineUiState, appNames: Map<String, String>,
    plotHeight: Dp = 156.dp, onWorkScrolling: (Boolean) -> Unit = {}) {
    stickyHeader(key = "viewpoint-timeline") {
        Surface(color = PageBackground, modifier = Modifier.testTag("viewpoint-chart-pinned")) {
            ViewpointTimelineChart(state, plotHeight)
        }
    }
    item(key = "viewpoint-content") { ViewpointTimelineContent(state, appNames, onWorkScrolling) }
    stickyHeader(key = "viewpoint-timeline-end") { Spacer(Modifier.height(0.dp)) }
}

@Composable
private fun ViewpointTimelineChart(state: ViewpointTimelineUiState, plotHeight: Dp) {
    val buckets = state.buckets
    val bucket = buckets[state.index]
    val maximum = buckets.maxOfOrNull { it.items.size }?.coerceAtLeast(1) ?: 1
    val scroll = rememberScrollState()
    val step = with(LocalDensity.current) { 38.dp.roundToPx() }
    LaunchedEffect(state.period, scroll.maxValue) {
        if (!state.hasSelection && buckets.size > 7) scroll.scrollTo((state.index * step - step * 3).coerceIn(0, scroll.maxValue))
    }
    val total = state.groups.firstOrNull()?.total ?: 0
    val works = state.groups.flatMap { it.items }.distinctBy { it.workIdentity }
    WhiteCard {
        SectionTitle("浏览趋势", help = analysisSourceLabel("各时段独立去重，同一作品可能跨时段出现，数量不能直接相加。\n\n${bucket.items.count { it.analysisSource == "structured_model" }}/${bucket.items.size} 个作品有云端采样结果，其余使用本地识别。采样仅覆盖部分内容，可查看原文核对。\n\n观点分布统计范围为当前范围 $total 个去重作品，含未识别部分；一部作品可含多个观点，各组比例之和可能超过 100%。${works.count { it.analysisSource == "structured_model" }}/$total 个作品有有效云端采样结果，其余使用本地识别。云端分析按主题、对象、维度、条件和立场归组；信息不明确时标为待确认，未提取观点时标为未识别。采样不代表整部作品已分析，音频和视觉文字不单独增加作品数或停留时长。"))
        Text("${bucket.label} · ${bucket.items.size} 个作品", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().height(plotHeight + 40.dp)) {
            Column(Modifier.width(32.dp).height(plotHeight), verticalArrangement = Arrangement.SpaceBetween) {
                MetadataText("$maximum"); MetadataText("${maximum / 2}"); MetadataText("0")
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val width = if (buckets.size <= 7) maxWidth / buckets.size else 38.dp
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.Bottom) {
                    buckets.forEachIndexed { i, b ->
                        Column(Modifier.width(width).clickable(role = Role.Button) { state.select(i) }.testTag("viewpoint-bar-$i")
                            .semantics { contentDescription = "${b.label}，${b.items.size}个作品，点击查看主题观点"; selected = i == state.index },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.fillMaxWidth().height(plotHeight).padding(horizontal = 6.dp)) {
                                listOf(0f, .5f, 1f).forEach { fraction ->
                                    drawLine(Color(0xFFE6E8EC), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                                }
                                val fraction = b.items.size.toFloat() / maximum
                                val height = if (b.items.isEmpty()) 3.dp.toPx() else size.height * fraction
                                // Solid colors follow relative bar height, not an inferred risk score.
                                val color = when {
                                    b.items.isEmpty() -> Color(0xFFD7DEDA)
                                    fraction <= 1f / 3f -> Color(0xFF75AE91)
                                    fraction <= 2f / 3f -> Color(0xFFD2B967)
                                    else -> Color(0xFFCE817B)
                                }
                                drawRoundRect(color,
                                    topLeft = Offset(0f, size.height - height), size = Size(size.width, height), cornerRadius = CornerRadius(4.dp.toPx()))
                            }
                            Text(b.label, Modifier.padding(top = 8.dp), color = if (i == state.index) Ink else Muted,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewpointTimelineContent(state: ViewpointTimelineUiState, appNames: Map<String, String>, onWorkScrolling: (Boolean) -> Unit) {
    val bucket = state.buckets[state.index]
    val format = remember(state.zone) { DateTimeFormatter.ofPattern("M/d HH:mm").withZone(state.zone) }
    WhiteCard {
        if (state.hasSelection) Column(Modifier.testTag("viewpoint-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MetadataText("${format.format(Instant.ofEpochMilli(bucket.start))} — ${format.format(Instant.ofEpochMilli(bucket.end))}")
                PojianTextButton(onClick = state.clearSelection) { Text("全部观点") }
            }
            if (bucket.items.isEmpty()) MetadataText("该时段暂无作品。")
            key(state.period, bucket.start) {
                BrowseWorkCards(bucket.items, bucket.start, bucket.end, appNames, state.zone, onScrolling = onWorkScrolling)
            }
        } else DailyViewpointDistribution(state.groups,
            "${format.format(Instant.ofEpochMilli(state.window.start))} — ${format.format(Instant.ofEpochMilli(state.window.end))}",
            appNames, state.window.start, state.window.end,
            stateKey = "${state.period}:${Instant.ofEpochMilli(state.window.end).atZone(state.zone).toLocalDate()}")
    }
}

@Composable
internal fun ViewpointSources(share: ViewpointShare, start: Long, end: Long, names: Map<String, String>, key: String,
    initiallyShowRaw: Boolean = false) {
    ViewpointTimeline.details(share.items).forEach { item ->
        key(item.workIdentity, item.quote) {
            var raw by rememberSaveable(key, share.key, item.workIdentity, item.quote) { mutableStateOf(initiallyShowRaw) }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceAppText(item.record.packageName.takeUnless { item.record.sourceType == "audio_import" },
                    names[item.record.packageName] ?: item.record.packageName)
                MetadataText(analysisSourceLabel(if (item.analysisSource == "structured_model") "云端分析" else "本地识别"))
                val duration = item.durationMillis(ObservationWindow(start, end, ZoneId.systemDefault()))
                MetadataText(duration?.let { "本时段停留时长 ${formatDuration(it)}" } ?: "时长未统计")
                item.quote?.takeIf { it != share.label }?.let { QuoteBlock(it) }
                item.claims.map { it.evidence }.filter { it.isNotBlank() && it != item.quote }
                    .distinct().forEach { QuoteBlock(it) }
                if (initiallyShowRaw) WorkAnalysisContent(item, ObservationWindow(start, end, ZoneId.systemDefault()))
                PojianTextButton(onClick = { raw = !raw }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (raw) "收起原文" else "查看原文 ›")
                }
                if (raw) item.records.map { rememberOriginalText(it) }.filter(String::isNotBlank)
                    .distinctBy(BrowseWorkKey::normalize).forEach { QuoteBlock(it) }
            }
        }
    }
}
