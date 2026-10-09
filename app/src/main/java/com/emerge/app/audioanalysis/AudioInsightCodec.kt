package com.emerge.app.audioanalysis

import org.json.JSONArray
import org.json.JSONObject

object AudioInsightCodec {
    fun encode(report: AudioInsightReport): String = JSONObject().apply {
        put("summary", report.summary); put("method", report.method); put("generatedAt", report.generatedAt)
        put("usage", report.usage)
        put("claims", JSONArray().apply { report.claims.forEach { claim -> put(JSONObject().apply {
            put("claim", claim.claim); put("evidence", claim.evidence); put("segmentId", claim.segmentId)
            put("relation", claim.relation); put("novelty", claim.novelty)
            put("historySegmentId", claim.historySegmentId); put("historyQuote", claim.historyQuote)
            put("comparedSegmentId", claim.comparedSegmentId); put("comparedQuote", claim.comparedQuote)
        }) } })
    }.toString()

    fun decode(value: String?): AudioInsightReport? = value?.let { runCatching {
        val json = JSONObject(it)
        val claims = json.optJSONArray("claims") ?: JSONArray()
        AudioInsightReport(json.getString("summary"), json.getString("method"), (0 until claims.length()).map { index ->
            val row = claims.getJSONObject(index)
            AudioClaimInsight(row.getString("claim"), row.getString("evidence"), row.getLong("segmentId"),
                row.getString("relation"), row.getString("novelty"),
                if (row.isNull("historySegmentId")) null else row.getLong("historySegmentId"),
                if (row.isNull("historyQuote")) null else row.getString("historyQuote"),
                if (row.isNull("comparedSegmentId")) null else row.getLong("comparedSegmentId"),
                if (row.isNull("comparedQuote")) null else row.getString("comparedQuote"))
        }, json.getLong("generatedAt"), if (json.isNull("usage")) null else json.getString("usage"))
    }.getOrNull() }
}
