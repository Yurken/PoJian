package com.emerge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ContentWord
import com.emerge.app.analysis.recentContentWords
import com.emerge.app.analysis.recentContentWordRecords
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun RecentContentCloud(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, appNames: Map<String, String> = emptyMap()) {
    val words by produceState<List<ContentWord>?>(null, events, period, now, semantics) {
        value = null
        value = withContext(Dispatchers.Default) { recentContentWords(events, period, now, semantics) }
    }
    var selected by rememberSaveable(period) { mutableStateOf<String?>(null) }
    WhiteCard(Modifier.testTag("recent-content-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("近期接触", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() })
            HelpButton("近期接触", "AI 整理近期内容的主题与对象，字号按相关记录数呈现。")
        }
        val current = words
        when {
            current == null -> Text("正在整理近期内容…", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            current.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("暂无关键词", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
            else -> ContentWordCloud(current) { selected = it.text }
        }
    }
    selected?.let { word ->
        ContentWordDetails(word, events, period, now, semantics, appNames) { selected = null }
    }
}

// Preferred centers echo the reference's editorial composition; measured bounds avoid overlap.
private val WordAnchors = listOf(
    Offset(.48f, .46f), Offset(.28f, .24f), Offset(.72f, .64f),
    Offset(.76f, .30f), Offset(.25f, .70f), Offset(.14f, .48f),
    Offset(.86f, .48f), Offset(.46f, .10f), Offset(.55f, .86f),
    Offset(.70f, .14f), Offset(.28f, .89f)
)

@Composable
private fun ContentWordCloud(words: List<ContentWord>, onWord: (ContentWord) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val colors = if (dark) listOf(Color(0xFF93C5FD), Color(0xFF5EEAD4), Color(0xFFFCD34D),
        Color(0xFFC4B5FD), Color(0xFF7DD3FC)) else listOf(Color(0xFF1E40AF), Color(0xFF0F766E),
        Color(0xFFB45309), Color(0xFF4338CA), Color(0xFF0369A1))
    val glow = scheme.primaryContainer.copy(alpha = if (dark) .65f else .8f)
    val edge = scheme.surfaceContainerLow.copy(alpha = .2f)
    val min = words.minOf { it.recordCount }
    val max = words.maxOf { it.recordCount }
    Layout(modifier = Modifier.fillMaxWidth().testTag("recent-content-cloud")
        .clip(RoundedCornerShape(14.dp)).drawWithCache {
            val brush = Brush.radialGradient(listOf(glow, edge),
                center = Offset(size.width * .5f, size.height * .46f),
                radius = maxOf(size.width, size.height) * .65f)
            onDrawBehind { drawRect(brush) }
        }, content = {
        words.forEachIndexed { index, word ->
            val weight = if (max == min) .4f else (word.recordCount - min).toFloat() / (max - min)
            val label = "${word.text}，${word.recordCount} 条记录"
            val baseColor = when {
                weight >= .8f -> colors[0]
                index < colors.size -> colors[index]
                else -> scheme.onSurfaceVariant
            }
            val wordColor = readableCloudColor(baseColor, .55f + weight * .45f, glow.compositeOver(scheme.surface))
            Box(contentAlignment = Alignment.Center,
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = "查看相关原文") { onWord(word) }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .clearAndSetSemantics {
                        contentDescription = label
                        role = Role.Button
                        onClick(label = "查看相关原文") { onWord(word); true }
                    }) {
                Text(word.text, color = wordColor, fontSize = (14 + weight * 20).sp,
                    lineHeight = (18 + weight * 24).sp, textAlign = TextAlign.Center,
                    fontWeight = when { weight >= .75f -> FontWeight.ExtraBold; weight >= .4f -> FontWeight.Bold; else -> FontWeight.Medium },
                    letterSpacing = if (weight >= .8f) (-.3).sp else 0.sp)
            }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val inset = 8.dp.roundToPx().coerceAtMost(width / 4)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = (width - 2 * inset).coerceAtLeast(0))) }
        val baseHeight = (if (words.size <= 3) 120.dp else 180.dp).roundToPx()
        val contentArea = placeables.sumOf { it.width.toDouble() * it.height }
        var cloudHeight = maxOf(baseHeight, (contentArea / (width.coerceAtLeast(1) * .65)).toInt(),
            (placeables.maxOfOrNull { it.height } ?: 0) + 2 * inset)
        fun arrange(cloudHeight: Int): Pair<List<Rect>, Int> {
            val boxes = mutableListOf<Rect>()
            var bottom = cloudHeight
            placeables.forEachIndexed { index, item ->
                val anchor = WordAnchors[index % WordAnchors.size]
                var found: Rect? = null
                for (step in 0..1200) {
                    val angle = step * .38
                    val radius = sqrt(step.toDouble()) * 4.dp.toPx()
                    val x = (width * anchor.x + cos(angle) * radius - item.width / 2f).toInt()
                    val y = (cloudHeight * anchor.y + sin(angle) * radius * .8 - item.height / 2f).toInt()
                    val box = Rect(x.toFloat(), y.toFloat(), (x + item.width).toFloat(), (y + item.height).toFloat())
                    if (x >= inset && y >= inset && box.right <= width - inset && box.bottom <= cloudHeight - inset && boxes.none { it.overlaps(box) }) {
                        found = box
                        break
                    }
                }
                // Search remaining gaps before growing the stage; edge anchors can miss distant spaces.
                if (found == null) {
                    var nearest = Float.MAX_VALUE
                    val stride = 2.dp.roundToPx().coerceAtLeast(1)
                    for (y in inset..(cloudHeight - inset - item.height) step stride) {
                        for (x in inset..(width - inset - item.width) step stride) {
                            val box = Rect(x.toFloat(), y.toFloat(), (x + item.width).toFloat(), (y + item.height).toFloat())
                            val dx = box.center.x - width * anchor.x
                            val dy = box.center.y - cloudHeight * anchor.y
                            val distance = dx * dx + dy * dy
                            if (distance < nearest && boxes.none { it.overlaps(box) }) {
                                found = box
                                nearest = distance
                            }
                        }
                    }
                }
                // Keep every word and its complete text reachable, including at large font scales.
                boxes += found ?: Rect((width - item.width) / 2f, bottom.toFloat(),
                    (width + item.width) / 2f, (bottom + item.height).toFloat()).also { bottom += item.height + inset }
            }
            return boxes to bottom
        }
        var (boxes, bottom) = arrange(cloudHeight)
        // When touch targets need more room, reflow the whole composition before appending rows.
        var attempts = 0
        while (bottom > cloudHeight && attempts < 6) {
            cloudHeight += 48.dp.roundToPx()
            val arrangement = arrange(cloudHeight)
            boxes = arrangement.first
            bottom = arrangement.second
            attempts++
        }
        layout(width, constraints.constrainHeight(bottom)) {
            placeables.forEachIndexed { index, item ->
                item.placeRelative(boxes[index].left.toInt(), boxes[index].top.toInt())
            }
        }
    }
}

