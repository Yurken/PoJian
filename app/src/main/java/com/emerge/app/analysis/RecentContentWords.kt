package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.text.Normalizer
import java.time.ZoneId
import java.util.Locale

data class ContentWord(val text: String, val recordCount: Int)

data class RecentContentPoint(val key: String, val quote: String, val records: List<BrowseEvent>)
data class RecentContentGroup(val key: String, val title: String, val points: List<RecentContentPoint>) {
    val records: List<BrowseEvent> = points.flatMap { it.records }.distinctBy { it.id }
}

/** Related claims are grouped by their concrete subject; original snapshots remain drill-down data. */
fun recentContentGroups(word: String, events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault(), category: String? = null): List<RecentContentGroup> {
    val key = conceptKey(word)
    val latest = latestSemantics(semantics)
    data class Entry(val title: String, val claim: ViewpointClaim, val record: BrowseEvent)
    val entries = recentContentWordRecords(word, events, period, now, semantics, zone, category).flatMap { event ->
        latest[event.id]?.takeIf { it.validFor(event) }?.claims.orEmpty().filter { claim ->
            (category == null || cleanConcept(claim.topic)?.let(::conceptKey) == conceptKey(category)) &&
                listOf(claim.topic, claim.subject).mapNotNull(::cleanConcept).any { conceptKey(it) == key }
        }.map { claim ->
            val title = listOf(claim.subject, claim.topic, claim.dimension).mapNotNull(::cleanConcept)
                .firstOrNull { conceptKey(it) != key } ?: word
            Entry(title, ViewpointClaim(claim.quote, claim.topic, claim.subject, claim.dimension, claim.stance,
                claim.conditions, claim.uncertain, "structured_model", event, zone.id), event)
        }
    }
    return entries.groupBy { conceptKey(it.title) }.map { (groupKey, related) ->
        val points = related.groupBy { it.claim.key }.map { (pointKey, matches) ->
            RecentContentPoint(pointKey, matches.first().claim.quote, matches.map { it.record }.distinctBy { it.id })
        }.sortedWith(compareByDescending<RecentContentPoint> { it.records.size }
            .thenByDescending { it.records.maxOf { record -> record.timestamp } }.thenBy { it.key })
        RecentContentGroup(groupKey, related.first().title, points)
    }.sortedWith(compareByDescending<RecentContentGroup> { it.records.size }
        .thenByDescending { it.records.maxOf { record -> record.timestamp } }.thenBy { it.title })
}

/** Model concepts backed by distinct retained snapshots; the UI controls collapsed capacity. */
fun recentContentWords(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault(), category: String? = null): List<ContentWord> {
    val selected = mutableListOf<ConceptSources>()
    for (candidate in recentConceptSources(events, period, now, semantics, zone, category)) {
        // A broad topic adds no coverage when its more specific label points to exactly
        // the same sources. Do not guess synonyms or combine unrelated source sets.
        if (selected.any { existing ->
            existing.records.keys == candidate.records.keys &&
                minOf(existing.key.length, candidate.key.length) >= 2 &&
                (containsConcept(existing.key, candidate.key) || containsConcept(candidate.key, existing.key))
        }) continue
        selected += candidate
    }
    return selected.map { ContentWord(it.text, it.records.size) }
}

/** Only retained, valid model topics can become category filters. */
fun recentContentCategories(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault()): List<String> {
    val window = ObservationWindow.forPeriod(period, now, zone)
    val latest = latestSemantics(semantics)
    return events.asSequence().filter(window::contains).distinctBy { it.id }
        .sortedByDescending { it.timestamp }.flatMap { event ->
            latest[event.id]?.takeIf { it.validFor(event) }?.claims.orEmpty().asSequence()
        }.mapNotNull { cleanConcept(it.topic) }.distinctBy(::conceptKey).toList()
}

