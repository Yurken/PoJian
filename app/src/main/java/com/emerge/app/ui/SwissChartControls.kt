package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun SwissChartSelectionHeader(label: String, previousLabel: String, nextLabel: String,
    previousEnabled: Boolean, nextEnabled: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = previousEnabled,
            modifier = Modifier.size(48.dp).testTag("$tag-previous")) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = previousLabel)
        }
        Text(label, Modifier.weight(1f).testTag("$tag-date"), style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        IconButton(onClick = onNext, enabled = nextEnabled,
            modifier = Modifier.size(48.dp).testTag("$tag-next")) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = nextLabel)
        }
    }
}

@Composable
internal fun SwissChartAxis(labels: List<Pair<String, String?>>, selectedIndex: Int? = null) {
    if (labels.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelWidth = 44.dp * LocalDensity.current.fontScale
        val indices = when {
            maxWidth >= labelWidth * labels.size -> labels.indices.toList()
            labels.size > 2 && maxWidth >= labelWidth * 3 -> listOf(0, labels.lastIndex / 2, labels.lastIndex)
            else -> listOf(0, labels.lastIndex).distinct()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            indices.forEach { index ->
                val (label, value) = labels[index]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = if (index == selectedIndex) DesignTokens.Ink else Muted,
                        fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal)
                    value?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted,
                        fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
