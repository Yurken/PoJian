package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun SwissOverviewSectionHeader(
    index: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
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
                    text = index,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
        }
        if (trailing != null) {
            trailing()
        } else if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwissEvidenceTrend(
    events: List<BrowseEvent>,
    semantics: List<EventSemantics>,
    now: Long
) {
    var selectedTime by remember { mutableStateOf<Long?>(null) }
    val request = Triple(events, semantics, now)
    var error by remember(request) { mutableStateOf(false) }
    val result by produceState<Pair<Triple<List<BrowseEvent>, List<EventSemantics>, Long>, List<SwissEvidenceBucket>>?>(null, request) {
        try {
            value = withContext(Dispatchers.Default) {
                val task = currentCoroutineContext()
                val report = CognitiveAnalyzer().analyze(
                    events = events,
                    period = StatisticsPeriod.HOURS24,
                    now = now,
                    semantics = semantics,
                    checkCancellation = { task.ensureActive() }
                )
                request to swissEvidenceTrend(report.claims, now)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    val buckets = result?.takeIf { it.first == request }?.second.orEmpty()
    WhiteCard(Modifier.testTag("swiss-evidence-trend")) {
        SwissOverviewSectionHeader(
            index = "02",
            title = "24小时浏览新鲜度",
            subtitle = "FRESHNESS 24H"
        )
        if (error) {
            Text("分析暂不可用", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else if (result?.first != request) {
            Text("加载中", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            SwissEvidenceChart(buckets, selectedTime) { selectedTime = it }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwissEvidenceChart(
    buckets: List<SwissEvidenceBucket>,
    selectedTime: Long?,
    onSelectedTime: (Long) -> Unit
) {
    if (buckets.isEmpty()) return
    val selectedIndex = buckets.indexOfFirst { bucket ->
        selectedTime?.let {
            it >= bucket.start && (it < bucket.end || bucket == buckets.last() && it == bucket.end)
        } == true
    }.takeIf { it >= 0 } ?: buckets.lastIndex
    val selected = buckets[selectedIndex]
    fun select(index: Int) {
        buckets.getOrNull(index)?.let { onSelectedTime(it.start + (it.end - it.start) / 2) }
    }
    val interval = swissEvidenceInterval(selected)
    val maximum = buckets.maxOf { it.total }.coerceAtLeast(1)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            .testTag("swiss-evidence-plot")
            .pointerInput(buckets) {
                detectTapGestures { point ->
                    swissChartSelection(point.x, size.width.toFloat(), buckets.size)?.let(::select)
                }
            }
            .semantics {
                contentDescription = "$interval 新增依据 ${selected.fresh} 重复依据 ${selected.repeated} 待判断 ${selected.unknown}"
                customActions = buildList {
                    if (selectedIndex > 0) add(CustomAccessibilityAction("上一小时") { select(selectedIndex - 1); true })
                    if (selectedIndex < buckets.lastIndex) add(CustomAccessibilityAction("下一小时") { select(selectedIndex + 1); true })
                }
            }
    ) {
        val step = size.width / buckets.size
        drawRect(DesignTokens.Ink.copy(alpha = .055f), Offset(selectedIndex * step, 0f), Size(step, size.height))
        drawLine(DesignTokens.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        buckets.forEachIndexed { index, bucket ->
            var bottom = size.height
            listOf(
                bucket.fresh to DesignTokens.Ink,
                bucket.repeated to DesignTokens.Rose,
                bucket.unknown to Color(0xFFD4D4D8)
            ).forEach { (count, color) ->
                val height = count.toFloat() / maximum * (size.height - 4.dp.toPx())
                if (height > 0) {
                    drawRect(
                        color = color,
                        topLeft = Offset(index * step + step * .17f, bottom - height),
                        size = Size(step * .66f, height)
                    )
                }
                bottom -= height
            }
        }
        drawLine(
            color = DesignTokens.Ink,
            start = Offset(selectedIndex * step + step * .08f, size.height),
            end = Offset(selectedIndex * step + step * .92f, size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
    SwissChartAxis(buckets.filterIndexed { index, _ -> index % 6 == 0 }.map { it.label to null })
    SwissChartSelectionHeader(
        label = interval,
        previousLabel = "上一小时",
        nextLabel = "下一小时",
        previousEnabled = selectedIndex > 0,
        nextEnabled = selectedIndex < buckets.lastIndex,
        onPrevious = { select(selectedIndex - 1) },
        onNext = { select(selectedIndex + 1) },
        tag = "swiss-evidence"
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("swiss-evidence-readout")
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SwissLegend("新增依据 ${selected.fresh}", DesignTokens.Ink)
        SwissLegend("重复依据 ${selected.repeated}", DesignTokens.Rose)
        SwissLegend("待判断 ${selected.unknown}", Color(0xFFD4D4D8))
    }
}

@Composable
internal fun SwissLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwissAppDuration(stats: BrowseStatistics, names: Map<String, String>) {
    val apps = stats.apps.filter { it.durationMillis > 0 }.sortedByDescending { it.durationMillis }
    val total = apps.sumOf { it.durationMillis }
    val colors = listOf(DesignTokens.Ink, DesignTokens.Rose, DesignTokens.Blue, DesignTokens.Amber)
    WhiteCard {
        SwissOverviewSectionHeader(
            index = "03",
            title = "应用时长分布",
            subtitle = "DURATION SHARE"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(DesignTokens.PillShape)
                .background(DesignTokens.Border),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp)
        ) {
            apps.forEachIndexed { index, app ->
                Box(
                    modifier = Modifier
                        .weight(app.durationMillis.toFloat())
                        .fillMaxHeight()
                        .background(colors[index % colors.size])
                )
            }
        }
        if (total == 0L) {
            Text("时长未统计", style = MaterialTheme.typography.bodySmall, color = Muted)
        } else {
            BoxWithConstraints {
                val columns = if (maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.2f) 4 else 2
                val cellWidth = (maxWidth - 8.dp * (columns - 1)) / columns
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    apps.forEachIndexed { index, app ->
                        Surface(
                            shape = DesignTokens.InnerShape,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
                            modifier = Modifier.width(cellWidth)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SwissLegend(
                                    label = names[app.packageName] ?: platformName(app.packageName),
                                    color = colors[index % colors.size]
                                )
                                Text(
                                    text = percentText(app.durationMillis.toFloat() / total),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink
                                )
                                Text(
                                    text = formatDuration(app.durationMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Muted
                                )
                            }
                        }
                    }
                }
            }
        }
        if (stats.unknownDurationRecords > 0) {
            Text(
                text = "${stats.unknownDurationRecords} 条时长未知",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
        }
    }
}

internal fun platformName(packageName: String): String = when (packageName) {
    "com.ss.android.ugc.aweme" -> "抖音"
    "com.xingin.xhs" -> "小红书"
    "tv.danmaku.bili" -> "B站"
    "com.zhihu.android" -> "知乎"
    "com.sina.weibo" -> "微博"
    "com.smile.gifmaker" -> "快手"
    else -> packageName
}

@Composable
internal fun SwissEmotionSummary(report: CognitiveReport?) {
    val emotion = report?.emotionExposure
    val expressions = remember(report?.semanticV2) { report?.semanticV2?.values?.let(::expressionFractions) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "连续时长 —",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Muted
                )
            }
        }
        Surface(
            shape = DesignTokens.InnerShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SwissMetricBar("事实性表述", expressions?.first, DesignTokens.Blue, Modifier.weight(1f))
                SwissMetricBar("情绪化表达", expressions?.second, DesignTokens.Rose, Modifier.weight(1f))
            }
        }
        emotion?.let {
            Text(
                text = "已分析 ${it.knownWorks} / ${it.totalWorks} 篇",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Muted
            )
        }
        emotion?.counts?.forEach { count ->
            SwissMetricBar(
                label = "${count.category.label} · ${count.works} 篇",
                fraction = if (emotion.knownWorks > 0) count.works.toFloat() / emotion.knownWorks else null,
                color = if (count.category == EmotionCategory.JOY || count.category == EmotionCategory.NEUTRAL) DesignTokens.Blue else DesignTokens.Rose
            )
        }
        val phrases = emotion?.works.orEmpty()
            .flatMap { it.emotions }
            .distinctBy { it.eventId to it.evidence }
            .map { it.trigger?.takeIf(String::isNotBlank) ?: it.evidence }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
        if (phrases.isEmpty()) {
            Text("暂无数据", style = MaterialTheme.typography.bodySmall, color = Muted)
        } else {
            Surface(
                shape = DesignTokens.InnerShape,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    phrases.forEachIndexed { idx, (text, count) ->
                        if (idx > 0) {
                            HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.6f), thickness = 0.75.dp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Ink)
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = DesignTokens.Rose.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "×$count",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = DesignTokens.Rose
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
