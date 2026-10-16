package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Observed exposure, never an estimate of recommendation intent or the user's beliefs. */
data class ExposureDistribution(val contacts: Int, val days: Int, val classified: Int,
    val shares: Map<String, Double>, val apps: Map<String, Int>) {
    val coverage: Double get() = if (contacts == 0) 0.0 else classified.toDouble() / contacts
    val sufficient: Boolean get() = classified >= 30 && coverage >= .6
    val diversity: Double get() = 1.0 - shares.values.sumOf { it * it }
}

data class ViewpointExposure(val key: String, val topic: String, val subject: String, val dimension: String,
    val stance: String, val distribution: ExposureDistribution, val share: Double,
    val previousShare: Double?, val diversityChange: Double?, val alignedApps: Int,
    val concentrated: Boolean, val latest: ClaimObservation, val eventIds: List<Long>,
    val sufficient: Boolean = distribution.sufficient,
    val longitudinal: LongitudinalEvidenceAssessment = LongitudinalEvidenceAssessment()) {
    val shareChange: Double? get() = previousShare?.let { share - it }
    val narrowing: Boolean get() = concentrated && (shareChange ?: 0.0) >= .15 && (diversityChange ?: 0.0) <= -.1
    val title: String get() = when {
        !sufficient -> "观察不足"
        narrowing -> "同向观点占比上升"
        concentrated && alignedApps >= 2 -> "跨应用同向集中"
        concentrated && (previousShare ?: 0.0) >= .75 -> "同向观点持续集中"
        concentrated -> "同向观点高度集中"
        else -> "暂未发现明显集中线索"
    }
}

data class ExposureAssessment(val start: Long, val end: Long, val topics: ExposureDistribution,
    val leadingTopic: String?, val topicShareChange: Double?, val viewpoints: List<ViewpointExposure>,
    val claims: List<ClaimObservation>) {
    val primary: ViewpointExposure? get() = viewpoints.filter { topics.sufficient && it.sufficient }
        .sortedWith(compareByDescending<ViewpointExposure> { it.concentrated }
            .thenByDescending { it.distribution.contacts }.thenBy { it.key }).firstOrNull()
    val signals: List<ViewpointExposure> get() = viewpoints.filter { it.concentrated && it.longitudinal.eligible }
    fun findings(): List<ObservationFinding> = viewpoints.map { view ->
        val change = view.shareChange?.let { "；较前7天${signedPoints(it)}个百分点" }.orEmpty()
        val diversity = view.diversityChange?.let {
            "；观点多样性变化${String.format(Locale.CHINA, "%+.2f", it)}"
        }.orEmpty()
        ObservationFinding(view.topic, view.title,
            "近7天，${view.subject} · ${view.dimension}：${view.distribution.contacts}次接触，覆盖${view.distribution.days}天；" +
                "已识别立场${view.distribution.classified}次，主要立场“${view.stance}”占${percent(view.share)}%$change$diversity；" +
                "同向集中的应用${view.alignedApps}个。仅反映已采集内容，不证明推荐算法的因果影响或你的认同。",
            view.eventIds)
    }.ifEmpty { listOf(ObservationFinding("全部", "观察不足", "近7天暂无足够的可比立场记录。", emptyList())) }
}

private fun percent(value: Double) = String.format(Locale.CHINA, "%.1f", value * 100)
private fun signedPoints(value: Double) = String.format(Locale.CHINA, "%+.1f", value * 100)

