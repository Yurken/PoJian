package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Record counts, rather than duration or inferred attention, determine the colour. */
@Composable
internal fun HourlyRecordHeatmap(
    counts: List<Int>,
    selectedHour: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    HourlyRecordHeatmapContent(counts, selectedHour, onSelect, modifier) { hour, count ->
        "${hour}时，${count}条保存记录，点击选择时段"
    }
}

/** The review supplies its existing duration descriptions and opens the source sheet. */
@Composable
internal fun HourlyRecordHeatmapContent(
    counts: List<Int>,
    selectedHour: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    countLabel: String = "数字为保存记录数",
    showLegend: Boolean = true,
    hourDescription: (Int, Int) -> String
) {
    val normalizedCounts = List(24) { counts.getOrElse(it) { 0 }.coerceAtLeast(0) }
    val maximum = normalizedCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Each cell remains at least 48 dp wide, including on small phones and split screens.
        val columns = when {
            maxWidth >= 620.dp -> 12
            maxWidth >= 412.dp -> 8
            maxWidth >= 288.dp -> 6
            maxWidth >= 192.dp -> 4
            maxWidth >= 96.dp -> 2
            else -> 1
        }
        val columnSpacing = if (columns > 1) {
            ((maxWidth - 48.dp * columns) / (columns - 1)).coerceIn(0.dp, 4.dp)
        } else 0.dp
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (0 until 24).chunked(columns).forEach { hours ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columnSpacing)) {
                    hours.forEach { hour ->
                        val count = normalizedCounts[hour]
                        val intensity = count.toFloat() / maximum
                        val strong = intensity > .6f
                        val background = when {
                            count == 0 -> scheme.surfaceVariant
                            strong -> scheme.primary
                            else -> lerp(scheme.surface, scheme.primary, .12f + intensity * .5f)
                        }
                        val foreground = if (strong) scheme.onPrimary else scheme.onSurface
                        val shape = RoundedCornerShape(8.dp)
                        Column(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .clip(shape).background(background)
                                .border(BorderStroke(if (selectedHour == hour) 2.dp else 0.dp,
                                    if (selectedHour == hour) {
                                        if (strong) scheme.onPrimary else scheme.primary
                                    } else Color.Transparent), shape)
                                .clickable(role = Role.Button) { onSelect(hour) }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = hourDescription(hour, count)
                                    selected = selectedHour == hour
                                }
                                .testTag("hour-$hour")
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(hour.toString().padStart(2, '0'), color = foreground,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(count.toString(), color = foreground, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (showLegend) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("少", color = Muted, fontSize = 10.sp)
                listOf(scheme.surfaceVariant, lerp(scheme.surface, scheme.primary, .2f),
                    lerp(scheme.surface, scheme.primary, .45f), scheme.primary).forEach { color ->
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
                Text("多", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(countLabel, color = Muted, fontSize = 10.sp)
            }
        }
    }
}
