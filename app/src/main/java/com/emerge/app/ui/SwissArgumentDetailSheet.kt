package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SwissArgumentSelection(val title: String, val conditions: String = "",
    val details: SwissViewpointDetails)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwissArgumentDetailSheet(selection: SwissArgumentSelection, events: List<BrowseEvent>,
    semantics: List<EventSemantics>, report: CognitiveReport?, appNames: Map<String, String>, onDismiss: () -> Unit) {
    var sourceId by remember(selection) { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val byId = remember(events) { events.associateBy { it.id } }
    val sources = remember(selection, events) {
        selection.details.sourceIds.mapNotNull { swissSourceEvent(it, events) }.distinctBy { it.id }
    }
    val evidence = remember(selection, events) {
        selection.details.evidence.mapNotNull { fragment ->
            val owners = fragment.eventIds.filter { byId[it]?.visibleText?.contains(fragment.text) == true }
            if (owners.isEmpty()) null else fragment.copy(eventIds = owners, historyMatches = fragment.historyMatches.filter { match ->
                byId[match.eventId]?.let { prior -> prior.visibleText.contains(match.quote) &&
                    owners.any { id -> prior.timestamp < byId.getValue(id).timestamp } } == true
            })
        }
    }
    val source = sourceId?.let { swissSourceEvent(it, events) }
    val secureResults = remember(selection, events, semantics) {
        selection.details.sourceIds.mapNotNull { id -> byId[id]?.let { event ->
            semantics.filter { it.eventId == id }.maxByOrNull { it.analyzedAt }?.takeIf { it.validFor(event) }?.semanticV2
        } }.distinct()
    }
    if (source != null) {
        SwissWorkDetailSheet(source, events, semantics, report, appNames,
            onDismiss = { sourceId = null }, onRelated = { sourceId = it.id })
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().testTag("swiss-argument-detail"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selection.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    PojianIconButton(onDismiss, Modifier.testTag("argument-detail-close")) { Icon(Icons.Outlined.Close, "关闭") }
                }
                if (selection.conditions.isNotBlank()) Text(selection.conditions, style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${selection.details.workCount} 篇", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            item("evidence-header") { Text("核心依据与历史对照", style = MaterialTheme.typography.titleSmall) }
            secureResults.forEachIndexed { index, result ->
                if (result.emotionalGuidance.isOverExtrapolation) item("guidance-$index") {
                    GuidanceEvidenceCard(result.emotionalGuidance)
                }
                result.facts.mapNotNull { it.matchedSourceNote }.distinct().forEachIndexed { noteIndex, note ->
                    item("source-note-$index-$noteIndex") { Text(note, color = DesignTokens.Amber,
                        style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (evidence.isEmpty()) item("no-evidence") { Text("暂无依据", color = Muted) }
            evidence.forEachIndexed { index, fragment ->
                item("evidence-$index") {
                    HorizontalDivider()
                    if (fragment.label.isNotBlank()) Text(fragment.label, style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(fragment.text, style = MaterialTheme.typography.bodyMedium)
                }
                items(fragment.eventIds, key = { "evidence-$index-source-$it" }) { id ->
                    PojianTextButton({ sourceId = id }, Modifier.testTag("argument-evidence-$index-source-$id")) { Text("原文 #$id") }
                }
                items(fragment.historyMatches.distinct(), key = { "evidence-$index-history-${it.eventId}-${it.quote}" }) { match ->
                    Column {
                        Text(match.quote, style = MaterialTheme.typography.bodySmall, color = Muted)
                        PojianTextButton({ sourceId = match.eventId }, Modifier.testTag("argument-history-${match.eventId}")) { Text("历史记录 #${match.eventId}") }
                    }
                }
            }
            item("sources-header") { Text("原文", style = MaterialTheme.typography.titleSmall) }
            items(sources, key = { "source-${it.id}" }) { event ->
                SwissCard(onClick = { sourceId = event.id }, modifier = Modifier.testTag("argument-source-${event.id}")) {
                    Text(appNames[event.packageName] ?: swissKnownApps[event.packageName] ?: event.packageName,
                        style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(event.visibleText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                    Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(event.timestamp)), style = MaterialTheme.typography.labelSmall, color = Muted)
                    WorkSourceButton(event, onUnavailable = { sourceId = event.id })
                }
            }
        }
    }
}
