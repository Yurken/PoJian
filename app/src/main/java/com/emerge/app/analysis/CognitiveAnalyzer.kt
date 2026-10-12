package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

enum class Increment(val label: String) {
    FIRST("首次出现"), NEW("依据有新增"), REPEATED("重复出现，依据没变"),
    UNKNOWN("出处或依据不足，暂无法判断"), CONDITIONS("时间或条件不同，单独保留")
}
enum class EvidenceType(val label: String) {
    FACT("事实/数据线索"), CONDITION("适用条件"), ADVICE("行动建议"),
    EXPLANATION("原因/解释"), OTHER("其他线索")
}
/** A direct, retained historical quotation, not a claim of independent source or truth. */
data class EvidenceHistoryMatch(val eventId: Long, val quote: String)
data class EvidenceFragment(val text: String, val type: EvidenceType, val isNew: Boolean = false,
    val historyMatches: List<EvidenceHistoryMatch> = emptyList(),
    val identity: EvidenceIdentity = EvidenceIdentityComparator.identity(text), val identityUnresolved: Boolean = false,
    val comparisonText: String = text, val strictComparison: Boolean = false, val relativeTimeScope: String = "")
data class ClaimObservation(val eventId: Long, val timestamp: Long, val packageName: String,
    val topic: String, val quote: String, val evidence: List<String>, val conditions: String,
    val uncertainAttribution: Boolean, val increment: Increment, val previousEventId: Long?,
    val durationMillis: Long? = null, val evidenceDetails: List<EvidenceFragment> = emptyList(),
    val subject: String = "",
    val comparedHistoryEventIds: List<Long> = emptyList(),
    val historyComparisonTruncated: Boolean = false,
    val dimension: String = "", val stance: String = "", val analysisSource: String = "local_rules")
data class ClaimGroup(val topic: String, val quote: String, val observations: List<ClaimObservation>)
data class TopicCount(val topic: String, val count: Int)
data class HourlyReview(val hour: Int, val recordCount: Int, val durationMillis: Long,
    val unknownDurationRecords: Int, val claimEventIds: List<Long>, val topics: List<TopicCount>)
data class TopicExposure(val topic: String, val recordCount: Int, val durationMillis: Long)
data class ContentSignal(val eventId: Long, val label: String, val quote: String)
data class ObservationFinding(val topic: String, val title: String, val explanation: String,
    val eventIds: List<Long>)
data class CognitiveReport(val generatedAt: Long, val start: Long, val end: Long, val period: String,
    val rawRecords: Int, val usableSnapshots: Int, val skippedRecords: Int, val mergedSnapshots: Int,
    val claims: List<ClaimObservation>, val groups: List<ClaimGroup>, val topics: List<TopicCount>,
    val findings: List<ObservationFinding>, val observedDays: Int, val sourceCounts: Map<String, Int>,
    val purpose: String, val hourlyClaims: List<Int>, val zone: String,
    val method: String = "本地识别与历史片段对照（未经模型训练，结果需核实）",
    val hourlyReviews: List<HourlyReview> = emptyList(), val coverageSessions: Int = 0,
    val observedDurationMillis: Long = 0, val unknownDurationRecords: Int = 0,
    val topicExposure: List<TopicExposure> = emptyList(), val contentSignals: List<ContentSignal> = emptyList(),
    val comparisonStart: Long? = null, val comparisonRecords: Int = 0,
    val workStatistics: com.emerge.app.ui.BrowseStatistics? = null,
    val structuredAnalyzedRecords: Int = 0, val localFallbackRecords: Int = 0,
    val rejectedSemanticsRecords: Int = 0, val pendingAnalysisRecords: Int = 0,
    val failedAnalysisRecords: Int = 0, val exposure: ExposureAssessment? = null)

