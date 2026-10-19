package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ViewpointClaim(val quote: String, val topic: String = "", val subject: String = "",
    val dimension: String = "", val stance: String = "", val conditions: String = "",
    val uncertain: Boolean = false, val analysisSource: String = "local_rules",
    val record: BrowseEvent? = null, val zoneId: String = ZoneId.systemDefault().id,
    val evidence: String = "", val presentation: ClaimPresentation? = null,
    val inputKind: String = "", val analyzedAt: Long = 0) {
    private companion object {
        val whitespace = Regex("\\s+")
        val conditionalQuote = Regex("如果|只有|除非|条件|反对|不支持|不应该|不需要")
    }
    private fun normalized(value: String) = value.trim().replace(whitespace, " ").lowercase()
    val confirmed: Boolean get() = !uncertain && topic.isNotBlank() && subject.isNotBlank() && dimension.isNotBlank() && stance.isNotBlank()
    val key: String get() = if (analysisSource == "structured_model")
        listOf(analysisSource, topic, subject, dimension,
            record?.let { SemanticFieldKeys.conditions(conditions, it.timestamp, ZoneId.of(zoneId)) } ?: normalized(conditions),
            SemanticFieldKeys.stance(stance, subject, dimension),
            if (confirmed) "" else quote).joinToString("|") { normalized(it) }
        else "local_rules|" + if (ViewpointTimeline.label(quote) == "未识别" ||
            conditionalQuote.containsMatchIn(quote)) normalized(quote) else ViewpointTimeline.label(quote)
    val label: String get() = if (analysisSource == "structured_model") {
        listOf(topic.takeIf(String::isNotBlank), subject.takeIf(String::isNotBlank),
            dimension.takeIf(String::isNotBlank), conditions.takeIf(String::isNotBlank),
            stance.takeIf(String::isNotBlank), if (confirmed) null else "待确认")
            .filterNotNull().joinToString(" · ").ifBlank { "观点待确认" }
    } else ViewpointTimeline.label(quote)
}
data class ViewpointItem(val record: BrowseEvent, val label: String?, val quote: String?, val emotional: Boolean,
    val claims: List<ViewpointClaim> = emptyList(), val analysisSource: String = "local_rules",
    val records: List<BrowseEvent> = listOf(record), val workIdentity: String = BrowseWorkKey.of(record)) {
    fun durationMillis(window: ObservationWindow): Long? {
        val selected = records.filter { it.sourceType == "accessibility" && window.contains(it) }
        return if (selected.isEmpty() || selected.any { !window.durationKnown(it) }) null
        else window.unionDuration(selected)
    }
}
data class ViewpointBucket(val label: String, val start: Long, val end: Long,
    val items: List<ViewpointItem>, val score: Int?, val repeated: Int, val emotional: Int)
data class ViewpointShare(val label: String, val items: List<ViewpointItem>, val total: Int,
    val key: String = label, val analysisSource: String = "local_rules") {
    val percent: Double get() = if (total == 0) 0.0 else items.size * 100.0 / total
}

/** Local, auditable exposure signals; never a measurement of the user's mental state. */
object ViewpointTimeline {
    private val emotion = Regex("焦虑|恐慌|恐惧|绝望|完蛋|没有未来|毁掉")
    private val negatedEmotion = Regex("(?:不必|不用|无需|别|不要)[^，。；]{0,6}(?:焦虑|恐慌|恐惧|绝望)")
    private val predicate = Regex("应该|必须|需要|不能|独立|自主|焦虑|认为|觉得|导致|越来越|意味着|会|难|值得|自由")
    private fun workKey(item: ViewpointItem) = item.workIdentity
    private fun records(items: List<ViewpointItem>) = items.flatMap { it.records }
        .distinctBy { listOf(it.packageName, it.sourceType, it.id, it.timestamp) }

