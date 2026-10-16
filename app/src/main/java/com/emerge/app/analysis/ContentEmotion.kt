package com.emerge.app.analysis

/** Content labels from a general-purpose model, never the viewer's emotional state. */
enum class EmotionCategory(val wire: String, val label: String) {
    ANXIETY("anxiety", "焦虑"), ANGER("anger", "愤怒"), FEAR("fear", "恐惧"),
    SADNESS("sadness", "悲伤"), JOY("joy", "喜悦"), NEUTRAL("neutral", "中性");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}

/** UNKNOWN means unassessed intensity; a null trigger means no supported trigger was identified. */
data class ContentEmotion(val category: EmotionCategory, val level: ImpactLevel,
    val evidence: String, val trigger: String? = null) {
    fun validFor(text: String): Boolean = evidence.isNotBlank() && evidence.length <= 300 && text.contains(evidence) &&
        (trigger == null || trigger.isNotBlank() && trigger.length <= 300 && text.contains(trigger))
}