class CognitiveAnalyzer {
    private companion object {
        const val HISTORY_LIMIT = 120
        const val MATCHES_PER_FRAGMENT = 3
    }
    private data class Candidate(val topic: String, val quote: String, val subject: String,
        val evidence: List<EvidenceFragment>, val conditions: String, val uncertain: Boolean,
        val dimension: String = "", val stance: String = "", val source: String = "local_rules")
    private data class HistoryKey(val source: String, val topic: String, val subject: String,
        val dimension: String, val stance: String)
    private val topics = linkedMapOf(
        "就业与职场" to listOf("就业", "求职", "招聘", "岗位", "裁员", "秋招", "春招", "面试", "职场", "薪资", "工资", "程序员"),
        "教育与升学" to listOf("考研", "保研", "升学", "院校", "高考", "大学", "专业", "留学", "学历", "学费"),
        "科技与产品" to listOf("人工智能", "大模型", "手机", "芯片", "算法", "软件", "电脑", "AI", "机器人"),
        "经济与消费" to listOf("经济", "消费", "房价", "房贷", "物价", "收入", "投资", "股市", "存款"),
        "健康与生活" to listOf("健康", "睡眠", "运动", "饮食", "减肥", "疾病", "医疗", "养生"),
        "环境与生态" to listOf("水质", "河段", "水体", "空气质量", "污染", "排放", "生态"),
        "社会与关系" to listOf("社会", "婚姻", "结婚", "生育", "恋爱", "家庭", "政策", "养老"),
        "娱乐与休闲" to listOf("搞笑", "舞蹈", "游戏", "音乐", "电影", "追剧", "综艺", "明星")
    )
    private val uiLabels = setOf("点赞", "评论", "收藏", "分享", "关注", "已关注", "推荐", "首页", "消息", "我", "我的", "返回", "搜索", "发布", "直播", "购物", "复制链接", "展开", "收起", "导航", "设置", "Play Store", "Gmail", "Photos", "Chrome", "YouTube", "Navigate up")
    private val predicate = Regex("认为|觉得|意味着|说明|导致|应该|不应|必须|需要|可能|不能|不可能|越来越|更难|很难|很差|不好|容易|值得|没有机会|增长|下降|减少|增加|提高|降低|改善|恶化|是|不是|会|不会|能|难|好|差")
    private val evidenceMarker = Regex("因为|由于|数据显示|根据|调查|报告|统计|例如|同比|原因|建议|可以|首先|其次|如果|针对|对于|仅在|前提|[0-9]+(?:\\.[0-9]+)?[%万亿年月日人个元]")
    private val conditionMarker = Regex("未来|明年|今年|当前|过去|去年|现在|[0-9]{4}年|北京|上海|深圳|广州|地区|应届|嵌入式|互联网|医疗|制造业|如果[^，。；\\n]{0,30}")
    private val attributionMarker = Regex("有人说|有人认为|听说|据说|网传|引用|反驳|所谓|并非|不代表|不意味着|但|然而|不是.*而是|[“”\"]")
    private val numericTokens = Regex("[0-9]+(?:\\.[0-9]+)?(?:[%万亿年月日人个元])?")
    private val emotionalExpression = Regex("焦虑|恐慌|恐惧|绝望|完蛋|没有未来|毁掉|再不[^，。；]{0,18}就|你还不[^，。；]{0,18}")

