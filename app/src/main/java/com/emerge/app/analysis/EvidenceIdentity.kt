package com.emerge.app.analysis

/** Literal references in retained evidence, not verified real-world identities or independent sources. */
data class EvidenceIdentity(val actors: List<String> = emptyList(), val origins: List<String> = emptyList(),
    val unresolvedCase: Boolean = false, val unresolvedReferences: Boolean = false)

enum class EvidenceRelation { REUSED, DIFFERENT_CASE, CHANGED, UNRESOLVED }

/** Shared by historical review and live monitoring; no platform/account identity implies a new fact. */
object EvidenceIdentityComparator {
    private val numbers = Regex("[-+−<>≤≥±≈~～]?\\s*[0-9]+(?:\\.[0-9]+)?(?:个百分点|个招聘名额|招聘名额|千米|公里|厘米|毫米|公斤|千克|毫升|小时|分钟|[%％万亿年月日人名个份件封次家所台元天秒米克吨升倍瓦度例])?")
    private val qualifiers = Regex("不少于|不超过|不低于|不高于|上升|下降|增加|减少|提高|降低|增长|下跌|至少|至多|超过|不足|大约|约|左右|上下|预计|估计|预测|可能|不|没|未|无")
    private val timeScope = Regex("今年|去年|前年|明年|后年|本月|上月|下月|本季度|上季度|下季度|[0-9]{4}年|[0-9]{1,2}月")
    private val placeScope = Regex("(?:在|向|位于|居住于)(北京|上海|深圳|广州|天津|重庆|杭州|成都|南京|武汉|西安|苏州)|(?:在|向|位于|居住于)([\\p{L}]{2,12}?(?:市|省|县|地区))|(?:地区为|城市为|城市：|城市:)([^\\s，,。；;]{2,12})")
    // Only an explicitly attributed personal record supplies a legacy case actor. Arbitrary 'according to X' does not.
    private val personalRecord = Regex("(?:根据|依据|来自|据)\\s*([^\\s，,。；;：:\\n]{1,40}?)的(?:本人|个人|亲身)(?:记录|自述|经历)")
    private val namedPersonalRecord = Regex("(?:^|[，,；;：:\\s])([^\\s，,。；;：:\\n]{1,40}?)的(?:本人|个人|亲身)(?:记录|自述|经历)")
    private const val materialName = "(?:报告|调查|研究|统计资料)(?:(?!显示|记录|指出|表明|说明|列出|公布|提供|预测)[\\p{L}\\p{N}·_-]){1,20}?"
    private val material = Regex("(?:^|[，,；;：:\\s])(?:根据|依据|据)?($materialName)(?:显示|记录|指出|表明|说明|列出|公布|提供)")
    private val directedMaterial = Regex("(?:^|[，,；;：:\\s])(?:根据|依据|据)($materialName)[，,]")
    private val materialTitle = Regex("《([^《》\\n]{1,78})》")
    private val anonymous = Regex("^(?:我|我们|本人|有人|当事人|求职者|受访者)$|^(?:某|一位|一名|匿名|这位|该名|该求职者)|某$|先生$|女士$")
    private val personalContext = Regex("本人记录|个人记录|亲身经历|个人经历|本人自述|个人自述|匿名|(?:^|[，,；;：:\\s])(?:我|我们)(?:曾|今年|去年|在|向|投递|参加|就诊|经历|的)")
    private val actorRole = Regex("^(?:求职者|受访者|患者|学生|居民|当事人|当事对象|案例对象|工程师|设计师)(?=.+)")

