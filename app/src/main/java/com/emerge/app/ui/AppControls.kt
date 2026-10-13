@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emerge.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** App-wide controls retain Material input, focus and disabled behavior with shared visual roles. */
@Composable
internal fun PojianButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = shape,
        colors = colors, elevation = null, contentPadding = contentPadding) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { content() }
    }
}

@Composable
internal fun PojianSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = shape, border = border,
        colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurface, disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)),
        elevation = null, contentPadding = contentPadding) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { content() }
    }
}

@Composable
internal fun PojianTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = shape,
        colors = colors, contentPadding = contentPadding) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { content() }
    }
}

@Composable
internal fun PojianIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(34.dp), shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)) {
            Box(contentAlignment = Alignment.Center, content = { content() })
        }
    }
}

/** The whole 48 dp target toggles; the compact track is decorative and has no duplicate semantics. */
@Composable
internal fun PojianSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (checked) 1f else 0f)
    val track by animateColorAsState(if (checked) scheme.primary else scheme.outline.copy(alpha = 0.38f))
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp, 26.dp)) {
            val opacity = if (enabled) 1f else 0.38f
            drawRoundRect(track.copy(alpha = track.alpha * opacity), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val fraction = if (rtl) 1f - progress else progress
            drawCircle(Color.White.copy(alpha = opacity), radius = 10.dp.toPx(),
                center = Offset(13.dp.toPx() + 18.dp.toPx() * fraction, size.height / 2))
        }
    }
}

@Composable
internal fun PojianCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val interaction = if (onCheckedChange == null) Modifier else Modifier.toggleable(checked,
        enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    Box(modifier.size(48.dp).then(interaction), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).background(if (checked) scheme.primary else Color.Transparent, RoundedCornerShape(7.dp))
            .border(1.5.dp, if (checked) scheme.primary else scheme.outline, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Outlined.Check, contentDescription = null,
                tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun PojianRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val interaction = if (onClick == null) Modifier else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
    Box(modifier.size(48.dp).then(interaction), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).border(if (selected) 6.dp else 1.5.dp,
            if (selected) scheme.primary else scheme.outline, CircleShape))
    }
}

@Composable
internal fun PojianFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier) {
    FilterChip(selected, onClick, label, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp),
        border = null, colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
}

@Composable
internal fun PojianSuggestionChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    SuggestionChip(onClick, label, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp), border = null,
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface))
}

@Composable
internal fun PojianTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, singleLine: Boolean = false, minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, enabled: Boolean = true, isError: Boolean = false,
    shape: Shape = RoundedCornerShape(12.dp), visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(value, onValueChange, modifier, enabled = enabled, label = label, placeholder = placeholder,
        supportingText = supportingText, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines, isError = isError,
        shape = shape, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceVariant, unfocusedContainerColor = scheme.surfaceVariant,
            disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.5f),
            focusedBorderColor = scheme.primary, unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent, unfocusedLabelColor = scheme.onSurfaceVariant))
}

@Composable
internal fun PojianSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, onValueChangeFinished: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(value, onValueChange, modifier.heightIn(min = 48.dp), valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(22.dp).shadow(2.dp, CircleShape).background(scheme.surface, CircleShape)
            .border(3.dp, scheme.primary, CircleShape)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val start = Offset(if (rtl) size.width else 0f, size.height / 2)
                val end = Offset(if (rtl) 0f else size.width, size.height / 2)
                drawLine(scheme.outlineVariant, start, end, size.height, StrokeCap.Round)
                drawLine(scheme.primary, start, Offset(if (rtl) size.width * (1 - fraction) else size.width * fraction, size.height / 2), size.height, StrokeCap.Round)
            }
        })
}

@Composable
internal fun PojianSegmentedTabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, counts: List<Int> = emptyList()) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = scheme.surfaceVariant) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(if (selected) scheme.surface else Color.Transparent)
                Surface(selected = selected, onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.Tab },
                    shape = RoundedCornerShape(10.dp), color = color,
                    contentColor = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                    shadowElevation = if (selected) 1.dp else 0.dp) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f, fill = false))
                        counts.getOrNull(index)?.takeIf { it > 0 }?.let { count ->
                            Text("$count", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PojianNavigationBar(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
    visible: Boolean = true, icon: @Composable (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(visible, modifier = Modifier.testTag("main-navigation"),
        enter = slideInVertically(tween(150)) { it } + expandVertically(tween(150), expandFrom = Alignment.Bottom),
        exit = slideOutVertically(tween(150)) { it } + shrinkVertically(tween(150), shrinkTowards = Alignment.Bottom)) {
    Surface(color = scheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(index) })
                        .heightIn(min = 64.dp).padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompositionLocalProvider(LocalContentColor provides if (selected) scheme.primary else scheme.onSurfaceVariant) {
                            Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) { icon(index) }
                            Text(label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun PojianDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null) {
    AlertDialog(onDismissRequest, confirmButton, dismissButton = dismissButton, title = title, text = text,
        shape = RoundedCornerShape(20.dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp)
}
