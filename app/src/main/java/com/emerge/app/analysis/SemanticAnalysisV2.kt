package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SemanticEvidence(val factPoint: String, val factSpan: String, val isCoveredByHistory: Boolean,
    val matchedHistoryId: String? = null, val matchedSourceNote: String? = null)
data class SemanticViewpoint(val stanceSummary: String, val authorAttitude: String,
    val stanceSpan: String = "", val evidences: List<SemanticEvidence> = emptyList())
data class SemanticGuidance(val isOverExtrapolation: Boolean, val factSpan: String = "",
    val conclusionSpan: String = "", val scopeExtrapolation: String = "", val certaintyUpgrade: String = "",
    val emotionAmplification: String = "", val spanVerified: Boolean = true)
data class SemanticBlindSpot(val dimension: String, val gapTip: String, val sanitizedQuery: String)
data class SemanticExpressionSpans(val factSpans: List<String> = emptyList(), val emotionSpans: List<String> = emptyList())
data class SemanticAnalysisV2(val workId: String, val topicName: String, val isNewTopic: Boolean,
    val viewpoints: List<SemanticViewpoint>, val emotionalGuidance: SemanticGuidance,
    val blindSpots: List<SemanticBlindSpot>, val dedupNewFactCount: Int, val dedupTotalFactCount: Int,
    val noveltyScore: Double, val processingMs: Int, val expressionSpans: SemanticExpressionSpans? = null) {
    val facts: List<SemanticEvidence> get() = viewpoints.flatMap { it.evidences }
        .distinctBy { it.matchedHistoryId?.let { id -> "history:$id" } ?: "new:${factKey(it.factPoint)}" }
    fun validFor(text: String): Boolean {
        val sanitized = com.emerge.app.capture.PagePrivacyGuard.sanitizeForCloud(text).first
        fun span(value: String) = value.isNotBlank() && value.length <= 1000 && (text.contains(value) || sanitized.contains(value))
        val evidence = viewpoints.flatMap { it.evidences }
        return workId.length in 1..256 && topicName.length in 1..80 && viewpoints.size <= 3 &&
            viewpoints.all { it.stanceSummary.length in 1..120 && it.authorAttitude in setOf("支持", "反驳", "中立") &&
                (it.stanceSpan.isEmpty() || span(it.stanceSpan)) && it.evidences.size <= 12 && it.evidences.all { fact ->
                    fact.factPoint.length in 1..1000 && span(fact.factSpan) &&
                        (fact.matchedHistoryId == null || fact.matchedHistoryId.length in 1..256) &&
                        (fact.matchedSourceNote == null || fact.matchedSourceNote.length in 1..500) &&
                        fact.isCoveredByHistory == (fact.matchedHistoryId != null)
                } } && evidence.groupBy { factKey(it.factPoint) }.values.all { versions ->
                    versions.map { it.isCoveredByHistory to it.matchedHistoryId }.distinct().size == 1
                } && dedupTotalFactCount == facts.size && dedupNewFactCount == facts.count { !it.isCoveredByHistory } &&
            noveltyScore.isFinite() && kotlin.math.abs(noveltyScore -
                if (facts.isEmpty()) 0.0 else dedupNewFactCount.toDouble() / facts.size) <= .0011 &&
            processingMs >= 0 && emotionalGuidance.let { guidance ->
                listOf(guidance.scopeExtrapolation, guidance.certaintyUpgrade, guidance.emotionAmplification).all { it.length <= 500 } &&
                    (guidance.factSpan.isEmpty() || span(guidance.factSpan)) &&
                    (guidance.conclusionSpan.isEmpty() || span(guidance.conclusionSpan)) &&
                    (!guidance.isOverExtrapolation || guidance.spanVerified && span(guidance.factSpan) && span(guidance.conclusionSpan) &&
                        guidance.emotionAmplification.isNotBlank() &&
                        (guidance.scopeExtrapolation.isNotBlank() || guidance.certaintyUpgrade.isNotBlank()))
            } && (expressionSpans?.let { it.factSpans.size <= 12 && it.emotionSpans.size <= 12 &&
                (it.factSpans + it.emotionSpans).all(::span) } != false) && blindSpots.size <= 4 && blindSpots.all {
                it.dimension.length in 1..80 && it.gapTip.length in 1..500 && it.sanitizedQuery.length in 1..200 &&
                    it.sanitizedQuery.none { char -> char.code < 32 }
            }
    }
}

