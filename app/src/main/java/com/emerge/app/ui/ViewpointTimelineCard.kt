package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ViewpointTimelineCard(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    report: CognitiveReport?, appNames: Map<String, String>, semantics: List<EventSemantics> = emptyList()) {
    val zone = ZoneId.systemDefault()
    val buckets = remember(events, period, now, report, semantics) { ViewpointTimeline.build(events, period, now, zone, report, semantics) }
    var selected by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    val latest = buckets.indexOfLast { it.items.isNotEmpty() }.coerceAtLeast(0)
    val index = (selected ?: latest).coerceIn(0, buckets.lastIndex)
    val bucket = buckets[index]
    val maximum = buckets.maxOfOrNull { it.items.size }?.coerceAtLeast(1) ?: 1
    val scroll = rememberScrollState()
    val step = with(LocalDensity.current) { 38.dp.roundToPx() }
    LaunchedEffect(period, scroll.maxValue) {
        if (selected == null && buckets.size > 7) scroll.scrollTo((latest * step - step * 3).coerceIn(0, scroll.maxValue))
    }
    WhiteCard {
        SectionTitle("浏览趋势", help = "点击柱子查看时段观点，小时图可左右滑动。各时段独立去重，同一作品可能跨时段出现，数量不能直接相加。")
        Text("${bucket.label} · ${bucket.items.size} 个作品", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().height(230.dp)) {
            Column(Modifier.width(32.dp).height(190.dp), verticalArrangement = Arrangement.SpaceBetween) {
                MetadataText("$maximum"); MetadataText("${maximum / 2}"); MetadataText("0")
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val width = if (buckets.size <= 7) maxWidth / buckets.size else 38.dp
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.Bottom) {
                    buckets.forEachIndexed { i, b ->
                        Column(Modifier.width(width).clickable { selected = i }.testTag("viewpoint-bar-$i")
                            .semantics { contentDescription = "${b.label}，${b.items.size}个作品，点击查看观点" },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 6.dp)) {
                                listOf(0f, .5f, 1f).forEach { fraction ->
                                    drawLine(Color(0xFFE6E8EC), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                                }
                                val height = if (b.items.isEmpty()) 3.dp.toPx() else size.height * b.items.size / maximum
                                val colors = if (b.items.isEmpty()) listOf(Color.LightGray, Color.LightGray) else
                                    listOf(Color(0xFFE85650), Color(0xFFFFCC45), Color(0xFF35BE78))
                                drawRoundRect(Brush.verticalGradient(colors, startY = 0f, endY = size.height),
                                    topLeft = Offset(0f, size.height - height), size = Size(size.width, height), cornerRadius = CornerRadius(4.dp.toPx()))
                            }
                            Text(b.label, Modifier.padding(top = 8.dp), color = if (i == index) Accent else Muted,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    val format = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zone)
    WhiteCard {
        Column(Modifier.testTag("viewpoint-details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("时段观点", "${format.format(Instant.ofEpochMilli(bucket.start))} — ${format.format(Instant.ofEpochMilli(bucket.end))}",
                help = "${bucket.items.count { it.analysisSource == "structured_model" }}/${bucket.items.size} 个作品有云端采样结果，其余使用本地识别。采样仅覆盖部分内容，可查看原文核对。")
            if (bucket.items.isEmpty()) MetadataText("该时段暂无作品。")
            ViewpointTimeline.groups(bucket.items).forEachIndexed { i, share ->
                ViewpointGroup(share, bucket.start, bucket.end, appNames, "bucket-$index", i == 0 && share.items.size <= 3)
            }
        }
    }
    val date = if (period == StatisticsPeriod.WEEK) Instant.ofEpochMilli(bucket.start).atZone(zone).toLocalDate()
        else Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayEnd = if (date == today) now else date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val dayGroups = remember(events, date, dayEnd, report, semantics) {
        ViewpointTimeline.groups(ViewpointTimeline.build(events, StatisticsPeriod.DAY, dayEnd, zone,
            report?.takeIf { it.period == StatisticsPeriod.DAY.label }, semantics).flatMap { it.items })
    }
    DailyViewpointPie(dayGroups, "$date", appNames, date.atStartOfDay(zone).toInstant().toEpochMilli(), dayEnd)
}

@Composable
private fun ViewpointGroup(share: ViewpointShare, start: Long, end: Long, names: Map<String, String>, key: String,
    initiallyExpanded: Boolean = false, headerColor: Color? = null, showPercent: Boolean = false) {
    var expanded by rememberSaveable(key, share.key) { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((if (showPercent) "● " else "") + share.label, Modifier.weight(1f), color = headerColor ?: MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            val amount = if (showPercent) "${share.items.size} 个 · ${String.format(Locale.CHINA, "%.1f", share.percent)}%" else "${share.items.size} 个作品"
            Text("$amount ${if (expanded) "−" else "+"}", color = Accent, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) share.items.forEach { item ->
            var raw by rememberSaveable(key, item.record.id) { mutableStateOf(false) }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MetadataText(names[item.record.packageName] ?: item.record.packageName)
                MetadataText(if (item.analysisSource == "structured_model") "云端分析" else "本地识别")
                val duration = ObservationWindow(start, end, ZoneId.systemDefault()).durationMillis(item.record)
                MetadataText(duration?.let { "本时段预计停留 ${formatDuration(it)}" } ?: "时长未统计")
                item.quote?.takeIf { it != share.label }?.let { QuoteBlock(it) }
                TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起原文" else "查看原文 ›") }
                if (raw) QuoteBlock(item.record.visibleText)
            }
        }
    }
}

@Composable
private fun DailyViewpointPie(groups: List<ViewpointShare>, date: String, names: Map<String, String>, start: Long, end: Long) {
    val total = groups.firstOrNull()?.total ?: 0
    val overlaps = groups.sumOf { it.items.size } > total
    val works = groups.flatMap { it.items }.distinctBy { it.record.packageName + "|" +
        (it.record.workKey ?: "record:${it.record.id}:${it.record.timestamp}") }
    val colors = listOf(Color(0xFF3298F4), Color(0xFF42C587), Color(0xFFFFAA45), Color(0xFFB678DC), Color(0xFFEF729D), Color(0xFF35B9C9))
    WhiteCard {
        SectionTitle("观点分布", "$date · $total 个作品（估算）",
            help = "统计范围为当天 $total 个去重作品，含未识别部分；一部作品可含多个观点，各组比例之和可能超过 100%。${works.count { it.analysisSource == "structured_model" }}/$total 个作品有有效云端采样结果，其余使用本地识别。云端分析按主题、对象、维度、条件和立场归组；信息不明确时标为待确认，未提取观点时标为未识别。多观点用比例条表示。采样不代表整部作品已分析，音频和视觉文字不单独增加作品数或停留时长。")
        val chartSemantics = Modifier.fillMaxWidth().testTag("daily-viewpoint-pie").semantics {
            contentDescription = groups.joinToString("；") { "${it.label}，${it.items.size}个作品，${String.format(Locale.CHINA, "%.1f", it.percent)}%" }
                .ifBlank { "暂无作品" }
        }
        if (overlaps) Column(chartSemantics, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEachIndexed { i, group ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.label, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (group.percent / 100.0).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (group.label == "未识别") Muted else colors[i % colors.size],
                        trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        } else Canvas(chartSemantics.height(220.dp)) {
            val diameter = minOf(size.width, size.height) * .9f
            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            if (total == 0) drawCircle(Color.LightGray, diameter / 2)
            var angle = -90f
            groups.forEachIndexed { i, group ->
                val sweep = group.items.size * 360f / total
                drawArc(if (group.label == "未识别") Color.LightGray else colors[i % colors.size],
                    angle, sweep, true, origin, Size(diameter, diameter))
                angle += sweep
            }
        }
        groups.forEachIndexed { i, group ->
            ViewpointGroup(group, start, end, names, "pie-$date", headerColor =
                if (group.label == "未识别") Muted else colors[i % colors.size], showPercent = true)
        }
        if (total == 0) MetadataText("当天没有保存的浏览作品。")
    }
}
