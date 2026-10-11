package com.emerge.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val Accent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

val PageBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background

val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

internal val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

internal val Blue: Color
    @Composable get() = MaterialTheme.colorScheme.secondary

internal val Amber: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

/** Charts keep the same category order in either theme; accompanying labels carry the values. */
internal val ChartColors: List<Color>
    @Composable get() {
        val scheme = MaterialTheme.colorScheme
        return if (scheme.background.luminance() < 0.5f) {
            listOf(scheme.primary, scheme.secondary, scheme.tertiary,
                Color(0xFF94A3B8), Color(0xFF5EEAD4), Color(0xFFC4B5FD))
        } else {
            listOf(scheme.primary, scheme.secondary, scheme.tertiary,
                Color(0xFF64748B), Color(0xFF0F766E), Color(0xFF7C3AED))
        }
    }

@Composable
internal fun WhiteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null, help: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (!help.isNullOrBlank()) HelpButton(title, help)
    }
    if (!subtitle.isNullOrBlank()) MetadataText(subtitle)
}

/** Contextual help stays out of the reading flow, with a full touch target and spoken label. */
@Composable
internal fun HelpButton(title: String, body: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Outlined.Info, contentDescription = "${title}说明", tint = Muted)
    }
    if (open) HelpDialog(title, body) { open = false }
}

@Composable
private fun HelpDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

/** An unclipped, selectable source excerpt rather than a chat bubble. */
@Composable
internal fun QuoteBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
internal fun HelpLink(title: String, body: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
    if (open) HelpDialog(title, body) { open = false }
}

@Composable
internal fun MetadataText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = modifier)
}
