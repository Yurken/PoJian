package com.zhiheng.app.analysis

import com.zhiheng.app.data.BrowseEvent
import com.zhiheng.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

enum class Increment(val label: String) {
    FIRST("首次观察"), NEW("新增依据片段"), REPEATED("近似复述且依据重复"),
    UNKNOWN("未发现依据，增量未知"), CONDITIONS("时间或条件不同，分别保留")
}
data class ClaimObservation(val eventId: Long, val timestamp: Long, val packageName: String,
    val topic: String, val quote: String, val evidence: List<String>, val conditions: String,
    val uncertainAttribution: Boolean, val increment: Increment, val previousEventId: Long?, val durationMillis: Long? = null)
data class ClaimGroup(val topic: String, val quote: String, val observations: List<ClaimObservation>)
data class TopicCount(val topic: String, val count: Int)
data class ObservationFinding(val topic: String, val title: String, val explanation: String,
    val eventIds: List<Long>)
data class CognitiveReport(val generatedAt: Long, val start: Long, val end: Long, val period: String,
    val rawRecords: Int, val usableSnapshots: Int, val skippedRecords: Int, val mergedSnapshots: Int,
    val claims: List<ClaimObservation>, val groups: List<ClaimGroup>, val topics: List<TopicCount>,
    val findings: List<ObservationFinding>, val observedDays: Int, val sourceCounts: Map<String, Int>,
    val purpose: String, val hourlyClaims: List<Int>, val zone: String,
    val method: String = "离线文本规则基线 v0.2.1（未训练，待人工核查）")

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
    private val evidenceMarker = Regex("因为|由于|数据显示|根据|调查|报告|统计|例如|同比|岗位数量|原因|建议|可以|首先|其次|但|然而|如果|地区|行业|人群|[0-9]+(?:\\.[0-9]+)?[%万亿年月日人个元]")
    private val conditionMarker = Regex("未来|明年|今年|当前|过去|去年|现在|[0-9]{4}年|北京|上海|深圳|广州|地区|应届|嵌入式|互联网|医疗|制造业|如果[^，。；\\n]{0,20}")
    private val attributionMarker = Regex("有人说|有人认为|听说|据说|网传|引用|反驳|所谓|并非|不代表|不意味着|但|然而|不是.*而是|[“”\"]")

    fun analyze(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), purpose: String = "未填写"): CognitiveReport {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = if (period == StatisticsPeriod.DAY) now - 86_400_000L
            else today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        // Analyze retained earlier history chronologically before filtering to the report window.
        val ordered = events.filter { it.timestamp <= now }.sortedWith(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id })
        val observations = mutableListOf<ClaimObservation>()
        var previous: BrowseEvent? = null
        var skipped = 0
        var merged = 0
        var usable = 0
        val selected = ordered.filter { it.timestamp >= start }
        ordered.forEach { event ->
            val within = event.timestamp >= start
            val lines = clean(event)
            if (lines.isEmpty()) { previous = null; if (within) skipped++; return@forEach }
            val old = previous
            previous = event
            // Legacy snapshots: only adjacent same-app, stable prose visits can be combined.
            if (old != null && old.sampleCount == 0 && event.sampleCount == 0 &&
                old.packageName == event.packageName && event.timestamp - old.timestamp in 0..30_000 &&
                com.zhiheng.app.capture.ContentContinuity.same(old.visibleText, event.visibleText)) {
                if (within) merged++
                return@forEach
            }
            if (within) usable++
            val candidates = lines.filter { it.length in 8..240 && predicate.containsMatchIn(it) }.take(6)
            candidates.forEach candidate@ { quote ->
                val topic = detectTopic(quote) ?: return@candidate
                if (topic == "娱乐与休闲") return@candidate
                val conditions = conditionMarker.findAll(quote).map { it.value }.distinct().sorted().joinToString("、")
                val evidence = lines.filter { it != quote && evidenceMarker.containsMatchIn(it) }.take(4)
                    .ifEmpty { if (evidenceMarker.containsMatchIn(quote)) listOf(quote) else emptyList() }
                val uncertain = attributionMarker.containsMatchIn(quote)
                // Limit comparison cost and retain target/time/conditions; no claim that this is semantic inference.
                val related = observations.asReversed().asSequence().filter { it.topic == topic }
                    .take(120).firstOrNull { polarity(it.quote) == polarity(quote) && similarity(claimPart(it.quote), claimPart(quote)) >= 0.65 }
                val comparable = related?.takeIf { it.conditions == conditions && !it.uncertainAttribution && !uncertain }
                val increment = when {
                    related != null && related.conditions != conditions -> Increment.CONDITIONS
                    comparable == null -> if (uncertain) Increment.UNKNOWN else Increment.FIRST
                    evidence.isEmpty() || comparable.evidence.isEmpty() -> Increment.UNKNOWN
                    evidence.all { current -> comparable.evidence.any { similarity(current, it) >= 0.85 } } -> Increment.REPEATED
                    else -> Increment.NEW
                }
                observations += ClaimObservation(event.id, event.timestamp, event.packageName, topic, quote,
                    evidence, conditions, uncertain, increment, comparable?.eventId ?: related?.eventId, event.durationMillis.takeIf { event.sampleCount > 0 })
            }
        }
        val current = observations.filter { it.timestamp >= start }
        val grouped = mutableListOf<MutableList<ClaimObservation>>()
        current.forEach { observation ->
            val group = grouped.asReversed().asSequence().filter { it.first().topic == observation.topic }.take(120).firstOrNull { members -> members.first().let {
                it.topic == observation.topic && it.conditions == observation.conditions &&
                    it.uncertainAttribution == observation.uncertainAttribution && polarity(it.quote) == polarity(observation.quote) && similarity(claimPart(it.quote), claimPart(observation.quote)) >= 0.65
            } }
            if (group == null) grouped += mutableListOf(observation) else group += observation
        }
        val groups = grouped.map { ClaimGroup(it.first().topic, it.first().quote, it.toList()) }
            .sortedByDescending { it.observations.size }
        val topicCounts = current.groupingBy { it.topic }.eachCount().map { TopicCount(it.key, it.value) }.sortedByDescending { it.count }
        val days = current.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.distinct().size
        val findings = topicCounts.map { topic ->
            val members = current.filter { it.topic == topic.topic }
            val major = groups.first { it.topic == topic.topic }
            val repeated = members.count { it.increment == Increment.REPEATED }
            val topicDays = members.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.distinct().size
            val title = when {
                members.map { it.eventId }.distinct().size < 8 || topicDays < 3 -> "观察不足，不能评估持续窄化"
                major.observations.size.toDouble() / members.size >= 0.7 && repeated.toDouble() / members.size >= 0.6 -> "观察到持续低增量重复线索，建议核查"
                members.any { it.increment == Increment.NEW } -> "观察到依据片段补充，仍需核查内容"
                else -> "暂未满足重复线索规则，不代表不存在信息茧房"
            }
            ObservationFinding(topic.topic, title,
                "${members.size} 个观点候选，覆盖 $topicDays 个自然日；最大近似措辞组 ${major.observations.size} 个；依据重复 $repeated 个，新增片段 ${members.count { it.increment == Increment.NEW }} 个，增量未知 ${members.count { it.increment == Increment.UNKNOWN }} 个。对象、立场及材料源头尚未做模型验证。",
                major.observations.map { it.eventId }.distinct().take(8))
        }.ifEmpty { listOf(ObservationFinding("全部", "未提取到可分析观点", "当前文字可能只有界面标签、娱乐内容、短评或缺少明确判断。请检查原文，不能据此推断信息茧房。", emptyList())) }
        val hours = IntArray(24)
        current.forEach { hours[Instant.ofEpochMilli(it.timestamp).atZone(zone).hour]++ }
        return CognitiveReport(now, start, now, period.label, selected.size, usable, skipped, merged, current,
            groups, topicCounts, findings, days, selected.groupingBy { it.packageName }.eachCount(), purpose,
            hours.toList(), zone.id)
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
    private fun claimPart(text: String) = text.split(Regex("因为|由于|数据显示|根据|例如|，因为|,因为")).first()
    private fun polarity(text: String) = Regex("不|没|无|下降|减少|降低|增长|增加|提高").findAll(claimPart(text)).map { it.value }.distinct().sorted().joinToString()
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
