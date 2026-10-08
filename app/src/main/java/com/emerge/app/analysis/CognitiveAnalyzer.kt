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
data class EvidenceFragment(val text: String, val type: EvidenceType, val isNew: Boolean = false)
data class ClaimObservation(val eventId: Long, val timestamp: Long, val packageName: String,
    val topic: String, val quote: String, val evidence: List<String>, val conditions: String,
    val uncertainAttribution: Boolean, val increment: Increment, val previousEventId: Long?,
    val durationMillis: Long? = null, val evidenceDetails: List<EvidenceFragment> = emptyList(),
    val subject: String = "")
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
    val method: String = "本地文字识别规则 v0.3（未经过模型训练，结果需人工核实）",
    val hourlyReviews: List<HourlyReview> = emptyList(), val coverageSessions: Int = 0,
    val observedDurationMillis: Long = 0, val unknownDurationRecords: Int = 0,
    val topicExposure: List<TopicExposure> = emptyList(), val contentSignals: List<ContentSignal> = emptyList(),
    val comparisonStart: Long? = null, val comparisonRecords: Int = 0)

class CognitiveAnalyzer {
    private val topics = linkedMapOf(
        "就业与职场" to listOf("就业", "求职", "招聘", "岗位", "裁员", "秋招", "春招", "面试", "职场", "薪资", "工资", "程序员"),
        "教育与升学" to listOf("考研", "保研", "升学", "院校", "高考", "大学", "专业", "留学", "学历", "学费"),
        "科技与产品" to listOf("人工智能", "大模型", "手机", "芯片", "算法", "软件", "电脑", "AI", "机器人"),
        "经济与消费" to listOf("经济", "消费", "房价", "房贷", "物价", "收入", "投资", "股市", "存款"),
        "健康与生活" to listOf("健康", "睡眠", "运动", "饮食", "减肥", "疾病", "医疗", "养生"),
        "社会与关系" to listOf("社会", "婚姻", "结婚", "生育", "恋爱", "家庭", "政策", "养老"),
        "娱乐与休闲" to listOf("搞笑", "舞蹈", "游戏", "音乐", "电影", "追剧", "综艺", "明星")
    )
    private val uiLabels = setOf("点赞", "评论", "收藏", "分享", "关注", "已关注", "推荐", "首页", "消息", "我", "我的", "返回", "搜索", "发布", "直播", "购物", "复制链接", "展开", "收起", "导航", "设置", "Play Store", "Gmail", "Photos", "Chrome", "YouTube", "Navigate up")
    private val predicate = Regex("认为|觉得|意味着|说明|导致|应该|不应|必须|需要|可能|不能|不可能|越来越|更难|很难|很差|不好|容易|值得|没有机会|增长|下降|减少|增加|提高|降低|是|不是|会|不会|能|难|好|差")
    private val evidenceMarker = Regex("因为|由于|数据显示|根据|调查|报告|统计|例如|同比|原因|建议|可以|首先|其次|如果|针对|对于|仅在|前提|[0-9]+(?:\\.[0-9]+)?[%万亿年月日人个元]")
    private val conditionMarker = Regex("未来|明年|今年|当前|过去|去年|现在|[0-9]{4}年|北京|上海|深圳|广州|地区|应届|嵌入式|互联网|医疗|制造业|如果[^，。；\\n]{0,30}")
    private val attributionMarker = Regex("有人说|有人认为|听说|据说|网传|引用|反驳|所谓|并非|不代表|不意味着|但|然而|不是.*而是|[“”\"]")
    private val numericTokens = Regex("[0-9]+(?:\\.[0-9]+)?(?:[%万亿年月日人个元])?")
    private val emotionalExpression = Regex("焦虑|恐慌|恐惧|绝望|完蛋|没有未来|毁掉|再不[^，。；]{0,18}就|你还不[^，。；]{0,18}")

