package com.emerge.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
    val date = if (period == StatisticsPeriod.WEEK) Instant.ofEpochMilli(bucket.start).atZone(zone).toLocalDate()
        else Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dayEnd = if (date == today) now else date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val dayGroups = remember(events, date, dayEnd, report, semantics) {
        ViewpointTimeline.groups(ViewpointTimeline.build(events, StatisticsPeriod.DAY, dayEnd, zone,
            report?.takeIf { it.period == StatisticsPeriod.DAY.label }, semantics).flatMap { it.items })
    }
    val total = dayGroups.firstOrNull()?.total ?: 0
    val works = dayGroups.flatMap { it.items }.distinctBy { it.workIdentity }
    val format = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(zone)
    WhiteCard {
        SectionTitle("浏览趋势", help = "各时段独立去重，同一作品可能跨时段出现，数量不能直接相加。\n\n${bucket.items.count { it.analysisSource == "structured_model" }}/${bucket.items.size} 个作品有云端采样结果，其余使用本地识别。采样仅覆盖部分内容，可查看原文核对。\n\n观点分布统计范围为当天 $total 个去重作品，含未识别部分；一部作品可含多个观点，各组比例之和可能超过 100%。${works.count { it.analysisSource == "structured_model" }}/$total 个作品有有效云端采样结果，其余使用本地识别。云端分析按主题、对象、维度、条件和立场归组；信息不明确时标为待确认，未提取观点时标为未识别。采样不代表整部作品已分析，音频和视觉文字不单独增加作品数或停留时长。")
        Text("${bucket.label} · ${bucket.items.size} 个作品", style = MaterialTheme.typography.titleSmall)
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
        if (selected != null) Column(Modifier.testTag("viewpoint-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MetadataText("${format.format(Instant.ofEpochMilli(bucket.start))} — ${format.format(Instant.ofEpochMilli(bucket.end))}")
            if (bucket.items.isEmpty()) MetadataText("该时段暂无作品。")
            ViewpointTimeline.groups(bucket.items).forEachIndexed { i, share ->
                ViewpointGroup(share, bucket.start, bucket.end, appNames, "bucket-$index", i == 0 && share.items.size <= 3)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DailyViewpointDistribution(dayGroups, "$date", appNames, date.atStartOfDay(zone).toInstant().toEpochMilli(), dayEnd)
    }
}

@Composable
private fun ViewpointGroup(share: ViewpointShare, start: Long, end: Long, names: Map<String, String>, key: String,
    initiallyExpanded: Boolean = false, headerColor: Color? = null, showPercent: Boolean = false) {
    var expanded by rememberSaveable(key, share.key) { mutableStateOf(initiallyExpanded) }
    val scheme = MaterialTheme.colorScheme
    val amount = if (showPercent) "${share.items.size} 个 · ${String.format(Locale.CHINA, "%.1f", share.percent)}%" else "${share.items.size} 个作品"
    val header = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button) { expanded = !expanded }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showPercent) {
            Column(header.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(share.label, color = Ink, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val fraction = (share.percent / 100.0).toFloat().coerceIn(0f, 1f)
                    val barColor = headerColor ?: scheme.primary
                    Canvas(Modifier.weight(1f).height(4.dp).progressSemantics(fraction)) {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(scheme.surfaceVariant, cornerRadius = radius)
                        if (fraction > 0f) drawRoundRect(barColor,
                            size = Size(size.width * fraction, size.height), cornerRadius = radius)
                    }
                    Text("$amount ${if (expanded) "−" else "+"}", color = Muted,
                        modifier = Modifier.width(112.dp * LocalDensity.current.fontScale),
                        textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Row(header,
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(share.label, Modifier.weight(1f), color = Ink,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("$amount ${if (expanded) "−" else "+"}", modifier = Modifier.widthIn(max = 120.dp),
                    color = Accent, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded) ViewpointTimeline.details(share.items).forEach { item ->
            var raw by rememberSaveable(key, item.record.id) { mutableStateOf(false) }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceAppText(item.record.packageName.takeUnless { item.record.sourceType == "audio_import" },
                    names[item.record.packageName] ?: item.record.packageName)
                MetadataText(if (item.analysisSource == "structured_model") "云端分析" else "本地识别")
                val duration = item.durationMillis(ObservationWindow(start, end, ZoneId.systemDefault()))
                MetadataText(duration?.let { "本时段停留时长 ${formatDuration(it)}" } ?: "时长未统计")
                item.quote?.takeIf { it != share.label }?.let { QuoteBlock(it) }
                PojianTextButton(onClick = { raw = !raw }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (raw) "收起原文" else "查看原文 ›")
                }
                if (raw) item.records.map { it.visibleText }.distinctBy(BrowseWorkKey::normalize).forEach { QuoteBlock(it) }
            }
        }
    }
}

@Composable
private fun DailyViewpointDistribution(groups: List<ViewpointShare>, date: String, names: Map<String, String>, start: Long, end: Long) {
    val total = groups.firstOrNull()?.total ?: 0
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.testTag("daily-viewpoint-card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("观点分布", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                MetadataText("$date · $total 个作品")
            }
        }
        val chartSemantics = Modifier.fillMaxWidth().testTag("daily-viewpoint-pie").semantics {
            contentDescription = groups.joinToString("；") { "${it.label}，${it.items.size}个作品，${String.format(Locale.CHINA, "%.1f", it.percent)}%" }
                .ifBlank { "暂无作品" }
        }
        Column(chartSemantics) {
            groups.forEachIndexed { i, group ->
                if (i > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .6f))
                ViewpointGroup(group, start, end, names, "pie-$date", headerColor =
                    if (group.label == "未识别") scheme.outline else scheme.primary, showPercent = true)
            }
            if (total == 0) MetadataText("当天没有保存的浏览作品。")
        }
    }
}
