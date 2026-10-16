package com.emerge.app.ui

import com.emerge.app.analysis.CognitiveReport
import org.json.JSONArray
import org.json.JSONObject

/** A versioned presentation export; no inferred independence or security guarantees. */
internal fun swissReportJson(report: CognitiveReport): String = JSONObject().apply {
    put("schemaVersion", 1)
    put("generatedAt", report.generatedAt); put("start", report.start); put("end", report.end)
    put("period", report.period); put("zone", report.zone); put("method", report.method)
    put("purpose", report.purpose)
    put("rawRecords", report.rawRecords); put("observedDays", report.observedDays)
    put("observedDurationMillis", report.observedDurationMillis)
    put("unknownDurationRecords", report.unknownDurationRecords)
    put("independentSources", JSONObject.NULL)
    put("topics", JSONArray().apply { report.topics.forEach { put(JSONObject().put("topic", it.topic).put("count", it.count)) } })
    put("claims", JSONArray().apply { report.claims.forEach { claim -> put(JSONObject().apply {
        put("eventId", claim.eventId); put("timestamp", claim.timestamp); put("packageName", claim.packageName)
        put("topic", claim.topic); put("quote", claim.quote); put("subject", claim.subject)
        put("dimension", claim.dimension); put("stance", claim.stance); put("conditions", claim.conditions)
        put("uncertainAttribution", claim.uncertainAttribution); put("increment", claim.increment.name)
        put("analysisSource", claim.analysisSource)
        put("evidenceQuotes", JSONArray(claim.evidence))
        put("evidence", JSONArray().apply { claim.evidenceDetails.forEach { evidence -> put(JSONObject().apply {
            put("text", evidence.text); put("type", evidence.type.name); put("isNew", evidence.isNew)
            put("identityUnresolved", evidence.identityUnresolved)
            put("historyMatches", JSONArray().apply { evidence.historyMatches.forEach { match ->
                put(JSONObject().put("eventId", match.eventId).put("quote", match.quote))
            } })
        }) } })
    }) } })
    put("emotions", JSONArray().apply { report.emotionExposure.works.forEach { work -> put(JSONObject().apply {
        put("workIdentity", work.workIdentity); put("eventIds", JSONArray(work.eventIds))
        put("emotions", JSONArray().apply { work.emotions.forEach { emotion -> put(JSONObject().apply {
            put("category", emotion.category.name); put("level", emotion.level.name)
            put("evidence", emotion.evidence); put("trigger", emotion.trigger ?: JSONObject.NULL)
            put("eventId", emotion.eventId)
        }) } })
    }) } })
}.toString(2)
