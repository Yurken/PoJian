package com.emerge.app.analysis

import org.json.JSONArray
import org.json.JSONObject

/** Content-level potential, not a diagnosis of the viewer or a calibrated probability. */
enum class ImpactLevel(val wire: String, val label: String) {
    UNKNOWN("unknown", "未评估"), LOW("low", "低"), MEDIUM("medium", "中"), HIGH("high", "高");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}

data class ImpactSignal(val level: ImpactLevel, val reason: String, val evidence: String) {
    fun validFor(text: String) = reason.isNotBlank() && reason.length <= 240 && evidence.length <= 300 &&
        (if (level == ImpactLevel.UNKNOWN) evidence.isEmpty() || text.contains(evidence)
        else evidence.isNotBlank() && text.contains(evidence))
}

data class ClaimPresentation(val category: String, val viewpoint: String,
    val anxiety: ImpactSignal, val cocoon: ImpactSignal, val cognition: ImpactSignal,
    val suggestions: List<String>) {
    val signals get() = listOf(anxiety, cocoon, cognition)
    fun validFor(text: String) = category in CATEGORIES && viewpoint.isNotBlank() &&
        viewpoint.length <= 48 && viewpoint !in CATEGORIES && signals.all { it.validFor(text) } &&
        suggestions.size in 1..3 && suggestions.all { it.isNotBlank() && it.length <= 120 }
    companion object {
        val CATEGORIES = setOf("教育", "生活", "求职", "就业", "体育", "健康", "消费", "科技", "家庭", "公共议题", "其他")
    }
}

/** Optional JSON extension: old Room rows remain readable without rewriting personal history. */
object ClaimPresentationCodec {
    fun encode(value: ClaimPresentation): JSONObject = JSONObject().apply {
        put("category", value.category); put("viewpoint", value.viewpoint)
        fun signal(value: ImpactSignal) = JSONObject().put("level", value.level.wire)
            .put("reason", value.reason).put("evidence", value.evidence)
        put("anxiety", signal(value.anxiety)); put("cocoon", signal(value.cocoon)); put("cognition", signal(value.cognition))
        put("suggestions", JSONArray(value.suggestions))
    }
    fun decode(value: JSONObject): ClaimPresentation {
        fun JSONObject.string(key: String) = get(key) as? String ?: error("Invalid presentation field")
        fun signal(key: String): ImpactSignal {
            val row = value.getJSONObject(key)
            return ImpactSignal(ImpactLevel.parse(row.string("level")), row.string("reason"), row.string("evidence"))
        }
        val suggestions = value.getJSONArray("suggestions")
        require(suggestions.length() in 1..3)
        return ClaimPresentation(value.string("category"), value.string("viewpoint"), signal("anxiety"),
            signal("cocoon"), signal("cognition"), (0 until suggestions.length()).map {
                suggestions.get(it) as? String ?: error("Invalid suggestion")
            })
    }
}
