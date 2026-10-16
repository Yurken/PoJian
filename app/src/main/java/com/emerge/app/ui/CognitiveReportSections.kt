package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorPolicy
import com.emerge.app.monitoring.SupplementKnownArgument
import com.emerge.app.monitoring.SupplementRequest

@Composable
internal fun CognitiveReportSections(report: CognitiveReport, events: List<BrowseEvent>,
    onClaims: (List<ClaimObservation>) -> Unit, supplementEnabled: Boolean,
    onSupplement: (SupplementRequest) -> Unit) {
    if (report.topicDigests.isNotEmpty()) WhiteCard {
        SectionTitle("主题观点")
        var expanded by remember(report.generatedAt) { mutableStateOf<String?>(null) }
        var topicLimit by remember(report.generatedAt) { mutableIntStateOf(4) }
        report.topicDigests.take(topicLimit).forEach { topic ->
            PojianTextButton(onClick = { expanded = if (expanded == topic.key) null else topic.key },
                modifier = Modifier.fillMaxWidth().testTag("topic-digest-${topic.key}")) {
                Text(topic.topic + topic.subject.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    modifier = Modifier.weight(1f))
                Text("${topic.workCount} 个作品")
            }
            if (expanded == topic.key) {
              var viewpointLimit by remember(report.generatedAt, topic.key) { mutableIntStateOf(3) }
              topic.viewpoints.take(viewpointLimit).forEach { viewpoint ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (viewpoint.dimension.isNotBlank()) Text(viewpoint.dimension, style = MaterialTheme.typography.titleSmall)
                QuoteBlock(viewpoint.quote)
                if (viewpoint.conditions.isNotBlank()) MetadataText(viewpoint.conditions)
                MetadataText("${viewpoint.workCount} 个作品 · ${viewpoint.observations.size} 条观点")
                var evidenceLimit by remember(report.generatedAt, viewpoint.key) { mutableIntStateOf(3) }
                viewpoint.evidence.take(evidenceLimit).forEach { evidence ->
                    Text(evidence.type.label, style = MaterialTheme.typography.labelMedium)
                    QuoteBlock(evidence.text)
                    MetadataText(evidence.eventIds.joinToString(" · ") { "#$it" })
                }
                if (viewpoint.evidence.size > evidenceLimit) PojianTextButton(onClick = { evidenceLimit += 3 }) { Text("更多论据") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PojianTextButton(onClick = { onClaims(viewpoint.observations) }) { Text("原文") }
                    PojianTextButton(enabled = supplementEnabled, onClick = {
                        onSupplement(SupplementRequest(
                            scopeKey = MonitorPolicy.hash("${report.generatedAt}|${report.start}|${report.end}|${topic.key}|${viewpoint.key}"),
                            topic = topic.topic, subject = topic.subject, dimension = viewpoint.dimension,
                            conditions = viewpoint.conditions,
                            known = viewpoint.observations.distinctBy { it.quote to it.evidence }.take(6).map {
                                SupplementKnownArgument(it.quote, it.evidenceDetails.firstOrNull()?.text.orEmpty())
                            }))
                    }) { Text("补充资料") }
                }
              }
              if (topic.viewpoints.size > viewpointLimit) PojianTextButton(onClick = { viewpointLimit += 3 }) { Text("更多观点") }
            }
        }
        if (report.topicDigests.size > topicLimit) PojianTextButton(onClick = { topicLimit += 4 }) { Text("更多主题") }
    }
    val summary = report.emotionExposure
    WhiteCard {
        SectionTitle("内容情绪")
        MetadataText("已分析 ${summary.knownWorks} · 未分析 ${summary.unknownWorks}")
        var category by remember(report.generatedAt) { mutableStateOf<EmotionCategory?>(null) }
        summary.counts.forEach { count ->
            PojianTextButton(onClick = { category = if (category == count.category) null else count.category },
                modifier = Modifier.fillMaxWidth()) {
                Text(count.category.label, modifier = Modifier.weight(1f))
                Text("${count.works} 个作品 · ${ReportExporter.percent(count.works, summary.knownWorks)}")
            }
            if (category == count.category) {
                val evidence = summary.works.flatMap { it.emotions }.filter { it.category == category }.distinct()
                var limit by remember(report.generatedAt, category) { mutableIntStateOf(3) }
                evidence.take(limit).forEach { item ->
                    MetadataText(item.level.label)
                    QuoteBlock(item.evidence)
                    item.trigger?.let { Text("诱因 · $it", style = MaterialTheme.typography.bodySmall) }
                    val related = report.claims.filter { it.eventId == item.eventId }
                    if (related.isNotEmpty()) PojianTextButton(onClick = { onClaims(related) }) { Text("原文 #${item.eventId}") }
                    else events.firstOrNull { it.id == item.eventId }?.let { QuoteBlock(it.visibleText) }
                }
                if (evidence.size > 3) PojianTextButton(onClick = { limit = if (limit < evidence.size) limit + 3 else 3 }) {
                    Text(if (limit < evidence.size) "更多" else "收起")
                }
            }
        }
        if (summary.byTopic.isNotEmpty()) {
            var showTopics by remember(report.generatedAt) { mutableStateOf(false) }
            PojianTextButton(onClick = { showTopics = !showTopics }) { Text("主题分布") }
            if (showTopics) summary.byTopic.forEach { topic ->
                Text(topic.topic, style = MaterialTheme.typography.titleSmall)
                MetadataText("已分析 ${topic.knownWorks} · 未分析 ${topic.unknownWorks}")
                Text(topic.counts.joinToString(" · ") { "${it.category.label} ${it.works}" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (summary.byDate.isNotEmpty()) {
            var showDates by remember(report.generatedAt) { mutableStateOf(false) }
            PojianTextButton(onClick = { showDates = !showDates }) { Text("日期分布") }
            if (showDates) summary.byDate.forEach { day ->
                Text(day.date, style = MaterialTheme.typography.titleSmall)
                MetadataText("已分析 ${day.knownWorks} · 未分析 ${day.unknownWorks}")
                Text(day.counts.joinToString(" · ") { "${it.category.label} ${it.works}" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
