@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.ui.graphics.Color
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun emotionDuration(millis: Long): String = when {
    millis == 0L -> "0秒"
    millis < 1_000 -> "不足1秒"
    millis < 60_000 -> "${millis / 1_000}秒"
    millis % 60_000 < 1_000 -> "${millis / 60_000}分钟"
    else -> "${millis / 60_000}分${millis % 60_000 / 1_000}秒"
}

internal fun emotionInterval(hour: EmotionHour, zone: ZoneId = ZoneId.systemDefault()): String {
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    return "${Instant.ofEpochMilli(hour.start).atZone(zone).format(format)} – " +
        Instant.ofEpochMilli(hour.end).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun toneColor(tone: EmotionTone): Color = when (tone) {
    EmotionTone.NEGATIVE -> Color(0xFFD65B65)
    EmotionTone.OBJECTIVE -> Color(0xFF33977D)
    EmotionTone.NEUTRAL -> Color(0xFF9B9EA6)
    EmotionTone.POSITIVE -> Color(0xFF6488BA)
    EmotionTone.UNKNOWN -> Color(0xFF6F737C)
}

@Composable
internal fun SwissEmotionTimeline(events: List<BrowseEvent>, semantics: List<EventSemantics>, now: Long,
    appNames: Map<String, String>) {
    var selectedTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val request = remember(events, semantics, now) { Any() }
    var failed by remember(request) { mutableStateOf(false) }
    val result by produceState<Pair<Any, List<EmotionHour>>?>(null, request) {
        try {
            value = request to withContext(Dispatchers.Default) { EmotionTimeline.analyze(events, semantics, now) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val hours = result?.takeIf { it.first === request }?.second
    if (hours == null) {
        WhiteCard {
            SwissOverviewSectionHeader(index = "02", title = "内容情绪")
            Text(if (failed) "分析暂不可用" else "加载中", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    } else SwissEmotionTimelineContent(hours, selectedTime, { selectedTime = it }, appNames) { detailId = it.id }
    detailId?.let { id -> events.firstOrNull { it.id == id }?.let { event ->
        SwissWorkDetailSheet(event, events, semantics, null, appNames,
            onDismiss = { detailId = null }, onRelated = { detailId = it.id })
    } }
}

@Composable
internal fun SwissEmotionTimelineContent(hours: List<EmotionHour>, selectedTime: Long?,
    onSelectedTime: (Long) -> Unit, appNames: Map<String, String> = emptyMap(),
    onPost: (BrowseEvent) -> Unit = {}) {
    if (hours.isEmpty()) return
    val selectedIndex = hours.indexOfFirst {
        selectedTime != null && selectedTime >= it.start && (selectedTime < it.end || it == hours.last() && selectedTime == it.end)
    }
    val selected = hours.getOrNull(selectedIndex)
    fun select(index: Int) { hours.getOrNull(index)?.let { onSelectedTime(it.start + (it.end - it.start) / 2) } }
    val activeIndex = selectedIndex.takeIf { it >= 0 } ?: hours.indexOfLast { it.workCount > 0 }.coerceAtLeast(0)
    val active = hours[activeIndex]
    val maximum = hours.maxOf { it.durationMillis }.coerceAtLeast(60_000L)
    val palette = EmotionTone.entries.associateWith(::toneColor)
    val border = DesignTokens.Border
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WhiteCard(Modifier.testTag("swiss-emotion-timeline")) {
            SwissOverviewSectionHeader(index = "02", title = "内容情绪", trailing = {
                Text("近24小时", color = Muted, style = MaterialTheme.typography.labelSmall)
            })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EmotionTone.entries.filter { it in listOf(EmotionTone.NEGATIVE, EmotionTone.OBJECTIVE, EmotionTone.NEUTRAL) ||
                    hours.any { hour -> hour.posts.any { post -> post.tone == it } } }.forEach { tone ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(8.dp)) {
                            if (tone == EmotionTone.UNKNOWN) drawCircle(palette.getValue(tone), style = Stroke(1.dp.toPx()))
                            else drawCircle(palette.getValue(tone))
                        }
                        Text(tone.label, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
            Text(emotionDuration(maximum), color = Muted, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace)
            Canvas(Modifier.fillMaxWidth().height(132.dp).testTag("swiss-emotion-plot")
                .pointerInput(hours) { detectTapGestures { point ->
                    swissChartSelection(point.x, size.width.toFloat(), hours.size)?.let(::select)
                } }
                .semantics {
                    contentDescription = "${emotionInterval(active)}，浏览${emotionDuration(active.durationMillis)}，${active.workCount}篇" +
                        active.durations.entries.joinToString("") { "，${it.key.label}${emotionDuration(it.value)}" } +
                        if (active.unknownDurationWorks > 0) "，${active.unknownDurationWorks}篇时长未知" else ""
                    onClick("查看时段内容") { select(activeIndex); true }
                    customActions = buildList {
                        if (activeIndex > 0) add(CustomAccessibilityAction("上一小时") { select(activeIndex - 1); true })
                        if (activeIndex < hours.lastIndex) add(CustomAccessibilityAction("下一小时") { select(activeIndex + 1); true })
                    }
                }) {
                val step = size.width / hours.size
                val width = (step * .64f).coerceAtLeast(1f)
                val height = size.height - 4.dp.toPx()
                (0..2).forEach { line ->
                    val y = height * line / 2
                    drawLine(border, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                hours.forEachIndexed { index, hour ->
                    val x = step * index + (step - width) / 2
                    if (index == selectedIndex) drawRoundRect(Color(0x126F737C), Offset(step * index, 0f),
                        Size(step, size.height), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    var bottom = height
                    EmotionTone.entries.forEach { tone ->
                        val barHeight = (hour.durations[tone] ?: 0L).toDouble().div(maximum).toFloat() * height
                        if (barHeight > 0f) {
                            val top = bottom - barHeight
                            if (tone == EmotionTone.UNKNOWN) {
                                drawRect(palette.getValue(tone).copy(alpha = .09f), Offset(x, top), Size(width, barHeight))
                                drawRect(palette.getValue(tone), Offset(x, top), Size(width, barHeight), style = Stroke(1.dp.toPx()))
                            } else drawRect(palette.getValue(tone), Offset(x, top), Size(width, barHeight))
                            bottom = top
                        }
                    }
                    if (hour.unknownDurationWorks > 0) drawCircle(palette.getValue(EmotionTone.UNKNOWN), 2.dp.toPx(),
                        Offset(step * (index + .5f), height), style = Stroke(1.dp.toPx()))
                }
            }
            SwissChartAxis(hours.map { hour ->
                Instant.ofEpochMilli(hour.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) to null
            }, selectedIndex)
        }
        if (selected != null) WhiteCard(Modifier.testTag("swiss-emotion-details")) {
            SwissChartSelectionHeader(emotionInterval(selected), "上一小时", "下一小时",
                selectedIndex > 0, selectedIndex < hours.lastIndex, { select(selectedIndex - 1) },
                { select(selectedIndex + 1) }, "swiss-emotion")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("浏览 ${emotionDuration(selected.durationMillis)}", style = MaterialTheme.typography.titleSmall)
                Text("${selected.workCount}篇", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (selected.unknownDurationWorks > 0) Text("${selected.unknownDurationWorks}篇时长未知",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (selected.posts.isEmpty()) Text("该时段暂无内容", color = Muted, style = MaterialTheme.typography.bodySmall)
            EmotionTone.entries.forEach { tone ->
                val matching = selected.posts.filter { it.tone == tone }
                if (matching.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = border)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tone.label, color = palette.getValue(tone), style = MaterialTheme.typography.titleSmall)
                        Text("${matching.map { it.workIdentity }.distinct().size}篇", color = Muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    matching.groupBy { it.topic }.forEach { (topic, posts) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(topic, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Text("${posts.map { it.workIdentity }.distinct().size}篇", color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        posts.forEach { post -> EmotionPostCard(post, appNames, onPost) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmotionPostCard(post: EmotionPost, appNames: Map<String, String>, onPost: (BrowseEvent) -> Unit) {
    Surface(onClick = { onPost(post.event) }, modifier = Modifier.fillMaxWidth(),
        color = DesignTokens.Surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, DesignTokens.Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(post.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(appNames[post.event.packageName] ?: platformName(post.event.packageName), color = Muted,
                style = MaterialTheme.typography.labelSmall)
            post.emotions.forEach { emotion ->
                Text("${emotion.category.label} · “${emotion.evidence}”", style = MaterialTheme.typography.bodySmall,
                    color = toneColor(post.tone))
                emotion.trigger?.takeIf { it != emotion.evidence }?.let {
                    Text("触发语句 · “$it”", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            if (post.tone == EmotionTone.OBJECTIVE) post.evidence.filter { evidence ->
                post.emotions.none { it.evidence == evidence }
            }.forEach { Text("依据 · “$it”", style = MaterialTheme.typography.bodySmall, color = Muted) }
            if (post.tone == EmotionTone.UNKNOWN) Text("情绪待分析", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