/** Preserve foreground/background depth without washing out small labels in either theme. */
private fun readableCloudColor(base: Color, preferredAlpha: Float, background: Color): Color {
    val backgroundLuminance = background.luminance()
    var alpha = preferredAlpha.coerceIn(0f, 1f)
    while (alpha < 1f) {
        val foregroundLuminance = base.copy(alpha = alpha).compositeOver(background).luminance()
        val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + .05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + .05f)
        if (contrast >= 4.5f) break
        alpha = (alpha + .05f).coerceAtMost(1f)
    }
    return base.copy(alpha = alpha)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentWordDetails(word: String, events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, appNames: Map<String, String>, onDismiss: () -> Unit) {
    val originals by produceState<List<BrowseEvent>?>(null, word, events, period, now, semantics) {
        value = null
        value = withContext(Dispatchers.Default) { recentContentWordRecords(word, events, period, now, semantics) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(word, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            LazyColumn(Modifier.weight(1f).testTag("recent-content-details"),
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val current = originals
                item {
                    MetadataText(when {
                        current == null -> "正在查找相关原文…"
                        current.isEmpty() -> "对应原文暂不可用"
                        else -> "${period.label} · ${current.size} 条相关记录"
                    })
                }
                items(current.orEmpty(), key = { it.id }) { event ->
                    Column(Modifier.testTag("recent-content-original-${event.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val time = Instant.ofEpochMilli(event.timestamp).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                        MetadataText("${appNames[event.packageName] ?: event.packageName} · $time")
                        QuoteBlock(event.visibleText)
                    }
                }
            }
        }
    }
}
