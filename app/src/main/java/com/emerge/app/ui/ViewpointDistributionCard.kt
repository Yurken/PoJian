@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.*
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun DailyViewpointDistribution(groups: List<ViewpointShare>, date: String, names: Map<String, String>, start: Long, end: Long,
    stateKey: String = date) {
    val total = groups.firstOrNull()?.total ?: 0
    val topics = remember(groups) { ViewpointDistribution.topics(groups) }
    var topicKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(stateKey) { mutableStateOf(ViewpointFilter.ALL) }
    var query by rememberSaveable(stateKey) { mutableStateOf("") }
    var overrides by rememberSaveable(stateKey) { mutableStateOf(mapOf<String, Boolean>()) }
    var sourceKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    // A refreshed analysis can replace topics while the selected date remains the same.
    val activeTopic = topicKey?.takeIf { selected -> topics.any { it.key == selected } }
    val visible = remember(topics, activeTopic, filter, query) {
        ViewpointDistribution.filter(topics, activeTopic, filter, query)
    }
    var pageIndex by rememberSaveable(stateKey, activeTopic, filter, query) { mutableIntStateOf(0) }
    val entries = remember(visible) { ViewpointDistribution.entries(visible) }
    val page = remember(entries, pageIndex) { ViewpointDistribution.page(entries, pageIndex) }
    LaunchedEffect(page.index) { pageIndex = page.index }
    val pageStart = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun changePage(index: Int) {
        pageIndex = index
        sourceKey = null
        scope.launch { pageStart.bringIntoView() }
    }
    fun expanded(topic: ViewpointTopic) = overrides[topic.key] ?: topic.structured
    val allExpanded = visible.isNotEmpty() && visible.all(::expanded)
    val visibleGroups = visible.flatMap { it.groups }
    val source = visibleGroups.firstOrNull { it.key == sourceKey }
    val green = distributionGreen()

    Column(Modifier.fillMaxWidth().testTag("daily-viewpoint-card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("观点分布", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            MetadataText("$date · $total 个作品")
        }
        if (total > 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopicChip("全部观点 ($total)", activeTopic == null, green, { topicKey = null }, "viewpoint-topic-all")
                topics.forEach { topic ->
                    TopicChip("${topic.title} (${topic.workCount})", activeTopic == topic.key, green,
                        { topicKey = topic.key }, "viewpoint-filter-${topic.key}")
                }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("viewpoint-search"), shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium, label = { Text("搜索观点") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (query.isNotEmpty()) { {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "清除搜索") }
                } } else null)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    PojianTextButton(onClick = { menu = true }, modifier = Modifier.testTag("viewpoint-state-filter")) {
                        Text(analysisSourceLabel(filter.label))
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ViewpointFilter.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(analysisSourceLabel(option.label)) }, onClick = { filter = option; menu = false },
                                modifier = Modifier.testTag("viewpoint-state-${option.name}"))
                        }
                    }
                }
                PojianTextButton(onClick = { overrides = overrides + visible.associate { it.key to !allExpanded } },
                    enabled = visible.isNotEmpty(), modifier = Modifier.testTag("viewpoint-expand-all")) {
                    Icon(if (allExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (allExpanded) "全部收起" else "全部展开")
                }
            }
        }
        if (page.count > 1) ViewpointPageControls(page.index, page.count, ::changePage,
            Modifier.bringIntoViewRequester(pageStart).testTag("viewpoint-pages-top"))
        // Read only the current page; preserve percentages against the full statistics window.
        val pageKeys = page.topics.flatMap { it.groups }.map { it.key }.toSet()
        val visibleByKey = visibleGroups.filter { it.key in pageKeys }.associateBy { it.key }
        Column(Modifier.fillMaxWidth().testTag("daily-viewpoint-pie").semantics {
            contentDescription = groups.mapNotNull { visibleByKey[it.key] }.joinToString("；") {
                "${it.label}，${it.items.size}个作品，${percentage(it.percent)}%"
            }.ifBlank { "暂无作品" }
        }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            page.topics.forEach { section ->
                val topic = section.topic
                key(date, topic.key) {
                    ViewpointTopicCard(topic, section.groups, expanded(topic), { overrides = overrides + (topic.key to !expanded(topic)) },
                        { sourceKey = it.key }, date, names, start, end)
                }
            }
            if (total == 0) MetadataText("当天没有保存的浏览作品。")
            else if (visible.isEmpty()) MetadataText("无匹配观点")
        }
        if (page.count > 1) ViewpointPageControls(page.index, page.count, ::changePage,
            Modifier.testTag("viewpoint-pages-bottom"))
    }
    if (source != null) {
        ModalBottomSheet(onDismissRequest = { sourceKey = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .padding(bottom = 24.dp).testTag("viewpoint-source-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("原文", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    PojianTextButton(onClick = { sourceKey = null }) { Text("关闭") }
                }
                ViewpointSources(source, start, end, names, "distribution-source-$date", initiallyShowRaw = true)
            }
        }
    }
}