/** Sample size gates concentration; elapsed days are descriptive and only windows gate trends. */
object ExposureAnalyzer {
    private data class Contact(val key: String, val workKey: String, val at: Long, val packageName: String,
        val claims: MutableList<ClaimObservation> = mutableListOf(), val topics: MutableSet<String> = linkedSetOf())
    private data class Vote(val contact: Contact, val values: Set<String>)
    private data class Scope(val topic: String, val subject: String, val dimension: String, val conditions: String)
    private fun field(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun analyze(events: List<BrowseEvent>, claims: List<ClaimObservation>, now: Long,
        zone: ZoneId, topicByEvent: Map<Long, String> = emptyMap(),
        checkCancellation: () -> Unit = {}): ExposureAssessment {
        val end = Instant.ofEpochMilli(now).atZone(zone)
        val start = end.minusDays(7).toInstant().toEpochMilli()
        val previousStart = end.minusDays(14).toInstant().toEpochMilli()
        fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val eventsById = events.associateBy { it.id }
        val screens = events.filter { it.sourceType == "accessibility" && it.timestamp <= now &&
            it.timestamp >= previousStart && it.visibleText.isNotBlank() &&
            !it.packageName.contains("launcher") && it.packageName != "com.android.systemui" }
            .sortedBy { it.timestamp }
        val resolved = BrowseWorkKey.resolve(screens)
        val eventKeys = mutableMapOf<Long, String>()
        val contacts = linkedMapOf<String, Contact>()
        screens.forEachIndexed { index, event ->
            checkCancellation()
            // Reuse capture's work identity, not a time bucket:
            // scrolling through many distinct works in one sitting must still count those works.
            val key = "${date(event.timestamp)}|${resolved[index]}"
            eventKeys[event.id] = key
            contacts.getOrPut(key) { Contact(key, resolved[index], event.timestamp, event.packageName) }
        }
        // Multimodal samples inherit an explicitly bound work; an audio session is not a platform work.
        events.filter { it.sourceType in setOf("screen_ocr", "visual_model", "audio_playback") }.forEach { event ->
            val parent = event.parentEventId?.let(eventsById::get)
            if (parent != null && parent.sourceType == "accessibility" && parent.packageName == event.packageName &&
                parent.workKey == event.workKey) eventKeys[parent.id]?.let { eventKeys[event.id] = it }
        }
        claims.filter { it.timestamp in previousStart..now }.forEach { claim ->
            eventKeys[claim.eventId]?.let { contacts[it]?.claims?.add(claim) }
        }
        topicByEvent.forEach { (id, topic) ->
            if (topic.isNotBlank() && topic != "未分类") eventKeys[id]?.let { contacts[it]?.topics?.add(field(topic)) }
        }
        val current = contacts.values.filter { it.at in start..now }
        // A snapshot/session extended across the boundary cannot supply a clean earlier baseline.
        val crossesBoundary = eventKeys.entries.mapNotNull { (id, key) ->
            val event = eventsById[id] ?: return@mapNotNull null
            if ((contacts[key]?.at ?: now) < start && maxOf(event.timestamp, event.endTimestamp) >= start) key else null
        }.toSet()
        val previous = contacts.values.filter { it.at >= previousStart && it.at < start && it.key !in crossesBoundary }
        fun distribution(votes: List<Vote>): ExposureDistribution {
            val known = votes.filter { it.values.isNotEmpty() }
            val totals = linkedMapOf<String, Double>()
            known.forEach { vote -> vote.values.forEach { value ->
                totals[value] = (totals[value] ?: 0.0) + 1.0 / vote.values.size
            } }
            return ExposureDistribution(votes.size, votes.map { date(it.contact.at) }.distinct().size,
                known.size, totals.mapValues { it.value / known.size },
                votes.groupingBy { it.contact.packageName }.eachCount())
        }
        fun topicVotes(rows: List<Contact>) = rows.map { contact -> Vote(contact, contact.topics + contact.claims
            .filter { !it.uncertainAttribution && it.topic.isNotBlank() && it.topic != "未分类" }
            .map { field(it.topic) }.toSet()) }
        // Changes in capture coverage or application mix must not be reported as recommendation drift.
        fun comparable(a: ExposureDistribution, b: ExposureDistribution): Boolean = a.sufficient && b.sufficient &&
            kotlin.math.abs(a.coverage - b.coverage) <= .2 && a.apps.keys == b.apps.keys && a.apps.keys.all {
                kotlin.math.abs(a.apps.getValue(it).toDouble() / a.contacts - b.apps.getValue(it).toDouble() / b.contacts) <= .2
            }
        val topics = distribution(topicVotes(current))
        val oldTopics = distribution(topicVotes(previous))
        val leadingTopic = topics.shares.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
            .thenBy { it.key }).firstOrNull()?.key
        val topicChange = if (leadingTopic != null && comparable(topics, oldTopics))
            topics.shares.getValue(leadingTopic) - (oldTopics.shares[leadingTopic] ?: 0.0) else null
        fun scope(claim: ClaimObservation): Scope? = if (claim.analysisSource != "structured_model" ||
            listOf(claim.topic, claim.subject, claim.dimension).any { it.isBlank() }) null else Scope(
            field(claim.topic), field(claim.subject), field(claim.dimension),
            SemanticFieldKeys.conditions(claim.conditions, claim.timestamp, zone))
        // Keyword-only topic labels must not make a small analyzed subset look representative.
        fun classifiedContacts(rows: List<Contact>) = distribution(rows.map { contact ->
            Vote(contact, if (contact.claims.any {
                scope(it) != null && !it.uncertainAttribution && it.stance.isNotBlank()
            }) setOf("classified") else emptySet())
        })
        val classified = classifiedContacts(current)
        val oldClassified = classifiedContacts(previous)
        fun indexedVotes(rows: List<Contact>): Map<Scope, List<Vote>> {
            val result = linkedMapOf<Scope, MutableList<Vote>>()
            rows.forEach { contact ->
                checkCancellation()
                contact.claims.mapNotNull { claim -> scope(claim)?.let { it to claim } }.groupBy({ it.first }, { it.second })
                    .forEach { (key, members) ->
                        result.getOrPut(key) { mutableListOf() } += Vote(contact,
                            members.filter { !it.uncertainAttribution && it.stance.isNotBlank() }
                                .map { SemanticFieldKeys.stance(it.stance, it.subject, it.dimension) }.toSet())
                    }
            }
            return result
        }
        val currentScopes = indexedVotes(current)
        val previousScopes = indexedVotes(previous)
        val workByEvent = eventKeys.mapValues { (_, key) -> contacts.getValue(key).workKey }
        val viewpoints = currentScopes.mapNotNull { (key, votes) ->
            checkCancellation()
            val dist = distribution(votes)
            val dominant = dist.shares.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { it.key }).firstOrNull() ?: return@mapNotNull null
            val old = distribution(previousScopes[key].orEmpty())
            val historyComparable = comparable(topics, oldTopics) && comparable(classified, oldClassified) && comparable(dist, old)
            val alignedApps = votes.groupBy { it.contact.packageName }.values.count { app ->
                val perApp = distribution(app)
                perApp.classified >= 10 && perApp.coverage >= .6 &&
                    (perApp.shares[dominant.key] ?: 0.0) >= .75
            }
            val members = votes.flatMap { it.contact.claims }.filter { scope(it) == key && !it.uncertainAttribution &&
                SemanticFieldKeys.stance(it.stance, it.subject, it.dimension) == dominant.key }
            val latest = members.maxByOrNull { it.timestamp } ?: return@mapNotNull null
            val identity = listOf(key.topic, key.subject, key.dimension, key.conditions).joinToString("") { "${it.length}:$it" }
            val longitudinal = LongitudinalEvidence.assess(
                votes.flatMap { it.contact.claims }.filter { scope(it) == key },
                claims.filter { scope(it) == key }, workByEvent, dominant.key, zone, checkCancellation)
            ViewpointExposure(identity, latest.topic, latest.subject, latest.dimension, latest.stance, dist, dominant.value,
                if (historyComparable) old.shares[dominant.key] ?: 0.0 else null,
                if (historyComparable) dist.diversity - old.diversity else null, alignedApps,
                classified.sufficient && topics.sufficient && dist.sufficient && dominant.value >= .75,
                latest, members.sortedByDescending { it.timestamp }.map { it.eventId }.distinct().take(8),
                classified.sufficient && topics.sufficient && dist.sufficient, longitudinal)
        }.sortedWith(compareByDescending<ViewpointExposure> { it.concentrated }
            .thenByDescending { it.distribution.contacts }.thenBy { it.key })
        return ExposureAssessment(start, now, topics, leadingTopic, topicChange, viewpoints,
            current.flatMap { it.claims }.distinctBy { it.eventId to it.quote })
    }
}