internal fun factKey(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

/** Distribution of retained, model-annotated expressions; unannotated text is not classified as factual. */
fun expressionFractions(results: Collection<SemanticAnalysisV2>): Pair<Float, Float>? {
    var facts = 0
    var emotions = 0
    results.groupBy { it.workId }.values.map { it.last() }.forEach { result ->
        result.expressionSpans?.let { spans ->
            val emotional = spans.emotionSpans.distinct()
            facts += spans.factSpans.distinct().count { it !in emotional }
            emotions += emotional.size
        }
    }
    val total = facts + emotions
    return if (total == 0) null else facts.toFloat() / total to emotions.toFloat() / total
}

/** Wire and persisted V2 fields share a strict codec; model summaries are never used as original quotations. */
object SemanticV2Codec {
    fun decode(value: JSONObject): SemanticAnalysisV2 {
        fun JSONObject.text(key: String) = get(key) as? String ?: error("Invalid V2 text: $key")
        fun JSONObject.flag(key: String) = get(key) as? Boolean ?: error("Invalid V2 flag: $key")
        fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else text(key)
        fun JSONObject.number(key: String): Int {
            val number = get(key)
            require(number is Int || number is Long) { "Invalid V2 count: $key" }
            return (number as Number).toLong().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
        }
        fun rows(array: JSONArray, maximum: Int): List<JSONObject> {
            require(array.length() <= maximum)
            return (0 until array.length()).map(array::getJSONObject)
        }
        val guidance = value.getJSONObject("emotional_guidance")
        val score = value.get("novelty_score") as? Number ?: error("Invalid novelty")
        return SemanticAnalysisV2(value.text("work_id"), value.text("topic_name"), value.flag("is_new_topic"),
            rows(value.getJSONArray("viewpoints"), 3).map { viewpoint ->
                SemanticViewpoint(viewpoint.text("stance_summary"), viewpoint.text("author_attitude"),
                    viewpoint.optional("stance_span").orEmpty(), rows(viewpoint.getJSONArray("evidences"), 12).map {
                        SemanticEvidence(it.text("fact_point"), it.text("fact_span"), it.flag("is_covered_by_history"),
                            it.optional("matched_history_id"), it.optional("matched_source_note"))
                    })
            }, SemanticGuidance(guidance.flag("is_over_extrapolation"), guidance.text("fact_span"),
                guidance.text("conclusion_span"), guidance.text("scope_extrapolation"), guidance.text("certainty_upgrade"),
                guidance.text("emotion_amplification"), guidance.flag("span_verified")),
            rows(value.getJSONArray("blind_spots"), 4).map { SemanticBlindSpot(it.text("dimension"),
                it.text("gap_tip"), it.text("sanitized_query")) }, value.number("dedup_new_fact_count"),
            value.number("dedup_total_fact_count"), score.toDouble(), value.number("processing_ms"),
            value.optJSONObject("expression_spans")?.let { expressions ->
                fun spans(key: String): List<String> {
                    val array = expressions.getJSONArray(key)
                    require(array.length() <= 12)
                    return (0 until array.length()).map { array.get(it) as? String ?: error("Invalid expression span") }
                }
                SemanticExpressionSpans(spans("fact_spans"), spans("emotion_spans"))
            })
    }

    fun encode(value: SemanticAnalysisV2): JSONObject = JSONObject().apply {
        put("work_id", value.workId); put("topic_name", value.topicName); put("is_new_topic", value.isNewTopic)
        put("viewpoints", JSONArray().apply { value.viewpoints.forEach { viewpoint -> put(JSONObject().apply {
            put("stance_summary", viewpoint.stanceSummary); put("author_attitude", viewpoint.authorAttitude)
            put("stance_span", viewpoint.stanceSpan)
            put("evidences", JSONArray().apply { viewpoint.evidences.forEach { fact -> put(JSONObject().apply {
                put("fact_point", fact.factPoint); put("fact_span", fact.factSpan)
                put("is_covered_by_history", fact.isCoveredByHistory)
                put("matched_history_id", fact.matchedHistoryId ?: JSONObject.NULL)
                put("matched_source_note", fact.matchedSourceNote ?: JSONObject.NULL)
            }) } })
        }) } })
        put("emotional_guidance", JSONObject().apply { with(value.emotionalGuidance) {
            put("is_over_extrapolation", isOverExtrapolation); put("fact_span", factSpan); put("conclusion_span", conclusionSpan)
            put("scope_extrapolation", scopeExtrapolation); put("certainty_upgrade", certaintyUpgrade)
            put("emotion_amplification", emotionAmplification); put("span_verified", spanVerified)
        } })
        put("blind_spots", JSONArray().apply { value.blindSpots.forEach { put(JSONObject()
            .put("dimension", it.dimension).put("gap_tip", it.gapTip).put("sanitized_query", it.sanitizedQuery)) } })
        put("dedup_new_fact_count", value.dedupNewFactCount); put("dedup_total_fact_count", value.dedupTotalFactCount)
        put("novelty_score", value.noveltyScore); put("processing_ms", value.processingMs)
        value.expressionSpans?.let { put("expression_spans", JSONObject().put("fact_spans", JSONArray(it.factSpans))
            .put("emotion_spans", JSONArray(it.emotionSpans))) }
    }

    fun forEvent(value: JSONObject, event: BrowseEvent, analyzedAt: Long): EventSemantics {
        val decoded = decode(value)
        require(decoded.workId == (event.workKey ?: event.id.toString())) { "Analysis work mismatch" }
        // Stored V2 spans may refer to redacted source; legacy quotations must still exist in the original.
        require(decoded.validFor(event.visibleText)) { "Analysis evidence does not match retained text" }
        val claims = decoded.viewpoints.mapNotNull { viewpoint ->
            val quote = viewpoint.stanceSpan.ifBlank { viewpoint.evidences.firstOrNull()?.factSpan.orEmpty() }
            if (quote.isBlank() || !event.visibleText.contains(quote)) null else ArgumentUnit(quote.take(500), decoded.topicName.take(80),
                decoded.topicName.take(100), "观点", viewpoint.stanceSummary.take(200),
                viewpoint.evidences.firstOrNull { event.visibleText.contains(it.factSpan) }?.factSpan.orEmpty().take(500), uncertain = false)
        }
        return EventSemantics(event.id, event.visibleText, analyzedAt, claims, "secure_api_v2", decoded)
    }
}
