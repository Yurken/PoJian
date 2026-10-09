package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewpointTimelineCard(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    report: CognitiveReport?, appNames: Map<String, String>) {
    val buckets = remember(events, period, now, report) {
        ViewpointTimeline.build(events, period, now, ZoneId.systemDefault(), report)
    }
    var selected by rememberSaveable(period) { mutableStateOf<Int?>(null) }
    val scores = buckets.mapNotNull { it.score }
    WhiteCard {
        SectionTitle("我接触的观点", "${period.label} · 认知影响风险（估计）")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (scores.isEmpty()) "等待更多可分析作品" else "最高 ${scores.max()} / 100",
                style = MaterialTheme.typography.titleLarge)
            MetadataText("绿 → 黄 → 红")
        }
        MetadataText("点击柱子，查看这个时段的观点与原文。灰色表示样本不足。")
        Row(Modifier.fillMaxWidth().height(230.dp)) {
            Column(Modifier.width(28.dp).height(190.dp), verticalArrangement = Arrangement.SpaceBetween) {
                MetadataText("100"); MetadataText("50"); MetadataText("0")
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val width = if (buckets.size <= 7) maxWidth / buckets.size.coerceAtLeast(1) else 38.dp
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Bottom) {
                    buckets.forEachIndexed { index, bucket ->
                        Column(Modifier.width(width).clickable { selected = index }
                            .testTag("viewpoint-bar-$index").semantics {
                                contentDescription = "${bucket.label}，${bucket.items.size}个作品，" +
                                    (bucket.score?.let { "风险估计${it}分" } ?: "样本不足") + "，点击查看观点"
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 6.dp)) {
                                listOf(0f, .5f, 1f).forEach { fraction ->
                                    drawLine(Color(0xFFE6E8EC), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                                }
                                val height = if (bucket.score == null) 8.dp.toPx() else
                                    (size.height * bucket.score / 100f).coerceAtLeast(4.dp.toPx())
                                val brush = if (bucket.score == null) Brush.verticalGradient(listOf(Color.LightGray, Color.LightGray))
                                    else Brush.verticalGradient(listOf(Color(0xFFE85650), Color(0xFFFFCC45), Color(0xFF35BE78)),
                                        startY = 0f, endY = size.height)
                                drawRoundRect(brush, topLeft = Offset(0f, size.height - height), size = Size(size.width, height),
                                    cornerRadius = CornerRadius(4.dp.toPx()))
                            }
                            MetadataText(bucket.label, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        ExpandableNote("颜色与分数说明", "这是本地规则对已采集内容的估计，不是对你认知状态的测量。至少3个作品识别到观点候选后才计算：重复观点占比×60＋情绪表达占比×40。作品数量和停留时间不直接提高分数；低分不证明没有风险。未识别作品不计入评分，评论可能混入可见文字，结论需结合原文核实。")
    }
    selected?.let { index ->
        val bucket = buckets.getOrNull(index) ?: return@let
        val format = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.8f).testTag("viewpoint-details"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    SectionTitle("${format.format(Instant.ofEpochMilli(bucket.start))} — ${format.format(Instant.ofEpochMilli(bucket.end))}",
                        "${bucket.items.size} 个作品（估计） · ${bucket.score?.let { "风险估计 $it/100" } ?: "样本不足，暂不评分"}")
                    MetadataText("重复观点 ${bucket.repeated} 个 · 情绪表达 ${bucket.emotional} 个；以下均为观点候选，不代表事实或你的立场。")
                }
                if (bucket.items.isEmpty()) item { MetadataText("这个时段没有保存的浏览作品。") }
                items(bucket.items) { item ->
                    var expanded by remember(item.record.id) { mutableStateOf(false) }
                    WhiteCard {
                        Text(item.label ?: "暂未识别观点", style = MaterialTheme.typography.titleMedium)
                        MetadataText(appNames[item.record.packageName] ?: item.record.packageName)
                        val duration = ObservationWindow(bucket.start, bucket.end, ZoneId.systemDefault()).durationMillis(item.record)
                        MetadataText(duration?.let { "本时段预计停留：${formatDuration(it)}" } ?: "未统计时长")
                        item.quote?.takeIf { it != item.label }?.let { QuoteBlock(it) }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "查看作品原文 ›") }
                        if (expanded) QuoteBlock(item.record.visibleText)
                    }
                }
            }
        }
    }
}