    /** Works are the top-level list. Shared quotes never collapse distinct works. */
    fun details(items: List<ViewpointItem>): List<ViewpointItem> = works(items)
        .sortedWith(compareBy<ViewpointItem> { it.records.filter { row -> row.sourceType == "accessibility" }
            .minOfOrNull { row -> row.timestamp } ?: it.record.timestamp }.thenBy { it.workIdentity })
    private fun claims(item: ViewpointItem): List<ViewpointClaim> = item.claims.ifEmpty {
        if (item.analysisSource == "structured_model") emptyList()
        else item.quote?.let { listOf(ViewpointClaim(it, record = item.record)) }.orEmpty()
    }
    /** Union all observed claims of a work; a completed model result supersedes phrase candidates. */
    private fun works(items: List<ViewpointItem>): List<ViewpointItem> = items.groupBy(::workKey).values.map { visits ->
        val model = visits.filter { it.analysisSource == "structured_model" }
        val used = model.ifEmpty { visits }
        val representative = used.firstOrNull { claims(it).isNotEmpty() } ?: used.first()
        val all = used.flatMap(::claims).groupBy { it.key }.values.map { versions ->
            versions.maxWith(compareBy<ViewpointClaim> { it.analyzedAt }.thenBy { it.record?.timestamp ?: 0 })
        }
        representative.copy(label = all.firstOrNull()?.label, quote = all.firstOrNull()?.quote,
            emotional = used.any { it.emotional }, claims = all, records = records(visits))
    }
    fun groups(items: List<ViewpointItem>): List<ViewpointShare> {
        val works = works(items)
        val grouped = linkedMapOf<String, MutableList<ViewpointItem>>()
        val descriptions = linkedMapOf<String, Pair<String, String>>()
        works.forEach { item ->
            val candidates = claims(item)
            if (candidates.isEmpty()) {
                val key = "unknown"
                grouped.getOrPut(key) { mutableListOf() } += item
                descriptions[key] = "未识别" to "unknown"
            } else candidates.forEach { claim ->
                grouped.getOrPut(claim.key) { mutableListOf() } += item.copy(record = claim.record ?: item.record,
                    label = claim.label, quote = claim.quote, claims = listOf(claim))
                descriptions[claim.key] = claim.label to claim.analysisSource
            }
        }
        return grouped.map { (key, entries) ->
            val (label, source) = descriptions.getValue(key)
            ViewpointShare(label, entries.distinctBy(::workKey), works.size, key, source)
        }.sortedWith(compareByDescending<ViewpointShare> { it.items.size }.thenBy { it.label })
    }
    fun label(quote: String): String = ViewpointLabeler.label(quote)

    internal fun localClaims(event: BrowseEvent, observations: List<ClaimObservation>, zone: ZoneId): List<ViewpointClaim> {
        val reportClaims = observations.filter {
            it.analysisSource == "local_rules" && event.visibleText.contains(it.quote)
        }.map { ViewpointClaim(it.quote, it.topic, it.subject, it.dimension, it.stance, it.conditions,
            it.uncertainAttribution, it.analysisSource, event, zone.id) }
        val fallback = event.contentText.lines().map {
            it.trim().replace(Regex("(?:\\.\\.\\.|…)?\\s*展开[>〉]?\\s*$"), "").trim()
        }.firstOrNull { it.length in 10..300 && predicate.containsMatchIn(it) &&
            !Regex("按钮|来自|回复|评论|图片[,，]").containsMatchIn(it) }
        return reportClaims.ifEmpty { fallback?.let { listOf(ViewpointClaim(it, record = event, zoneId = zone.id)) }.orEmpty() }
    }