    fun analyze(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), purpose: String = "未填写",
        checkCancellation: () -> Unit = {}): CognitiveReport {
        val window = ObservationWindow.forPeriod(period, now, zone)
        // Retained earlier history is compared in browsing order, never in analysis completion order.
        val ordered = events.filter { it.timestamp <= now }.sortedWith(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id })
        val selected = ordered.filter { window.contains(it) }
        val observations = mutableListOf<Pair<BrowseEvent, ClaimObservation>>()
        val usableEvents = mutableListOf<BrowseEvent>()
        val eventTopics = mutableMapOf<BrowseEvent, String>()
        val signals = mutableListOf<ContentSignal>()
        var previous: BrowseEvent? = null
        var skipped = 0
        var merged = 0
        ordered.forEach { event ->
            checkCancellation()
            val within = window.contains(event)
            val lines = clean(event)
            if (lines.isEmpty()) { previous = null; if (within) skipped++; return@forEach }
            val old = previous
            previous = event
            // Do not merge a first in-window snapshot into an unselected historical snapshot.
            if (old != null && window.contains(old) == within && old.sampleCount == 0 && event.sampleCount == 0 &&
                old.packageName == event.packageName && event.timestamp - old.timestamp in 0..30_000 &&
                com.emerge.app.capture.ContentContinuity.same(old.visibleText, event.visibleText)) {
                if (within) merged++
                return@forEach
            }
            if (within) {
                usableEvents += event
                eventTopics[event] = detectTopic(lines.joinToString(" ")) ?: "未分类"
                lines.filter { emotionalExpression.containsMatchIn(it) }.take(2).forEach {
                    signals += ContentSignal(event.id, "原文含焦虑或紧迫的表达", it)
                }
            }
            val candidates = lines.filter { it.length in 8..240 && predicate.containsMatchIn(it) &&
                !Regex("^(?:因为|由于|例如|根据|数据显示|建议|首先|其次|如果|针对|适用|调查显示|报告指出)").containsMatchIn(it)
            }.take(6)
            candidates.forEach candidate@ { quote ->
                val topic = detectTopic(quote) ?: return@candidate
                // Entertainment remains in exposure statistics, without a low-information judgment.
                if (topic == "娱乐与休闲") return@candidate
                val subject = subject(quote)
                val evidence = extractEvidence(lines, quote, topic)
                val conditions = conditionMarker.findAll((listOf(quote) + evidence.map { it.text }).joinToString(" "))
                    .map { it.value }.distinct().sorted().joinToString("、")
                val uncertain = attributionMarker.containsMatchIn(quote)
                val relatedCandidates = if (uncertain || subject.isBlank()) emptyList() else observations.asReversed().asSequence()
                    .map { it.second }.filter { it.eventId != event.id && it.topic == topic && !it.uncertainAttribution }
                    .take(120).filter { it.subject == subject && polarity(it.quote) == polarity(quote) &&
                        similarity(comparisonPart(it.quote), comparisonPart(quote)) >= 0.65 }.toList()
                // An intervening different region must not hide an older same-condition observation.
                val comparable = relatedCandidates.firstOrNull { it.conditions == conditions }
                val related = comparable ?: relatedCandidates.firstOrNull()
                fun reused(fragment: EvidenceFragment) = comparable?.evidenceDetails?.any {
                    it.type == fragment.type && sameEvidence(fragment.text, it.text)
                } == true
                val increment = when {
                    uncertain -> Increment.UNKNOWN
                    related != null && related.conditions != conditions -> Increment.CONDITIONS
                    comparable == null -> Increment.FIRST
                    evidence.isEmpty() || comparable.evidenceDetails.isEmpty() -> Increment.UNKNOWN
                    evidence.all { reused(it) } -> Increment.REPEATED
                    else -> Increment.NEW
                }
                val changedConditions = if (increment == Increment.CONDITIONS) {
                    conditionMarker.findAll((listOf(quote) + evidence.map { it.text }).joinToString(" "))
                        .map { it.value }.distinct().filter { related?.conditions?.contains(it) != true }
                        .map { EvidenceFragment(it, EvidenceType.CONDITION, isNew = true) }.toList()
                } else emptyList()
                val details = evidence.map { it.copy(isNew = increment == Increment.NEW && !reused(it)) } + changedConditions
                observations += event to ClaimObservation(event.id, event.timestamp, event.packageName, topic, quote,
                    details.map { it.text }, conditions, uncertain, increment, comparable?.eventId ?: related?.eventId,
                    if (within) window.durationMillis(event) else event.durationMillis.takeIf { window.durationKnown(event) },
                    details, subject)
            }
        }
        val usableSet = usableEvents.toSet()
        val current = observations.filter { it.first in usableSet }.map { it.second }
        val grouped = mutableListOf<MutableList<ClaimObservation>>()
        current.forEach { observation ->
            checkCancellation()
            val group = grouped.asReversed().asSequence().filter { it.first().topic == observation.topic }.take(120)
                .firstOrNull { members -> members.first().let {
                    it.subject.isNotBlank() && it.subject == observation.subject && it.conditions == observation.conditions &&
                        it.uncertainAttribution == observation.uncertainAttribution && polarity(it.quote) == polarity(observation.quote) &&
                        similarity(comparisonPart(it.quote), comparisonPart(observation.quote)) >= 0.65
                } }
            if (group == null) grouped += mutableListOf(observation) else group += observation
        }
        val groups = grouped.map { ClaimGroup(it.first().topic, it.first().quote, it.toList()) }
            .sortedByDescending { it.observations.map { candidate -> candidate.eventId }.distinct().size }
        val topicCounts = current.groupingBy { it.topic }.eachCount().map { TopicCount(it.key, it.value) }.sortedByDescending { it.count }
        val currentEventsById = usableEvents.associateBy { it.id }
        val slicesByEvent = usableEvents.associateWith { checkCancellation(); window.slices(it) }
        fun dates(ids: Set<Long>) = usableEvents.filter { it.id in ids }.flatMap { slicesByEvent[it].orEmpty() }
            .map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.distinct().size
        val findings = topicCounts.map { topic ->
            checkCancellation()
            val members = current.filter { it.topic == topic.topic }
            val ids = members.map { it.eventId }.toSet()
            val major = groups.first { it.topic == topic.topic }
            val majorIds = major.observations.map { it.eventId }.toSet()
            val repeated = members.filter { it.increment == Increment.REPEATED }.map { it.eventId }.distinct().size
            val topicDays = dates(ids)
            val title = when {
                ids.size < 8 || topicDays < 3 -> "观察不足，暂时无法判断是否收窄"
                majorIds.size.toDouble() / ids.size >= 0.7 && repeated.toDouble() / ids.size >= 0.6 -> "观察到持续低增量重复，建议回查来源"
                members.any { it.increment == Increment.NEW } -> "观察到依据有补充，仍需核实内容"
                else -> "暂未发现重复线索，不代表不存在信息茧房"
            }
            val knownDuration = ids.sumOf { currentEventsById[it]?.let(window::durationMillis) ?: 0 }
            val majorDuration = majorIds.sumOf { currentEventsById[it]?.let(window::durationMillis) ?: 0 }
            val unknown = ids.count { currentEventsById[it]?.let { event -> !window.durationKnown(event) } == true }
            ObservationFinding(topic.topic, title,
                "在 ${ids.size} 条相关记录中识别出 ${members.size} 个观点，覆盖 $topicDays 天；其中最大的一组相似说法占 ${majorIds.size} 条；依据重复 $repeated 条，依据新增 ${members.count { it.increment == Increment.NEW }} 个，${members.count { it.increment == Increment.UNKNOWN }} 个暂无法判断。该主题的记录预计停留共 ${knownDuration / 1000} 秒，其中最大相似组 ${majorDuration / 1000} 秒，$unknown 条未统计时长。停留按记录去重；没有时长数据时无法判断是否阅读集中。内容的作者、立场和材料来源尚未做模型验证。",
                majorIds.take(8))
        }.ifEmpty { listOf(ObservationFinding("全部", "没有识别到可分析的观点", "当前内容可能只有界面按钮、娱乐内容、短评，或缺少明确的判断句。娱乐内容仍计入接触统计；这不能用来推断信息茧房。", emptyList())) }
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
            observedDurationMillis = usableEvents.sumOf { window.durationMillis(it) ?: 0 },
            unknownDurationRecords = usableEvents.count { !window.durationKnown(it) }, topicExposure = exposures,
            contentSignals = signals, comparisonStart = prior.firstOrNull()?.timestamp, comparisonRecords = prior.size)
    }

    private fun clean(event: BrowseEvent): List<String> {
        if (event.packageName.contains("launcher") || event.packageName == "com.android.systemui") return emptyList()
        return event.visibleText.split(Regex("[\\n。！？；]+"))
            .map { it.trim().replace(Regex("\\s+"), " ") }.filter { line ->
                line.length >= 8 && line !in uiLabels && !Regex("^[0-9.,万千\\s:+/-]+$").matches(line) &&
                    !line.startsWith("http") && !line.matches(Regex(".*(?:点赞|评论|收藏|粉丝)\\s*[0-9.万千]+.*"))
            }.distinct().take(100)
    }
    private fun detectTopic(text: String): String? = topics.entries.map { entry ->
        entry.key to entry.value.count { text.contains(it, ignoreCase = true) }
    }.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first

    private fun extractEvidence(lines: List<String>, quote: String, topic: String): List<EvidenceFragment> {
        val own = quote.split(Regex("[，,；;]"))
            .filter { evidenceMarker.containsMatchIn(it) }.map { it.trim() }
        val position = lines.indexOf(quote)
        val nearby = lines.drop(position + 1).take(2).filter {
            // A neighboring independent judgment is not automatically evidence for this judgment.
            Regex("^(?:因为|由于|根据|数据|调查|报告|统计|例如|建议|可以|首先|其次|如果|针对|对于|仅|适用|前提|[0-9])").containsMatchIn(it) &&
                evidenceMarker.containsMatchIn(it) && (detectTopic(it) == null || detectTopic(it) == topic)
        }
        return (own + nearby).distinct().take(6).map { fragment ->
            val type = when {
                Regex("建议|可以|应当|应该|优先|避免|首先|其次").containsMatchIn(fragment) -> EvidenceType.ADVICE
                Regex("如果|仅在|适用|前提|针对|对于|地区|人群").containsMatchIn(fragment) -> EvidenceType.CONDITION
                numericTokens.containsMatchIn(fragment) || Regex("数据|调查|报告|统计|研究").containsMatchIn(fragment) -> EvidenceType.FACT
                Regex("因为|由于|原因|例如|解释").containsMatchIn(fragment) -> EvidenceType.EXPLANATION
                else -> EvidenceType.OTHER
            }
            EvidenceFragment(fragment, type)
        }
    }
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
    private fun sameEvidence(a: String, b: String): Boolean =
        numericTokens.findAll(a).map { it.value }.toList() == numericTokens.findAll(b).map { it.value }.toList() && similarity(a, b) >= 0.85
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
