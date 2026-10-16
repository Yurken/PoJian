package com.emerge.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun SwissSettingsSection(index: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(index, color = Muted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        SwissCard { Column(Modifier.fillMaxWidth(), content = content) }
    }
}

@Composable
internal fun SettingsContentFrame(embedded: Boolean, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    if (embedded) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    else WhiteCard(modifier, content = content)
}

@Composable
internal fun SettingsActionRow(title: String, modifier: Modifier = Modifier, value: String? = null,
    expanded: Boolean? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth / LocalDensity.current.fontScale < 250.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    value?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelMedium) }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                value?.let { Text(it, Modifier.widthIn(max = 140.dp), color = Muted,
                    style = MaterialTheme.typography.labelMedium) }
            }
        }
        Icon(when (expanded) {
            true -> Icons.Outlined.KeyboardArrowUp
            false -> Icons.Outlined.KeyboardArrowDown
            null -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
        }, contentDescription = null, modifier = Modifier.size(20.dp), tint = Muted)
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, status: String? = null) {
    Row(modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Ink else Muted)
            status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Muted) }
        }
        PojianSwitch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun SettingsDisclosure(title: String, value: String? = null, tag: String,
    initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(tag) { mutableStateOf(initiallyExpanded) }
    val motion = rememberSystemMotionEnabled()
    Column(Modifier.fillMaxWidth()) {
        SettingsActionRow(title, Modifier.testTag(tag).semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        }, value = value, expanded = expanded) { expanded = !expanded }
        AnimatedVisibility(expanded,
            enter = if (motion) expandVertically() + fadeIn() else EnterTransition.None,
            exit = if (motion) shrinkVertically() + fadeOut() else ExitTransition.None) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun SettingsHairline() = HorizontalDivider(color = DesignTokens.Border, thickness = 1.dp)
