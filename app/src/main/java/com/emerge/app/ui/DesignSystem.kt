package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onClick: (() -> Unit)? = null,
    elevation: Dp = 1.5.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    val cardContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(), shape = shape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border, shadowElevation = elevation, content = cardContent
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(), shape = shape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border, shadowElevation = elevation, content = cardContent
        )
    }
}

/** Keeps Material button semantics, disabled state and ripple with a subtle brand gradient. */
@Composable
internal fun PojianPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val disabled = scheme.onSurface.copy(alpha = 0.12f).compositeOver(scheme.surface)
    val gradient = Brush.horizontalGradient(
        if (enabled) listOf(scheme.primary, lerp(scheme.primary, scheme.onBackground, 0.08f))
        else listOf(disabled, disabled)
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .shadow(if (enabled) 2.dp else 0.dp, shape).background(gradient, shape),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PojianActionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (!text.endsWith("›")) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(15.dp))
            }
        }
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

/** Selectable source excerpt with a leading brand accent that grows with the text. */
@Composable
internal fun QuoteBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Accent))
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
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