@Composable
private fun ViewpointPageControls(index: Int, count: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        PojianTextButton(onClick = { onChange(index - 1) }, enabled = index > 0) { Text("上一页") }
        MetadataText("${index + 1} / $count")
        PojianTextButton(onClick = { onChange(index + 1) }, enabled = index + 1 < count) { Text("下一页") }
    }
}

@Composable
private fun TopicChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit, tag: String) {
    FilterChip(selected = selected, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        label = { Text(text, style = MaterialTheme.typography.labelMedium) }, shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = .13f),
            selectedLabelColor = accent), border = BorderStroke(1.dp,
            if (selected) accent.copy(alpha = .4f) else MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ViewpointTopicCard(topic: ViewpointTopic, pageGroups: List<ViewpointShare>, expanded: Boolean, onToggle: () -> Unit,
    onSource: (ViewpointShare) -> Unit, date: String, names: Map<String, String>, start: Long, end: Long) {
    val scheme = MaterialTheme.colorScheme
    val uncertain = topic.groups.any { group -> group.items.any { item ->
        item.claims.any { it.analysisSource == "structured_model" && !it.confirmed }
    } }
    val accent = when {
        uncertain -> scheme.tertiary
        topic.structured -> distributionGreen()
        else -> scheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, accent.copy(alpha = .2f)),
        color = scheme.surface, modifier = Modifier.fillMaxWidth().testTag("viewpoint-topic-${topic.key}")) {
        Column {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(accent.copy(alpha = .10f),
                accent.copy(alpha = .02f)))).clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${topic.workCount} 个作品", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text("${percentage(topic.percent)}% (${topic.workCount}/${topic.total})",
                        style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { (topic.percent / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp), color = accent,
                    trackColor = accent.copy(alpha = .10f), gapSize = 0.dp, drawStopIndicator = {})
            }
            if (expanded) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (topic.structured) pageGroups.forEach { share ->
                        key(share.key) { StructuredViewpointCard(share, start, end) { onSource(share) } }
                    } else pageGroups.forEach { share ->
                        ViewpointSources(share, start, end, names, "pie-$date")
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredViewpointCard(share: ViewpointShare, start: Long, end: Long, onSource: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val claim = share.items.firstOrNull()?.claims?.firstOrNull()
    val uncertain = claim?.confirmed == false
    val window = remember(start, end) { ObservationWindow(start, end, java.time.ZoneId.systemDefault()) }
    val duration = if (share.items.any { it.durationMillis(window) == null }) null
        else window.unionDuration(share.items.flatMap { it.records }.filter { it.sourceType == "accessibility" }.distinctBy { it.id })
    val level = WorkTopicPresentation.impact(share.items.flatMap { it.claims }, duration)
    val accent = impactColor(level)
    val title = claim?.let(WorkTopicPresentation::phrase) ?: share.label
    Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = .035f),
        border = BorderStroke(1.dp, accent.copy(alpha = .17f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DistributionBadge("影响提示${level.label}", accent)
                if (uncertain) DistributionBadge("待确认", scheme.tertiary)
            }
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            MetadataText(duration?.let(::formatDuration) ?: "时长未统计")
            HorizontalDivider(color = accent.copy(alpha = .13f))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${share.items.size} 个 · ${percentage(share.percent)}%", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                PojianTextButton(onClick = onSource, contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = accent)) { Text("查看原文 ›") }
            }
        }
    }
}

@Composable
private fun DistributionBadge(text: String, accent: Color) {
    Surface(color = accent.copy(alpha = .10f), shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = accent,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun distributionGreen(): Color = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
    Color(0xFF5EEAD4) else Color(0xFF047857)

private fun percentage(value: Double) = String.format(Locale.CHINA, "%.1f", value)
