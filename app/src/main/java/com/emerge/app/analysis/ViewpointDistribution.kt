package com.emerge.app.analysis

import java.util.Locale

/** Presentation groups only: a shared subject never makes two claims equivalent. */
data class ViewpointTopic(val key: String, val title: String, val groups: List<ViewpointShare>) {
    val workCount: Int get() = groups.flatMap { it.items }.distinctBy { it.workIdentity }.size
    val total: Int get() = groups.firstOrNull()?.total ?: 0
    val percent: Double get() = if (total == 0) 0.0 else workCount * 100.0 / total
    val structured: Boolean get() = groups.any { it.analysisSource == "structured_model" }
}

enum class ViewpointFilter(val label: String) {
    ALL("全部状态"), MODEL("云端分析"), LOCAL("本地识别"), UNCERTAIN("待确认"), UNKNOWN("未识别")
}

/** Topic statistics retain the full filtered scope; only the displayed rows are sliced. */
data class ViewpointPageEntry(val topic: ViewpointTopic, val share: ViewpointShare)
data class ViewpointTopicPage(val topic: ViewpointTopic, val groups: List<ViewpointShare>)
data class ViewpointPage(val index: Int, val count: Int, val topics: List<ViewpointTopicPage>)

object ViewpointDistribution {
    const val PAGE_SIZE = 8

    fun entries(topics: List<ViewpointTopic>): List<ViewpointPageEntry> = topics.flatMap { topic ->
        if (topic.structured) topic.groups.sortedWith(compareBy<ViewpointShare> {
            it.items.minOfOrNull { item -> item.record.timestamp } ?: Long.MAX_VALUE
        }.thenBy { it.key }).map { ViewpointPageEntry(topic, it) }
        else topic.groups.flatMap { share ->
            // Local topics display source rows inline. Bound those rows too, even when
            // hundreds of works share one local label such as "未识别".
            ViewpointTimeline.details(share.items).map { item ->
                ViewpointPageEntry(topic, share.copy(items = listOf(item)))
            }
        }
    }

    fun page(entries: List<ViewpointPageEntry>, requestedIndex: Int): ViewpointPage {
        val count = ((entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val index = requestedIndex.coerceIn(0, count - 1)
        val start = index * PAGE_SIZE
        val topics = entries.subList(start, minOf(start + PAGE_SIZE, entries.size)).groupBy { it.topic.key }.values.map { rows ->
            ViewpointTopicPage(rows.first().topic, rows.groupBy { it.share.key }.values.map { group ->
                group.first().share.copy(items = group.flatMap { it.share.items })
            })
        }
        return ViewpointPage(index, count, topics)
    }

    fun topics(groups: List<ViewpointShare>): List<ViewpointTopic> = groups.groupBy { share ->
        val claim = share.items.firstOrNull()?.claims?.firstOrNull()
        when {
            share.analysisSource == "structured_model" && claim != null -> {
                val subject = claim.subject.trim()
                val topic = claim.topic.trim()
                when {
                    subject.isNotEmpty() -> "subject:${normalize(subject)}"
                    topic.isNotEmpty() -> "topic:${normalize(topic)}"
                    else -> "claim:${share.key}"
                }
            }
            else -> "group:${share.key}"
        }
    }.map { (key, shares) ->
        val claim = shares.first().items.firstOrNull()?.claims?.firstOrNull()
        val title = if (shares.first().analysisSource == "structured_model") {
            claim?.subject?.trim()?.takeIf { it.isNotEmpty() }
                ?: claim?.topic?.trim()?.takeIf { it.isNotEmpty() } ?: "观点待确认"
        } else shares.first().label
        ViewpointTopic(key, title, shares)
    }.sortedWith(compareBy<ViewpointTopic> { topic ->
        topic.groups.flatMap { it.items }.minOfOrNull { it.record.timestamp } ?: Long.MAX_VALUE
    }.thenBy { it.title })

    fun filter(topics: List<ViewpointTopic>, topicKey: String? = null,
        state: ViewpointFilter = ViewpointFilter.ALL, query: String = ""): List<ViewpointTopic> {
        val needle = normalize(query)
        return topics.filter { topicKey == null || it.key == topicKey }.mapNotNull { topic ->
            val groups = topic.groups.mapNotNull { share ->
                val items = share.items.filter { item ->
                    when (state) {
                        ViewpointFilter.ALL -> true
                        ViewpointFilter.MODEL -> item.analysisSource == "structured_model"
                        ViewpointFilter.LOCAL -> item.analysisSource == "local_rules"
                        ViewpointFilter.UNCERTAIN -> item.claims.any { it.analysisSource == "structured_model" && !it.confirmed }
                        ViewpointFilter.UNKNOWN -> share.label == "未识别"
                    }
                }
                val claims = items.flatMap { it.claims }
                val fields = listOf(topic.title, share.label) + claims.flatMap {
                    listOf(it.topic, it.subject, it.dimension, it.stance, it.conditions, it.quote, it.evidence)
                } + items.mapNotNull { it.quote }
                share.copy(items = items).takeIf { items.isNotEmpty() &&
                    (needle.isEmpty() || fields.any { field -> normalize(field).contains(needle) }) }
            }
            groups.takeIf { it.isNotEmpty() }?.let { topic.copy(groups = it) }
        }
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
