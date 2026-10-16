package com.emerge.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SwissSettingsSection(
    index: String,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = DesignTokens.Ink,
                contentColor = Color.White
            ) {
                Text(
                    text = index,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }
        SwissCard {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun SettingsContentFrame(
    embedded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (embedded) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    } else {
        WhiteCard(modifier = modifier, content = content)
    }
}

@Composable
internal fun SwissStatusPill(text: String) {
    val positive = text in setOf("已就绪", "就绪", "已配置", "已启用", "已连接", "记录中", "支持", "按容量保留")
    val warning = text in setOf("未授权", "未连接", "下载失败", "模型未就绪")
    val bgColor = when {
        positive -> DesignTokens.Emerald.copy(alpha = 0.12f)
        warning -> DesignTokens.Amber.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        positive -> Color(0xFF047857)
        warning -> Color(0xFFB45309)
        else -> Muted
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (positive || warning) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (positive) DesignTokens.Emerald else DesignTokens.Amber)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    expanded: Boolean? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth / LocalDensity.current.fontScale < 250.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Ink
                    )
                    value?.let { SwissStatusPill(it) }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Ink
                    )
                    value?.let { SwissStatusPill(it) }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (expanded == true) DesignTokens.Ink.copy(alpha = 0.08f) else Color.Transparent,
            modifier = Modifier.size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (expanded) {
                        true -> Icons.Outlined.KeyboardArrowUp
                        false -> Icons.Outlined.KeyboardArrowDown
                        null -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (expanded == true) Ink else Muted
                )
            }
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    status: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Ink else Muted
            )
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted
                )
            }
        }
        PojianSwitch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun SettingsDisclosure(
    title: String,
    value: String? = null,
    tag: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(tag) { mutableStateOf(initiallyExpanded) }
    val motion = rememberSystemMotionEnabled()
    Column(Modifier.fillMaxWidth()) {
        SettingsActionRow(
            title = title,
            modifier = Modifier.testTag(tag).semantics {
                stateDescription = if (expanded) "已展开" else "已收起"
            },
            value = value,
            expanded = expanded
        ) { expanded = !expanded }
        AnimatedVisibility(
            visible = expanded,
            enter = if (motion) expandVertically() + fadeIn() else EnterTransition.None,
            exit = if (motion) shrinkVertically() + fadeOut() else ExitTransition.None
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                shape = DesignTokens.InnerShape,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
internal fun SettingsHairline() = HorizontalDivider(
    color = DesignTokens.Border.copy(alpha = 0.75f),
    thickness = 0.75.dp
)
