package com.emerge.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object DesignTokens {
    val Ink = Color(0xFF09090B)
    val Background = Color(0xFFF8FAFC)
    val Surface = Color.White
    val Border = Color(0xFFE4E4E7)
    val SecondaryText = Color(0xFF52525B)
    val MutedText = Color(0xFFA1A1AA)
    val Rose = Color(0xFFE11D48)
    val Amber = Color(0xFFF59E0B)
    val Blue = Color(0xFF2563EB)
    val Emerald = Color(0xFF10B981)
    val CardShape = RoundedCornerShape(16.dp)
    val InnerShape = RoundedCornerShape(10.dp)
    val PillShape = RoundedCornerShape(999.dp)
    val ControlShape = RoundedCornerShape(14.dp)
    val SegmentShape = RoundedCornerShape(12.dp)
    val ControlInk = Color(0xFF18181B)
    val CocoonHeroBrush = Brush.linearGradient(listOf(Ink, Ink))
    val SupportAccent = Blue
    val NeutralAccent = Amber
    val ChallengeAccent = Rose
}

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

/** Fixed category colors accompany visible labels and values. */
internal val ChartColors: List<Color>
    @Composable get() = listOf(DesignTokens.Ink, DesignTokens.Blue, DesignTokens.Rose,
        DesignTokens.Amber, DesignTokens.Emerald, DesignTokens.MutedText)

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun WhiteCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) = SwissCard(modifier = modifier, onClick = onClick, content = content)

@Composable
internal fun SwissCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = DesignTokens.CardShape
    val border = BorderStroke(1.dp, DesignTokens.Border)
    val cardContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(), shape = shape,
            color = DesignTokens.Surface,
            contentColor = DesignTokens.Ink,
            border = border, shadowElevation = 0.dp, tonalElevation = 0.dp, content = cardContent
        )
    } else {
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val motionEnabled = rememberSystemMotionEnabled()
        val scale by animateFloatAsState(
            targetValue = if (pressed && motionEnabled) .99f else 1f,
            animationSpec = if (motionEnabled) spring(
                dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium,
                visibilityThreshold = .0001f
            ) else snap(),
            label = "swiss-card-press"
        )
        val view = LocalView.current
        Surface(
            onClick = {
                // The platform respects the user's haptic setting; no vibration permission is needed.
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
            interactionSource = interactions,
            modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }, shape = shape,
            color = DesignTokens.Surface,
            contentColor = DesignTokens.Ink,
            border = if (pressed) BorderStroke(1.dp, DesignTokens.Ink.copy(alpha = .24f)) else border,
            shadowElevation = 0.dp, tonalElevation = 0.dp, content = cardContent
        )
    }
}

/** Primary actions use the same solid fill and corner radius throughout the app. */
@Composable
internal fun PojianPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    PojianButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PojianActionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PojianSecondaryButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = DesignTokens.ControlShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp)
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

/** Help entry points are intentionally disabled; retain the implementation for later restoration. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun HelpButton(title: String, body: String, modifier: Modifier = Modifier) {
    /*
    var open by rememberSaveable(title) { mutableStateOf(false) }
    PojianIconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Outlined.Info, contentDescription = "${title}说明", tint = Muted)
    }
    if (open) HelpDialog(title, body) { open = false }
    */
}

@Composable
private fun HelpDialog(title: String, body: String, onDismiss: () -> Unit) {
    PojianDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { PojianTextButton(onClick = onDismiss) { Text("关闭") } })
}

/** Selectable source excerpt with a leading brand accent that grows with the text. */
@Composable
internal fun QuoteBlock(text: String, modifier: Modifier = Modifier, fromAudio: Boolean = false) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Accent))
            SelectionContainer(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                AudioSourceContent(fromAudio) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun HelpLink(title: String, body: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    PojianTextButton(onClick = { open = true }, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
    if (open) HelpDialog(title, body) { open = false }
}

@Composable
internal fun MetadataText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = modifier)
}
