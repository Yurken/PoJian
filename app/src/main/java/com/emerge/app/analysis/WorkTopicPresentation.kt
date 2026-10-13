package com.emerge.app.analysis

data class WorkTopic(val title: String, val works: List<ViewpointItem>)

/** UI priority only. Thresholds are explicit engineering rules, not measured psychological effects. */
object WorkTopicPresentation {
    fun phrase(claim: ViewpointClaim) = claim.presentation?.viewpoint ?: ViewpointLabeler.compact(claim.quote)
    fun category(claim: ViewpointClaim): String = claim.presentation?.category
        ?: classify(claim.quote).takeUnless { it == "其他" } ?: classify(claim.topic)
    fun classify(text: String): String = when {
        Regex("求职|应聘|简历|面试|找工作").containsMatchIn(text) -> "求职"
        Regex("就业|招聘|失业|裁员|职场|工资|职业").containsMatchIn(text) -> "就业"
        Regex("教育|学校|考试|升学|学习|考研|学生").containsMatchIn(text) -> "教育"
        Regex("体育|运动|健身|足球|篮球|跑步|比赛").containsMatchIn(text) -> "体育"
        Regex("健康|医疗|睡眠|疾病|就医|饮食").containsMatchIn(text) -> "健康"
        Regex("家庭|性别|女性|婚姻|照护|育儿").containsMatchIn(text) -> "家庭"
        Regex("科技|产品|软件|人工智能|模型|手机|机器人").containsMatchIn(text) -> "科技"
        Regex("经济|消费|储蓄|收入|支出|购物|价格").containsMatchIn(text) -> "消费"
        Regex("公共|政策|社会|社区|交通").containsMatchIn(text) -> "公共议题"
        Regex("生活|人生|日常|旅行|收纳").containsMatchIn(text) -> "生活"
        else -> "其他"
    }
    fun firstSeen(item: ViewpointItem, window: ObservationWindow): Long = item.records
        .filter { it.sourceType == "accessibility" && window.contains(it) }.flatMap { window.slices(it) }
        .filter { it.end > window.start && it.start < window.end || it.start in window.start..window.end }
        .minOfOrNull { maxOf(it.start, window.start) } ?: maxOf(item.record.timestamp, window.start)

    fun topics(items: List<ViewpointItem>, window: ObservationWindow): List<WorkTopic> {
        val ordered = ViewpointTimeline.details(items).sortedWith(compareBy<ViewpointItem> { firstSeen(it, window) }.thenBy { it.workIdentity })
        return ordered.groupBy { item ->
            // One work stays in one primary topic, including works with several genuine viewpoints.
            item.claims.groupingBy(::category).eachCount().entries.sortedByDescending { it.value }
                .firstOrNull()?.key ?: classify(item.record.visibleText)
        }.map { (title, works) -> WorkTopic(title, works) }
    }

    fun impact(claims: List<ViewpointClaim>, durationMillis: Long?): ImpactLevel {
        if (durationMillis == null || durationMillis <= 0 || claims.isEmpty()) return ImpactLevel.UNKNOWN
        val assessed = claims.filter { !it.uncertain }.mapNotNull { it.presentation }
        val levels = assessed.flatMap { it.signals }.map { it.level }
        return when {
            ImpactLevel.HIGH in levels -> if (durationMillis >= 60_000) ImpactLevel.HIGH else ImpactLevel.MEDIUM
            ImpactLevel.MEDIUM in levels -> if (durationMillis >= 180_000) ImpactLevel.HIGH else ImpactLevel.MEDIUM
            assessed.size == claims.size && levels.all { it == ImpactLevel.LOW } -> ImpactLevel.LOW
            else -> ImpactLevel.UNKNOWN
        }
    }
}