/** Trace model concepts using the same input snapshot and time window as the cloud counts. */
fun recentContentWordRecords(word: String, events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId = ZoneId.systemDefault(), category: String? = null): List<BrowseEvent> =
    recentConceptSources(events, period, now, semantics, zone, category)
        .firstOrNull { it.key == conceptKey(word) }?.records?.values.orEmpty()
        .sortedWith(compareByDescending<BrowseEvent> { it.timestamp }.thenByDescending { it.id })

private data class ConceptSources(val key: String, val text: String,
    val records: MutableMap<List<String>, BrowseEvent> = linkedMapOf())

private fun recentConceptSources(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId, category: String?): List<ConceptSources> {
    val groups = linkedMapOf<String, ConceptSources>()
    recentConceptRecords(events, period, now, semantics, zone, category).forEach { (event, concepts) ->
        // Only identical full snapshots from the same source are duplicates. Different
        // claims, applications and growing snapshots retain their own source records.
        val source = listOf(event.packageName, event.sourceType, BrowseWorkKey.normalize(event.visibleText))
        concepts.forEach { text ->
            val key = conceptKey(text)
            groups.getOrPut(key) { ConceptSources(key, text) }.records.putIfAbsent(source, event)
        }
    }
    return groups.values.sortedWith(compareByDescending<ConceptSources> { it.records.size }
        .thenByDescending { it.records.values.maxOf { event -> event.timestamp } }
        .thenByDescending { it.key.length }.thenBy { it.text })
}

private fun recentConceptRecords(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
    semantics: List<EventSemantics>, zone: ZoneId, category: String?): List<Pair<BrowseEvent, List<String>>> {
    val window = ObservationWindow.forPeriod(period, now, zone)
    val latest = latestSemantics(semantics)
    return events.asSequence().filter(window::contains).distinctBy { it.id }
        .sortedWith(compareByDescending<BrowseEvent> { it.timestamp }.thenByDescending { it.id }).mapNotNull { event ->
            val result = latest[event.id]?.takeIf { it.validFor(event) } ?: return@mapNotNull null
            val concepts = result.claims.asSequence()
                .filter { category == null || cleanConcept(it.topic)?.let(::conceptKey) == conceptKey(category) }
                .flatMap { sequenceOf(it.topic, it.subject) }
                .mapNotNull(::cleanConcept).distinctBy(::conceptKey).toList()
            event to concepts
        }.toList()
}

private fun latestSemantics(semantics: List<EventSemantics>): Map<Long, EventSemantics> =
    semantics.withIndex().groupBy { it.value.eventId }.mapValues { (_, versions) ->
        versions.maxWith(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }.thenBy { it.index }).value
    }

private fun cleanConcept(value: String): String? {
    val text = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        .trim('"', '\'', '“', '”', '「', '」', '《', '》').trim()
        .replace(Regex("[\\p{Z}\\s]+"), " ")
    if (text.codePointCount(0, text.length) !in 2..24 || text.none(Char::isLetter) ||
        value.any { it in "\n\r。！？!?；;" } || conceptKey(text) in missingConcepts) return null
    return text
}

private fun conceptKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).trim().replace(Regex("[\\p{Z}\\s]+"), " ")
    .replace(Regex("(?<=[\\u3400-\\u9FFF]) (?=[\\u3400-\\u9FFF])"), "")

private fun containsConcept(specific: String, general: String): Boolean =
    if (general.any { it in '\u3400'..'\u9FFF' }) specific.contains(general)
    else Regex("(?<![\\p{L}\\p{N}])${Regex.escape(general)}(?![\\p{L}\\p{N}])").containsMatchIn(specific)

private val missingConcepts = setOf("未知", "未分类", "未识别", "不明确", "无", "暂无", "其他", "其它",
    "内容", "相关内容", "其他内容", "视频", "短视频", "文章", "文本", "信息", "相关信息", "话题", "其他话题",
    "观点", "个人观点", "讨论", "问题", "事件", "事情", "对象", "用户", "作者", "博主", "当事人", "相关人员",
    "大家", "我们", "你们", "他们", "她们", "分享", "日常分享", "点赞", "评论", "收藏", "转发", "关注",
    "null", "none", "unknown", "n/a", "undefined")