    fun identity(text: String, actors: List<String> = emptyList(), origins: List<String> = emptyList()): EvidenceIdentity {
        val inferred = (personalRecord.findAll(text) + namedPersonalRecord.findAll(text))
            .map { it.groupValues[1].removePrefix("因为").removePrefix("例如")
                .removePrefix("根据").removePrefix("依据").removePrefix("来自").removePrefix("据") }
            .distinct().take(5).toList()
        val actorCandidates = actors.ifEmpty { inferred }
        val knownActors = actorCandidates.filterNot { anonymous.containsMatchIn(it.trim()) }
        val unknown = inferred.size > 4 || actorCandidates.size != knownActors.size ||
            knownActors.isEmpty() && personalContext.containsMatchIn(text)
        val inferredOrigins = (material.findAll(text).map { it.groupValues[1] } + directedMaterial.findAll(text).map { it.groupValues[1] } + materialTitle.findAll(text).map { it.value })
            .distinct().take(5).toList()
        val knownOrigins = origins.ifEmpty { inferredOrigins }
        return EvidenceIdentity(knownActors.take(4), knownOrigins.take(4), unknown,
            inferred.size > 4 || inferredOrigins.size > 4)
    }

    fun compare(current: String, previous: String, currentIdentity: EvidenceIdentity = identity(current),
        previousIdentity: EvidenceIdentity = identity(previous)): EvidenceRelation {
        if (currentIdentity.unresolvedReferences || previousIdentity.unresolvedReferences) return EvidenceRelation.UNRESOLVED
        // Digits or direction words inside a person's label or a report title are not measured values.
        fun content(text: String, identity: EvidenceIdentity): String =
            (identity.actors + identity.origins).sortedByDescending { it.length }
                .fold(text) { value, anchor -> value.replace(anchor, " ") }
        val currentContent = EvidenceTextCanonicalizer.canonicalize(content(current, currentIdentity))
        val previousContent = EvidenceTextCanonicalizer.canonicalize(content(previous, previousIdentity))
        // Quantity, negation and direction changes remain changes even if entity/source references are incomplete.
        if (numbers.findAll(currentContent).map { it.value.filterNot(Char::isWhitespace) }.toList() !=
            numbers.findAll(previousContent).map { it.value.filterNot(Char::isWhitespace) }.toList() ||
            qualifiers.findAll(currentContent).map { it.value }.toList() != qualifiers.findAll(previousContent).map { it.value }.toList())
            return EvidenceRelation.CHANGED
        val currentTimes = timeScope.findAll(currentContent).map { it.value }.toSet()
        val previousTimes = timeScope.findAll(previousContent).map { it.value }.toSet()
        val currentPlaces = placeScope.findAll(currentContent).map { it.groupValues.drop(1).first(String::isNotEmpty) }.toSet()
        val previousPlaces = placeScope.findAll(previousContent).map { it.groupValues.drop(1).first(String::isNotEmpty) }.toSet()
        if (currentTimes != previousTimes || currentPlaces != previousPlaces) {
            if (currentTimes.isEmpty() != previousTimes.isEmpty() || currentPlaces.isEmpty() != previousPlaces.isEmpty())
                return EvidenceRelation.UNRESOLVED
            return EvidenceRelation.CHANGED
        }
        val actors = keys(currentIdentity.actors.map { it.replace(actorRole, "") })
        val oldActors = keys(previousIdentity.actors.map { it.replace(actorRole, "") })
        if (currentIdentity.unresolvedCase || previousIdentity.unresolvedCase || actors.isEmpty() != oldActors.isEmpty())
            return EvidenceRelation.UNRESOLVED
        if (actors.isNotEmpty() && oldActors.isNotEmpty() && actors != oldActors) return EvidenceRelation.DIFFERENT_CASE
        // A different named material (or a missing one) is not proof of independent evidence or factual novelty.
        if (keys(currentIdentity.origins) != keys(previousIdentity.origins)) return EvidenceRelation.UNRESOLVED
        if (similarity(EvidenceTextCanonicalizer.canonicalize(current), EvidenceTextCanonicalizer.canonicalize(previous)) < 0.85) return EvidenceRelation.CHANGED
        return EvidenceRelation.REUSED
    }

    private fun keys(values: List<String>) = values.map { it.trim().lowercase().removeSurrounding("《", "》") }.toSet()
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun similarity(a: String, b: String): Double {
        val first = normalize(a); val second = normalize(b)
        if (first == second) return 1.0
        if (first.length < 4 || second.length < 4) return 0.0
        val aa = first.windowed(2).toSet(); val bb = second.windowed(2).toSet()
        return aa.intersect(bb).size.toDouble() / aa.union(bb).size.coerceAtLeast(1)
    }
}
