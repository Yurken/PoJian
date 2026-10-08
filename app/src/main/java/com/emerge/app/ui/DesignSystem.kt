package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
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
                Color(0xFFA8B8A5), Color(0xFFC7AEA3), Color(0xFF9FAEA5))
        } else {
            listOf(scheme.primary, scheme.secondary, scheme.tertiary,
                Color(0xFF6F8469), Color(0xFF976F60), Color(0xFF7A8880))
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
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = Ink,
        modifier = Modifier.semantics { heading() }
    )
    if (!subtitle.isNullOrBlank()) {
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
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
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif)
            )
        }
    }
}

@Composable
internal fun ExpandableNote(title: String, body: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起说明" else "展开说明") {
                    expanded = !expanded
                }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "收起 −" else "展开 ＋",
                style = MaterialTheme.typography.labelSmall,
                color = Accent
            )
        }
        if (expanded) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
internal fun MetadataText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = modifier)
}
