package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ContentWord
import com.emerge.app.analysis.recentContentWords
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun RecentContentCloud(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>) {
    val words by produceState<List<ContentWord>?>(null, events, period, now, semantics) {
        value = null
        value = withContext(Dispatchers.Default) { recentContentWords(events, period, now, semantics) }
    }
    WhiteCard(Modifier.testTag("recent-content-card")) {
        SectionTitle("近期接触", help = "AI 整理近期内容的主题与对象，字号按相关记录数呈现。")
        val current = words
        when {
            current == null -> Text("正在整理近期内容…", color = Muted, modifier = Modifier.padding(vertical = 32.dp))
            current.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("暂无关键词", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
            else -> ContentWordCloud(current)
        }
    }
}

@Composable
private fun ContentWordCloud(words: List<ContentWord>) {
    val colors = ChartColors
    val min = words.minOf { it.recordCount }
    val max = words.maxOf { it.recordCount }
    Layout(modifier = Modifier.fillMaxWidth().testTag("recent-content-cloud"), content = {
        words.forEachIndexed { index, word ->
            val weight = if (max == min) 0.45f else (word.recordCount - min).toFloat() / (max - min)
            Text(word.text, color = colors[index % colors.size], fontSize = (15 + weight * 21).sp,
                fontWeight = if (weight > 0.6f) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp).clearAndSetSemantics {
                    contentDescription = "${word.text}，${word.recordCount} 条记录"
                })
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val cloudHeight = (if (words.size <= 6) 140.dp else 240.dp).roundToPx()
        val boxes = mutableListOf<Rect>()
        var bottom = cloudHeight
        placeables.forEach { item ->
            var found: Rect? = null
            for (step in 0..1800) {
                val angle = step * 0.38
                val radius = sqrt(step.toDouble()) * 4.dp.toPx()
                val x = (width / 2f + cos(angle) * radius - item.width / 2f).toInt()
                val y = (cloudHeight / 2f + sin(angle) * radius * 0.65 - item.height / 2f).toInt()
                val box = Rect(x.toFloat(), y.toFloat(), (x + item.width).toFloat(), (y + item.height).toFloat())
                if (x >= 0 && y >= 0 && box.right <= width && box.bottom <= cloudHeight && boxes.none { it.overlaps(box) }) {
                    found = box
                    break
                }
            }
            // Large system fonts and narrow screens grow vertically instead of dropping words.
            boxes += found ?: Rect((width - item.width) / 2f, bottom.toFloat(),
                (width + item.width) / 2f, (bottom + item.height).toFloat()).also { bottom += item.height }
        }
        layout(width, constraints.constrainHeight(bottom)) {
            placeables.forEachIndexed { index, item ->
                item.placeRelative(boxes[index].left.toInt(), boxes[index].top.toInt())
            }
        }
    }
}