    fun build(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long, zone: ZoneId,
        report: CognitiveReport? = null, semantics: List<EventSemantics> = emptyList()): List<ViewpointBucket> {
        val window = ObservationWindow.forPeriod(period, now, zone)
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = if (period.dailyBuckets) day.minusDays(period.calendarDays - 1L).atStartOfDay(zone)
            else Instant.ofEpochMilli(window.start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val count = when {
            period.dailyBuckets -> period.calendarDays
            period == StatisticsPeriod.DAY -> 24
            else -> ChronoUnit.HOURS.between(first, Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS)).toInt() + 1
        }
        val selected = events.filter { it.sourceType == "accessibility" && window.contains(it) }
        val materials = events.filter { it.parentEventId != null && window.contains(it) }
            .groupBy { it.parentEventId }
        val workKeys = selected.zip(BrowseWorkKey.resolve(selected)).toMap()
        val observations = report?.takeIf { it.period == period.label }?.claims?.groupBy { it.eventId }.orEmpty()
        val modelResults = semantics.groupBy { it.eventId }.mapValues { (_, values) ->
            values.withIndex().maxWithOrNull(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }
                .thenBy { it.index })!!.value
        }
        val candidates = selected.associateWith { event ->
            val model = modelResults[event.id]?.takeIf { it.validFor(event) }
            val modelClaims = model?.claims?.map { ViewpointClaim(it.quote, it.topic, it.subject,
                it.dimension, it.stance, it.conditions, it.uncertain, "structured_model", event, zone.id, it.evidence,
                it.presentation, model.inputKind, model.analyzedAt) }
            val attached = materials[event.id].orEmpty().filter { it.packageName == event.packageName && it.workKey == event.workKey }
            val attachedClaims = attached.flatMap { material ->
                val result = modelResults[material.id]?.takeIf { it.validFor(material) }
                result?.claims.orEmpty().map { ViewpointClaim(it.quote,it.topic,it.subject,it.dimension,it.stance,it.conditions,
                    it.uncertain,"structured_model",material,zone.id,it.evidence,it.presentation,result!!.inputKind,result.analyzedAt) } }
            // A report may be stale; only the full input snapshot can authorize a model result here.
            val chosen = ((modelClaims ?: localClaims(event, observations[event.id].orEmpty(), zone)) + attachedClaims).distinctBy { it.key }
            val source = if (model != null || chosen.any { it.analysisSource == "structured_model" }) "structured_model" else "local_rules"
            ViewpointItem(event, chosen.firstOrNull()?.label, chosen.firstOrNull()?.quote, chosen.any {
                emotion.containsMatchIn(it.quote) && !negatedEmotion.containsMatchIn(it.quote)
            }, chosen, source, records = listOf(event) + attached, workIdentity = workKeys.getValue(event))
        }
        val slices = selected.associateWith(window::slices)
        return List(count) { index ->
            val localStart = if (period.dailyBuckets) first.plusDays(index.toLong()) else first.plusHours(index.toLong())
            val localEnd = if (period.dailyBuckets) localStart.plusDays(1) else localStart.plusHours(1)
            val start = maxOf(window.start, localStart.toInstant().toEpochMilli())
            val end = minOf(now, localEnd.toInstant().toEpochMilli()).coerceAtLeast(start)
            val items = if (start > end) emptyList() else works(selected.filter { event -> slices.getValue(event).any {
                it.start >= start && it.start < end || it.end > start && it.start < end ||
                    it.start == end && end == now && now < localEnd.toInstant().toEpochMilli() && it.start >= start
            } }.map { candidates.getValue(it) })
            val recognized = items.filter { item -> claims(item).any {
                if (it.analysisSource == "structured_model") it.confirmed else it.label != "未识别"
            } }
            val repeated = groups(recognized).filter { it.label != "未识别" && !it.label.endsWith("待确认") }
                .sumOf { (it.items.size - 1).coerceAtLeast(0) }.coerceAtMost((recognized.size - 1).coerceAtLeast(0))
            val emotional = recognized.count { it.emotional }
            val score = if (recognized.size < 3) null else
                ((60.0 * repeated + 40.0 * emotional) / recognized.size).toInt().coerceIn(0, 100)
            val caption = if (period.dailyBuckets) {
                if (index == count - 1) "今天" else "${localStart.monthValue}/${localStart.dayOfMonth}"
            } else localStart.hour.toString().padStart(2, '0')
            ViewpointBucket(caption, start, end, items, score, repeated, emotional)
        }
    }
}