    fun analyze(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), purpose: String = "未填写",
        checkCancellation: () -> Unit = {}, semantics: List<EventSemantics> = emptyList(),
        analysisStatuses: Map<Long, String> = emptyMap()): CognitiveReport {
        val window = ObservationWindow.forPeriod(period, now, zone)
        // Retained earlier history is compared in browsing order, never in analysis completion order.
        val ordered = events.filter { it.timestamp <= now }.sortedWith(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id })
        val selected = ordered.filter { window.contains(it) }
        val observations = mutableListOf<Pair<BrowseEvent, ClaimObservation>>()
        // Per-analysis index: rebuilding after edits/deletion cannot retain stale evidence dependencies.
        val historyByClaim = mutableMapOf<HistoryKey, MutableList<ClaimObservation>>()
        val latestSemantics = semantics.groupBy { it.eventId }.mapValues { (_, versions) ->
            versions.withIndex().maxWithOrNull(compareBy<IndexedValue<EventSemantics>> { it.value.analyzedAt }
                .thenBy { it.index })!!.value
        }
        val usableEvents = mutableListOf<BrowseEvent>()
        val eventTopics = mutableMapOf<BrowseEvent, String>()
        val signals = mutableListOf<ContentSignal>()
        var previous: BrowseEvent? = null
        var skipped = 0
        var merged = 0
        var structuredAnalyzed = 0
        var localFallback = 0
        var rejectedSemantics = 0
        ordered.forEach { event ->
            checkCancellation()
            val within = window.contains(event)
            val supplied = latestSemantics[event.id]
            val structured = supplied?.takeIf { it.validFor(event) }
            val lines = clean(event)
            if (event.packageName.contains("launcher") || event.packageName == "com.android.systemui" ||
                lines.isEmpty() && structured == null) {
                previous = null
                if (within) skipped++
                return@forEach
            }
            val old = previous
            previous = event
            // Do not merge a first in-window snapshot into an unselected historical snapshot.
            if (structured == null && old != null && latestSemantics[old.id]?.validFor(old) != true &&
                window.contains(old) == within && old.sampleCount == 0 && event.sampleCount == 0 &&
                !old.sourceType.startsWith("audio_") && !event.sourceType.startsWith("audio_") &&
                old.packageName == event.packageName && event.timestamp - old.timestamp in 0..30_000 &&
                com.emerge.app.capture.ContentContinuity.same(old.visibleText, event.visibleText)) {
                if (within) merged++
                return@forEach
            }
            eventTopics[event] = structured?.claims?.firstOrNull { it.topic.isNotBlank() }?.topic?.trim()
                ?: detectTopic(lines.joinToString(" ")) ?: "未分类"
            if (within) {
                usableEvents += event
                if (structured != null) structuredAnalyzed++ else {
                    localFallback++
                    if (supplied != null) rejectedSemantics++
                }
                lines.filter { emotionalExpression.containsMatchIn(it) }.take(2).forEach {
                    signals += ContentSignal(event.id, "原文含焦虑或紧迫的表达", it)
                }
            }
            // A valid empty model result is an analyzed event, not an invitation to invent a fallback claim.
            val candidates = structured?.claims?.map { claim ->
                Candidate(claim.topic.trim().ifBlank { "未分类" }, claim.quote, claim.subject.trim(),
                    claim.evidence.takeIf { it.isNotEmpty() }?.let { evidenceFragments(it,
                        EvidenceIdentityComparator.identity(it, claim.evidenceActors, claim.evidenceOrigins), event.timestamp, zone) }.orEmpty(),
                    claim.conditions.trim(), claim.uncertain || claim.topic.isBlank() || claim.subject.isBlank() ||
                        claim.dimension.isBlank() || claim.stance.isBlank(), claim.dimension.trim(), claim.stance.trim(),
                    "structured_model")
            } ?: localCandidates(lines, event.timestamp, zone)
            candidates.forEach { candidate ->
                val (topic, quote, subject, evidence, conditions, uncertain, dimension, stance, source) = candidate
                val model = source == "structured_model"
                val historyKey = HistoryKey(source, fieldKey(topic), fieldKey(subject), fieldKey(dimension),
                    if (model) SemanticFieldKeys.stance(stance, subject, dimension) else polarity(quote))
                fun relatedCandidates(): Sequence<ClaimObservation> =
                    if (uncertain || subject.isBlank()) emptySequence()
                    else historyByClaim[historyKey].orEmpty().asReversed().asSequence().filter {
                        checkCancellation()
                        // A deterministic ID order is not evidence that simultaneous events preceded each other.
                        it.eventId != event.id && it.timestamp < event.timestamp && !it.uncertainAttribution &&
                            (model || similarity(comparisonPart(it.quote), comparisonPart(quote)) >= 0.65)
                    }
                // Filter scope before limiting recall; unrelated records cannot crowd out comparable history.
                fun sameConditions(previous: ClaimObservation) = SemanticFieldKeys.conditions(previous.conditions, previous.timestamp, zone) ==
                    SemanticFieldKeys.conditions(conditions, event.timestamp, zone)
                val recalled = relatedCandidates().filter(::sameConditions).take(HISTORY_LIMIT + 1).toList()
                val truncated = recalled.size > HISTORY_LIMIT
                val comparableHistory = recalled.take(HISTORY_LIMIT)
                val comparable = comparableHistory.firstOrNull()
                val related = comparable ?: relatedCandidates().firstOrNull()
                // Compare each information fragment with the UNION of historical evidence, not just the latest item.
                val matches = evidence.associateWith { fragment ->
                    comparableHistory.asSequence().flatMap { prior ->
                        checkCancellation()
                        prior.evidenceDetails.asSequence().filter {
                            it.type == fragment.type && EvidenceAtoms.compare(fragment.atom(), it.atom()) == EvidenceRelation.REUSED
                        }.map { EvidenceHistoryMatch(prior.eventId, it.text) }
                    }.distinct().take(MATCHES_PER_FRAGMENT).toList()
                }
                fun reused(fragment: EvidenceFragment) = matches[fragment].orEmpty().isNotEmpty()
                fun unresolved(fragment: EvidenceFragment) = !reused(fragment) && comparableHistory.any { prior ->
                    checkCancellation()
                    prior.evidenceDetails.any { old ->
                        EvidenceAtoms.compare(fragment.atom(), old.atom()) == EvidenceRelation.UNRESOLVED }
                }
                val increment = when {
                    uncertain || subject.isBlank() -> Increment.UNKNOWN
                    related != null && !sameConditions(related) -> Increment.CONDITIONS
                    comparable == null -> Increment.FIRST
                    evidence.isEmpty() || comparableHistory.all { it.evidenceDetails.isEmpty() } -> Increment.UNKNOWN
                    evidence.all { reused(it) } -> Increment.REPEATED
                    evidence.any(::unresolved) -> Increment.UNKNOWN
                    // An unmatched fragment beyond a truncated recall is not enough to establish novelty.
                    truncated -> Increment.UNKNOWN
                    else -> Increment.NEW
                }
                val changedConditions = if (increment == Increment.CONDITIONS && !model) {
                    conditionMarker.findAll((listOf(quote) + evidence.map { it.text }).joinToString(" "))
                        .map { it.value }.distinct().filter { related?.conditions?.contains(it) != true }
                        .map { EvidenceFragment(it, EvidenceType.CONDITION, isNew = true) }.toList()
                } else emptyList()
                val details = evidence.map { it.copy(isNew = increment == Increment.NEW && !reused(it),
                    historyMatches = matches[it].orEmpty(), identityUnresolved = unresolved(it)) } + changedConditions
                val observation = ClaimObservation(event.id, event.timestamp, event.packageName, topic, quote,
                    details.map { it.text }, conditions, uncertain, increment, comparable?.eventId ?: related?.eventId,
                    if (within) window.durationMillis(event) else event.durationMillis.takeIf { window.durationKnown(event) },
                    details, subject, comparableHistory.map { it.eventId }.distinct(), truncated, dimension, stance, source)
                observations += event to observation
                if (!uncertain && subject.isNotBlank()) historyByClaim.getOrPut(historyKey) { mutableListOf() } += observation
            }
        }
        val usableSet = usableEvents.toSet()
        val eventsById = ordered.associateBy { it.id }
        fun contactId(id: Long): Long? = eventsById[id]?.let {
            if (it.sourceType == "screen_ocr") it.parentEventId else it.id
        }
        val current = observations.filter { it.first in usableSet }.map { it.second }
        val grouped = mutableListOf<MutableList<ClaimObservation>>()
        current.forEach { observation ->
            checkCancellation()
            val group = grouped.asReversed().asSequence().filter { it.first().topic == observation.topic }.take(120)
                .firstOrNull { members -> members.first().let {
                    sameClaimGroup(it, observation, zone)
                } }
            if (group == null) grouped += mutableListOf(observation) else group += observation
        }
        val groups = grouped.map { ClaimGroup(it.first().topic, it.first().quote, it.toList()) }
            .sortedByDescending { it.observations.mapNotNull { candidate -> contactId(candidate.eventId) }.distinct().size }
        val topicCounts = current.groupingBy { it.topic }.eachCount().map { TopicCount(it.key, it.value) }.sortedByDescending { it.count }
        val slicesByEvent = usableEvents.associateWith { checkCancellation(); window.slices(it) }
        fun dates(ids: Set<Long>) = usableEvents.filter { it.id in ids }.flatMap { slicesByEvent[it].orEmpty() }
            .map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.distinct().size
        val exposure = ExposureAnalyzer.analyze(ordered, observations.map { it.second }, now, zone,
            eventTopics.mapKeys { it.key.id }, checkCancellation)
        val findings = exposure.findings()
        val exposures = usableEvents.groupBy { eventTopics[it] ?: "未分类" }.map { (topic, entries) ->
            TopicExposure(topic, entries.size, entries.sumOf { window.durationMillis(it) ?: 0 })
        }.sortedWith(compareByDescending<TopicExposure> { it.durationMillis }.thenByDescending { it.recordCount }.thenBy { it.topic })
        val reviews = (0..23).map { hour ->
            checkCancellation()
            val entries = usableEvents.filter { event -> slicesByEvent[event].orEmpty().any { it.hour == hour } }
            val entryIds = entries.map { it.id }.toSet()
            HourlyReview(hour, entries.size,
                entries.sumOf { event -> slicesByEvent[event].orEmpty().filter { it.hour == hour }.sumOf { it.durationMillis } },
                entries.count { !window.durationKnown(it) }, current.filter { it.eventId in entryIds }.map { it.eventId }.distinct(),
                entries.groupingBy { eventTopics[it] ?: "未分类" }.eachCount().map { TopicCount(it.key, it.value) }.sortedByDescending { it.count })
        }
        val hours = IntArray(24)
        current.forEach { hours[Instant.ofEpochMilli(maxOf(window.start, it.timestamp)).atZone(zone).hour]++ }
        val prior = ordered.filter { it.timestamp < window.start && !window.contains(it) }
        return CognitiveReport(now, window.start, now, period.label, selected.size, usableEvents.size, skipped, merged, current,
            groups, topicCounts, findings, dates(usableEvents.map { it.id }.toSet()), selected.groupingBy { it.packageName }.eachCount(),
            purpose, hours.toList(), zone.id, hourlyReviews = reviews, coverageSessions = usableEvents.size,
            method = if (structuredAnalyzed > 0)
                "云端分析与本地历史对照（原文校验；无有效结果时用本地识别，历史比较未经模型训练或校准）"
                else "本地识别与历史片段对照（未经模型训练，结果需核实）",
            observedDurationMillis = usableEvents.sumOf { window.durationMillis(it) ?: 0 },
            unknownDurationRecords = usableEvents.count { !window.durationKnown(it) }, topicExposure = exposures,
            contentSignals = signals, comparisonStart = prior.firstOrNull()?.timestamp, comparisonRecords = prior.size,
            workStatistics = com.emerge.app.ui.calculateStatistics(events, period, now, zone),
            structuredAnalyzedRecords = structuredAnalyzed, localFallbackRecords = localFallback,
            rejectedSemanticsRecords = rejectedSemantics,
            pendingAnalysisRecords = selected.count { analysisStatuses[it.id] in setOf("queued", "running") },
            failedAnalysisRecords = selected.count { analysisStatuses[it.id] == "failed" }, exposure = exposure)
    }

    private fun clean(event: BrowseEvent): List<String> {
        if (event.packageName.contains("launcher") || event.packageName == "com.android.systemui") return emptyList()
        return event.visibleText.split(Regex("[\\n。！？]+"))
            .map { it.trim().replace(Regex("\\s+"), " ") }.filter { line ->
                line.length >= 8 && line !in uiLabels && !Regex("^[0-9.,万千\\s:+/-]+$").matches(line) &&
                    !line.startsWith("http") && !line.matches(Regex(".*(?:点赞|评论|收藏|粉丝)\\s*[0-9.万千]+.*"))
            }.distinct().take(100)
    }
    private fun detectTopic(text: String): String? = topics.entries.map { entry ->
        entry.key to entry.value.count { text.contains(it, ignoreCase = true) }
    }.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first

    private fun localCandidates(lines: List<String>, capturedAt: Long, zone: ZoneId): List<Candidate> = lines.filter {
        it.length in 8..240 && predicate.containsMatchIn(it) &&
            !Regex("^(?:因为|由于|例如|根据|数据显示|建议|首先|其次|如果|针对|适用|调查显示|报告指出)").containsMatchIn(it)
    }.take(6).mapNotNull { quote ->
        val topic = detectTopic(quote) ?: return@mapNotNull null
        // Entertainment remains in exposure statistics, without a low-information judgment.
        if (topic == "娱乐与休闲") return@mapNotNull null
        val evidence = extractEvidence(lines, quote, topic, capturedAt, zone)
        val conditions = conditionMarker.findAll((listOf(quote) + evidence.map { it.text }).joinToString(" "))
            .map { it.value }.distinct().sorted().joinToString("、")
        Candidate(topic, quote, subject(quote), evidence, conditions, attributionMarker.containsMatchIn(quote))
    }

    private fun fieldKey(value: String) = value.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun sameClaimGroup(first: ClaimObservation, next: ClaimObservation, zone: ZoneId): Boolean {
        if (first.analysisSource != next.analysisSource) return false
        if (first.analysisSource == "structured_model") return !first.uncertainAttribution && !next.uncertainAttribution &&
            first.subject.isNotBlank() && first.dimension.isNotBlank() && first.stance.isNotBlank() &&
            fieldKey(first.topic) == fieldKey(next.topic) && fieldKey(first.subject) == fieldKey(next.subject) &&
            fieldKey(first.dimension) == fieldKey(next.dimension) &&
            SemanticFieldKeys.stance(first.stance, first.subject, first.dimension) == SemanticFieldKeys.stance(next.stance, next.subject, next.dimension) &&
            SemanticFieldKeys.conditions(first.conditions, first.timestamp, zone) == SemanticFieldKeys.conditions(next.conditions, next.timestamp, zone)
        return first.subject.isNotBlank() && first.subject == next.subject &&
            SemanticFieldKeys.conditions(first.conditions, first.timestamp, zone) == SemanticFieldKeys.conditions(next.conditions, next.timestamp, zone) &&
            first.uncertainAttribution == next.uncertainAttribution && polarity(first.quote) == polarity(next.quote) &&
            similarity(comparisonPart(first.quote), comparisonPart(next.quote)) >= 0.65
    }

    private fun evidenceType(fragment: String): EvidenceType = when {
        Regex("建议|可以|应当|应该|优先|避免|首先|其次").containsMatchIn(fragment) -> EvidenceType.ADVICE
        Regex("如果|仅在|适用|前提|针对|对于|地区|人群").containsMatchIn(fragment) -> EvidenceType.CONDITION
        numericTokens.containsMatchIn(EvidenceTextCanonicalizer.canonicalize(fragment)) || Regex("数据|调查|报告|统计|研究").containsMatchIn(fragment) -> EvidenceType.FACT
        Regex("因为|由于|原因|例如|解释").containsMatchIn(fragment) -> EvidenceType.EXPLANATION
        else -> EvidenceType.OTHER
    }

    private fun extractEvidence(lines: List<String>, quote: String, topic: String, capturedAt: Long, zone: ZoneId): List<EvidenceFragment> {
        // Keep dependent clauses together; only the shared coverage layer may split independent facts.
        val own = evidenceMarker.find(quote)?.let {
            val boundary = quote.lastIndexOfAny(charArrayOf('，', ',', '；', ';'), it.range.first - 1) + 1
            listOf(quote.substring(boundary).trim())
        }.orEmpty()
        val position = lines.indexOf(quote)
        val nearby = lines.drop(position + 1).take(2).filter {
            // A neighboring independent judgment is not automatically evidence for this judgment.
            Regex("^(?:因为|由于|根据|数据|调查|报告|统计|例如|建议|可以|首先|其次|如果|针对|对于|仅|适用|前提|[0-9])").containsMatchIn(it) &&
                evidenceMarker.containsMatchIn(it) && (detectTopic(it) == null || detectTopic(it) == topic)
        }
        return (own + nearby).distinct().take(6).flatMap { evidenceFragments(it, capturedAt = capturedAt, zone = zone) }
    }
    private fun evidenceFragments(text: String, identity: EvidenceIdentity = EvidenceIdentityComparator.identity(text),
        capturedAt: Long, zone: ZoneId) =
        EvidenceAtoms.split(text, identity, capturedAt, zone).map { EvidenceFragment(it.text, evidenceType(it.comparisonText),
            identity = it.identity, comparisonText = it.comparisonText, strictComparison = it.strictPayload,
            relativeTimeScope = it.relativeTimeScope) }
    private fun EvidenceFragment.atom() = EvidenceAtom(text, comparisonText, identity, strictComparison, relativeTimeScope)
    private fun claimPart(text: String) = text.split(Regex("因为|由于|数据显示|根据|例如|，因为|,因为")).first()
    private fun comparisonPart(text: String) = claimPart(text).replace(conditionMarker, "")
    private fun subject(text: String): String {
        val statement = claimPart(text)
        val index = predicate.find(statement)?.range?.first ?: return ""
        val before = statement.substring(0, index).replace(conditionMarker, "")
            .replace(Regex("^(?:我|我们|作者|专家|文章|这篇文章)(?:认为|觉得|表示|说)?"), "")
            .replace(Regex("(?:已经|正在|仍然|仍|已|将|也|就|都|还|很|更|明显|持续)+$"), "")
        return normalize(before)
    }
    private fun polarity(text: String) = Regex("不|没|无|下降|减少|降低|增长|增加|提高")
        .findAll(claimPart(text)).map { it.value }.distinct().sorted().joinToString()
    private fun normalize(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun similarity(a: String, b: String): Double {
        val first = normalize(a)
        val second = normalize(b)
        if (first == second) return 1.0
        if (first.length < 4 || second.length < 4) return 0.0
        val aa = first.windowed(2).toSet()
        val bb = second.windowed(2).toSet()
        return aa.intersect(bb).size.toDouble() / max(1, aa.union(bb).size)
    }
}
