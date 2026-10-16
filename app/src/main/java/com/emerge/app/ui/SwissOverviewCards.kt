package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwissEvidenceTrend(events: List<BrowseEvent>, semantics: List<EventSemantics>, now: Long) {
    var selectedTime by remember { mutableStateOf<Long?>(null) }
    val request = Triple(events, semantics, now)
    var error by remember(request) { mutableStateOf(false) }
    val result by produceState<Pair<Triple<List<BrowseEvent>, List<EventSemantics>, Long>, List<SwissEvidenceBucket>>?>(null, request) {
        try { value = withContext(Dispatchers.Default) {
            val task = currentCoroutineContext()
            val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.HOURS24, now, semantics = semantics,
                checkCancellation = { task.ensureActive() })
            request to swissEvidenceTrend(report.claims, now)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    val buckets = result?.takeIf { it.first == request }?.second.orEmpty()
    WhiteCard(Modifier.testTag("swiss-evidence-trend")) {
        Text("24小时新旧论据", style = MaterialTheme.typography.titleMedium)
        if (error) Text("分析暂不可用", color = Muted, style = MaterialTheme.typography.bodySmall)
        else if (result?.first != request) Text("加载中", color = Muted, style = MaterialTheme.typography.bodySmall)
        else SwissEvidenceChart(buckets, selectedTime) { selectedTime = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwissEvidenceChart(buckets: List<SwissEvidenceBucket>, selectedTime: Long?, onSelectedTime: (Long) -> Unit) {
    if (buckets.isEmpty()) return
    val selectedIndex = buckets.indexOfFirst { bucket -> selectedTime?.let {
        it >= bucket.start && (it < bucket.end || bucket == buckets.last() && it == bucket.end)
    } == true }.takeIf { it >= 0 } ?: buckets.lastIndex
    val selected = buckets[selectedIndex]
    fun select(index: Int) {
        buckets.getOrNull(index)?.let { onSelectedTime(it.start + (it.end - it.start) / 2) }
    }
    val interval = swissEvidenceInterval(selected)
    val maximum = buckets.maxOf { it.total }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(136.dp).testTag("swiss-evidence-plot")
        .pointerInput(buckets) {
            detectTapGestures { point -> swissChartSelection(point.x, size.width.toFloat(), buckets.size)?.let(::select) }
        }.semantics {
            contentDescription = "$interval 新增论据 ${selected.fresh} 重复材料 ${selected.repeated} 未判断 ${selected.unknown}"
            customActions = buildList {
                if (selectedIndex > 0) add(CustomAccessibilityAction("上一小时") { select(selectedIndex - 1); true })
                if (selectedIndex < buckets.lastIndex) add(CustomAccessibilityAction("下一小时") { select(selectedIndex + 1); true })
            }
        }) {
        val step = size.width / buckets.size
        drawRect(DesignTokens.Ink.copy(alpha = .055f), Offset(selectedIndex * step, 0f), Size(step, size.height))
        drawLine(DesignTokens.Border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        buckets.forEachIndexed { index, bucket ->
            var bottom = size.height
            listOf(bucket.fresh to DesignTokens.Ink, bucket.repeated to DesignTokens.Rose,
                bucket.unknown to Color(0xFFD4D4D8)).forEach { (count, color) ->
                val height = count.toFloat() / maximum * (size.height - 4.dp.toPx())
                if (height > 0) drawRect(color, Offset(index * step + step * .17f, bottom - height), Size(step * .66f, height))
                bottom -= height
            }
        }
        drawLine(DesignTokens.Ink, Offset(selectedIndex * step + step * .08f, size.height),
            Offset(selectedIndex * step + step * .92f, size.height), 2.dp.toPx())
    }
    SwissChartAxis(buckets.filterIndexed { index, _ -> index % 6 == 0 }.map { it.label to null })
    SwissChartSelectionHeader(interval, "上一小时", "下一小时", selectedIndex > 0, selectedIndex < buckets.lastIndex,
        { select(selectedIndex - 1) }, { select(selectedIndex + 1) }, "swiss-evidence")
    FlowRow(Modifier.fillMaxWidth().testTag("swiss-evidence-readout").semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwissLegend("新增论据 ${selected.fresh}", DesignTokens.Ink)
        SwissLegend("重复材料 ${selected.repeated}", DesignTokens.Rose)
        SwissLegend("未判断 ${selected.unknown}", Color(0xFFD4D4D8))
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
        Text("应用时长分布", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(DesignTokens.PillShape).background(DesignTokens.Border)) {
            apps.forEachIndexed { index, app -> Box(Modifier.weight(app.durationMillis.toFloat()).fillMaxHeight().background(colors[index % colors.size])) }
        }
        if (total == 0L) Text("时长未统计", style = MaterialTheme.typography.bodySmall, color = Muted)
        else BoxWithConstraints {
            val columns = if (maxWidth >= 320.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.2f) 4 else 2
            val cellWidth = (maxWidth - 8.dp * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                apps.forEachIndexed { index, app ->
                    Column(Modifier.width(cellWidth), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SwissLegend(names[app.packageName] ?: platformName(app.packageName), colors[index % colors.size])
                        Text(percentText(app.durationMillis.toFloat() / total), style = MaterialTheme.typography.titleSmall)
                        Text(formatDuration(app.durationMillis), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
        }
        if (stats.unknownDurationRecords > 0) Text("${stats.unknownDurationRecords} 条时长未知", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

internal fun platformName(packageName: String): String = when (packageName) {
    "com.ss.android.ugc.aweme" -> "抖音"
    "com.xingin.xhs" -> "小红书"
    "tv.danmaku.bili" -> "B站"
    "com.zhihu.android" -> "知乎"
    else -> packageName
}

@Composable
internal fun SwissEmotionCard(report: CognitiveReport?) {
    val emotion = report?.emotionExposure
    WhiteCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("情绪表达与语境", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("连续时长 —", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SwissMetricBar("事实性风险提醒", null, DesignTokens.Blue, Modifier.weight(1f))
            SwissMetricBar("夸张情绪表达", null, DesignTokens.Rose, Modifier.weight(1f))
        }
        emotion?.let { Text("已分析 ${it.knownWorks} / ${it.totalWorks} 篇", style = MaterialTheme.typography.labelSmall, color = Muted) }
        emotion?.counts?.forEach { count ->
            SwissMetricBar("${count.category.label} · ${count.works} 篇",
                if (emotion.knownWorks > 0) count.works.toFloat() / emotion.knownWorks else null,
                if (count.category == EmotionCategory.JOY || count.category == EmotionCategory.NEUTRAL) DesignTokens.Blue else DesignTokens.Rose)
        }
        val phrases = emotion?.works.orEmpty().flatMap { it.emotions }.distinctBy { it.eventId to it.evidence }
            .map { it.trigger?.takeIf(String::isNotBlank) ?: it.evidence }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4)
        if (phrases.isEmpty()) Text("暂无情绪分析", style = MaterialTheme.typography.bodySmall, color = Muted)
        phrases.forEach { (text, count) -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text("×$count", style = MaterialTheme.typography.labelMedium, color = Muted)
        } }
    }
}

@Composable
internal fun SwissTopicList(report: CognitiveReport?, events: List<BrowseEvent>, semantics: List<EventSemantics>,
    period: StatisticsPeriod, now: Long, onTopic: (String) -> Unit) {
    val topics = remember(report) { report?.topicDigests.orEmpty().groupBy { it.topic }.map { (name, digests) ->
        name to digests.flatMap { it.viewpoints }.flatMap { it.workKeys }.distinct().size
    }.sortedByDescending { it.second }.take(8) }
    val inputKey = remember(report, events, semantics, period, now) { Any() }
    var failed by remember(inputKey) { mutableStateOf(false) }
    val prepared by produceState<Pair<Any, Map<String, List<ViewpointStanceShare>>>?>(null, inputKey) {
        value = null
        try {
            value = inputKey to withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                if (topics.isEmpty()) emptyMap() else {
                    val buckets = ViewpointTimeline.build(events, period, now, ZoneId.systemDefault(), report, semantics)
                    val works = ViewpointTimeline.details(buckets.flatMap { it.items })
                    topics.associate { (name, _) ->
                        currentCoroutineContext().ensureActive()
                        val scoped = swissTopicItems(works, name)
                        name to viewpointSpectrum(ViewpointDistribution.topics(ViewpointTimeline.groups(scoped)))
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val distribution = prepared?.takeIf { it.first === inputKey }?.second
    WhiteCard {
        Text("高频关注话题", style = MaterialTheme.typography.titleMedium)
        if (topics.isEmpty()) Text("暂无话题", color = Muted, style = MaterialTheme.typography.bodySmall)
        topics.forEach { (topic, workCount) ->
            Surface(onClick = { onTopic(topic) }, color = Color.Transparent, shape = DesignTokens.InnerShape,
                modifier = Modifier.fillMaxWidth().testTag("overview-topic-$topic")) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(topic, style = MaterialTheme.typography.titleSmall)
                            Text("$workCount 篇 · 独立来源未核实", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("→", color = Muted)
                    }
                    if (failed) Text("分析暂不可用", color = Muted, style = MaterialTheme.typography.bodySmall)
                    else if (distribution == null) Text("整理中", color = Muted, style = MaterialTheme.typography.bodySmall)
                    else SwissStanceDistribution(distribution[topic].orEmpty())
                }
            }
        }
    }
}
