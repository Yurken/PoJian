package com.emerge.app.analysis

enum class ViewpointStance(val label: String) {
    SUPPORT("支持"), NEUTRAL("中立"), QUESTION("质疑"), MIXED("多立场"), UNKNOWN("未识别")
}

data class ViewpointStanceShare(val stance: ViewpointStance, val workKeys: Set<String>, val total: Int) {
    val count: Int get() = workKeys.size
    val percent: Double get() = if (total == 0) 0.0 else count * 100.0 / total
}

/** One work occupies exactly one segment, even when it has several claims or repeat visits. */
fun viewpointSpectrum(topics: List<ViewpointTopic>): List<ViewpointStanceShare> {
    val works = topics.flatMap { it.groups }.flatMap { it.items }.groupBy { it.workIdentity }
    val classified = works.mapValues { (_, items) ->
        val claims = items.flatMap { it.claims }.distinctBy { it.key }
        val stances = claims.map(::explicitStance).toSet()
        when {
            stances.isEmpty() || ViewpointStance.UNKNOWN in stances -> ViewpointStance.UNKNOWN
            stances.size > 1 -> ViewpointStance.MIXED
            else -> stances.single()
        }
    }
    return ViewpointStance.entries.mapNotNull { stance ->
        val keys = classified.filterValues { it == stance }.keys
        keys.takeIf { it.isNotEmpty() }?.let { ViewpointStanceShare(stance, it, works.size) }
    }
}

/** Keep all matching works' claims and their original window denominators for source drill-down. */
fun filterViewpointStance(topics: List<ViewpointTopic>, share: ViewpointStanceShare?): List<ViewpointTopic> {
    if (share == null) return topics
    return topics.mapNotNull { topic ->
        val groups = topic.groups.mapNotNull { group ->
            val items = group.items.filter { it.workIdentity in share.workKeys }
            items.takeIf { it.isNotEmpty() }?.let { group.copy(items = it) }
        }
        groups.takeIf { it.isNotEmpty() }?.let { topic.copy(groups = it) }
    }
}

private fun explicitStance(claim: ViewpointClaim): ViewpointStance {
    // Free-form predicates such as "下降", conditional endorsements and local labels
    // are not sentiment labels. Never guess their polarity or call unknown data neutral.
    if (claim.analysisSource != "structured_model" || !claim.confirmed) return ViewpointStance.UNKNOWN
    return when (claim.stance.trim()) {
        "支持", "认同", "赞同", "赞成", "支持 / 认同" -> ViewpointStance.SUPPORT
        "中立", "客观", "中立 / 客观" -> ViewpointStance.NEUTRAL
        "质疑", "反对", "不支持", "不认同", "不赞同", "不赞成", "质疑 / 反对" -> ViewpointStance.QUESTION
        else -> ViewpointStance.UNKNOWN
    }
}
