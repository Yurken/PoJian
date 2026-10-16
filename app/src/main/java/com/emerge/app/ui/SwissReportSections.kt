package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent

internal fun LazyListScope.swissReportSections(report: CognitiveReport, events: List<BrowseEvent>,
    onSelect: (SwissArgumentSelection) -> Unit) {
    val summary = report.emotionExposure
    item("emotion-header") {
        Text("内容情绪", style = MaterialTheme.typography.titleMedium)
        Text("已分析 ${summary.knownWorks} · 未分析 ${summary.unknownWorks}",
            style = MaterialTheme.typography.labelSmall, color = Muted)
    }
    items(summary.counts, key = { "emotion-${it.category}" }) { count ->
        SwissCard(onClick = {
            val evidence = summary.works.flatMap { it.emotions }.filter { it.category == count.category }.distinct()
            onSelect(SwissArgumentSelection(count.category.label, details = SwissViewpointDetails(count.works, count.eventIds,
                evidence.map { SwissEvidenceSource(it.evidence, listOf(it.eventId), label =
                    it.level.label + it.trigger?.let { trigger -> " · 诱因 · $trigger" }.orEmpty()) })))
        }, modifier = Modifier.testTag("report-emotion-${count.category.name}")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(count.category.label, style = MaterialTheme.typography.titleSmall)
                Text("${count.works} 篇 · ${ReportExporter.percent(count.works, summary.knownWorks)}",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
    if (summary.unknownWorks > 0) item("emotion-unknown") {
        PojianTextButton({
            onSelect(SwissArgumentSelection("未分析", details = SwissViewpointDetails(summary.unknownWorks,
                summary.works.filter { it.emotions.isEmpty() }.flatMap { it.eventIds }, emptyList())))
        }, Modifier.testTag("report-emotion-unknown")) { Text("未分析 ${summary.unknownWorks} 篇") }
    }
    val exposure = report.exposure
    if (exposure != null) {
        item("exposure-header") { Text("近7天观察", style = MaterialTheme.typography.titleMedium) }
        if (exposure.viewpoints.isEmpty()) item("exposure-empty") { Text("观察不足", color = Muted) }
        items(exposure.viewpoints, key = { "exposure-${it.key}" }) { view ->
            SwissCard(onClick = {
                val ids = view.supportingEventIds
                val parents = ids.mapNotNull { swissSourceEvent(it, events) }.distinctBy { it.id }
                onSelect(SwissArgumentSelection(view.topic + " · " + view.stance,
                    conditions = view.latest.conditions, details = SwissViewpointDetails(
                        BrowseWorkKey.resolve(parents).distinct().size, ids,
                        view.longitudinal.evidence.map { SwissEvidenceSource(it.quote, listOf(it.eventId), it.historyMatches) })))
            }, modifier = Modifier.testTag("report-exposure-${view.key}")) {
                Text(view.topic, style = MaterialTheme.typography.titleSmall)
                Text(view.title, style = MaterialTheme.typography.bodyMedium)
                Text(listOf(view.subject, view.dimension, view.stance).filter(String::isNotBlank).distinct().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
                Text("主导观点占比 ${String.format(java.util.Locale.CHINA, "%.1f%%", view.share * 100)}",
                    style = MaterialTheme.typography.labelMedium, color = Muted)
                Text("${view.distribution.contacts} 次接触 · ${view.distribution.days} 天 · 已识别 ${view.distribution.classified}",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
                Text("依据重复 ${view.longitudinal.repeated} / ${view.longitudinal.known}",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
    report.topicDigests.forEach { topic ->
        item("topic-${topic.key}") {
            Text(topic.topic + topic.subject.takeIf { it.isNotBlank() && it != topic.topic }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.titleMedium)
            Text("${topic.workCount} 篇", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        items(topic.viewpoints, key = { "topic-${topic.key}-view-${it.key}" }) { viewpoint ->
            SwissCard(onClick = {
                onSelect(SwissArgumentSelection(viewpoint.quote, viewpoint.conditions,
                    SwissViewpointDetails(viewpoint.workCount, viewpoint.observations.map { it.eventId }.distinct(),
                        viewpoint.evidence.map { SwissEvidenceSource(it.text, it.eventIds, it.historyMatches, it.type.label) })))
            }, modifier = Modifier.testTag("report-viewpoint-${viewpoint.key}")) {
                Text(viewpoint.quote, style = MaterialTheme.typography.bodyMedium)
                if (viewpoint.conditions.isNotBlank()) Text(viewpoint.conditions, style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${viewpoint.workCount} 篇 · ${viewpoint.evidence.size} 条依据", style = MaterialTheme.typography.labelSmall, color = Muted)
                viewpoint.evidence.firstOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 3) }
            }
        }
    }
}
