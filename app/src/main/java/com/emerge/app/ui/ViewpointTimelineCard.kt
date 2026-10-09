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
    report: CognitiveReport?, appNames: Map<String, String>) {
    val zone = ZoneId.systemDefault()
    val buckets = remember(events, period, now, report) { ViewpointTimeline.build(events, period, now, zone, report) }
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
        SectionTitle("浏览作品数量", "${period.label} · 每柱一个时段")
        Text("${bucket.label} · ${bucket.items.size} 个作品", style = MaterialTheme.typography.titleLarge)
        MetadataText("柱高和颜色表示作品数量。点击柱子，下方展示这个时段的观点。小时图可左右滑动。")
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
        MetadataText("各时段分别对作品去重，同一作品跨时段会再次出现；柱子数量不能直接相加作为全天作品总数。")
    }
    val format = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zone)
    WhiteCard {
        Column(Modifier.testTag("viewpoint-details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("这个时段看了哪些观点", "${format.format(Instant.ofEpochMilli(bucket.start))} — ${format.format(Instant.ofEpochMilli(bucket.end))}")
            if (bucket.items.isEmpty()) MetadataText("这个时段没有保存的作品。")
            ViewpointTimeline.groups(bucket.items).forEachIndexed { i, share ->
                ViewpointGroup(share, bucket.start, bucket.end, appNames, "bucket-$index", i == 0 && share.items.size <= 3)
            }
        }
    }
    val date = if (period == StatisticsPeriod.WEEK) Instant.ofEpochMilli(bucket.start).atZone(zone).toLocalDate()
        else Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayEnd = if (date == today) now else date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val dayGroups = remember(events, date, dayEnd, report) {
        ViewpointTimeline.groups(ViewpointTimeline.build(events, StatisticsPeriod.DAY, dayEnd, zone,
            report?.takeIf { it.period == StatisticsPeriod.DAY.label }).flatMap { it.items })
    }
    DailyViewpointPie(dayGroups, "$date", appNames, date.atStartOfDay(zone).toInstant().toEpochMilli(), dayEnd)
}

@Composable
private fun ViewpointGroup(share: ViewpointShare, start: Long, end: Long, names: Map<String, String>, key: String,
    initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(key, share.label) { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(share.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("${share.items.size} 个作品 ${if (expanded) "−" else "+"}", color = Accent, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) share.items.forEach { item ->
            var raw by rememberSaveable(key, item.record.id) { mutableStateOf(false) }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MetadataText(names[item.record.packageName] ?: item.record.packageName)
                val duration = ObservationWindow(start, end, ZoneId.systemDefault()).durationMillis(item.record)
                MetadataText(duration?.let { "本时段预计停留：${formatDuration(it)}" } ?: "未统计时长")
                item.quote?.takeIf { it != share.label }?.let { QuoteBlock(it) }
                TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起原文" else "查看作品原文 ›") }
                if (raw) QuoteBlock(item.record.visibleText)
            }
        }
    }
}

@Composable
private fun DailyViewpointPie(groups: List<ViewpointShare>, date: String, names: Map<String, String>, start: Long, end: Long) {
    val total = groups.firstOrNull()?.total ?: 0
    val colors = listOf(Color(0xFF3298F4), Color(0xFF42C587), Color(0xFFFFAA45), Color(0xFFB678DC), Color(0xFFEF729D), Color(0xFF35B9C9))
    WhiteCard {
        SectionTitle("一天接触的观点占比", "$date · $total 个作品（估计）")
        MetadataText("相同观点的作品归为一组，百分比按作品数计算；接触不代表认同。未识别的作品也计入分母。")
        Canvas(Modifier.fillMaxWidth().height(220.dp).testTag("daily-viewpoint-pie").semantics {
            contentDescription = groups.joinToString("；") { "${it.label}，${it.items.size}个作品，${String.format(Locale.CHINA, "%.1f", it.percent)}%" }
                .ifBlank { "暂无作品" }
        }) {
            val diameter = minOf(size.width, size.height) * .9f
            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            if (total == 0) drawCircle(Color.LightGray, diameter / 2)
            var angle = -90f
            groups.forEachIndexed { i, group ->
                val sweep = group.items.size * 360f / total
                drawArc(if (group.label == "暂未识别观点") Color.LightGray else colors[i % colors.size],
                    angle, sweep, true, origin, Size(diameter, diameter))
                angle += sweep
            }
        }
        groups.forEachIndexed { i, group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("● ${group.label}", Modifier.weight(1f), color = if (group.label == "暂未识别观点") Muted else colors[i % colors.size],
                    style = MaterialTheme.typography.bodyMedium)
                MetadataText("${group.items.size} 个 · ${String.format(Locale.CHINA, "%.1f", group.percent)}%")
            }
            ViewpointGroup(group, start, end, names, "pie-$date")
        }
        if (total == 0) MetadataText("当天没有保存的浏览作品。")
        ExpandableNote("观点归组说明", "当前使用离线规则和已生成报告中的观点候选。同义表达仅在有明确规则时归为一组，否定观点保留区别，不会为每个作品强行编造观点。其余内容标记为暂未识别。每个作品当前取一个代表性观点候选；完整语义归组需要后续模型。")
    }
}
