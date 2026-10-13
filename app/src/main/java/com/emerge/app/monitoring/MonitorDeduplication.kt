package com.emerge.app.monitoring

import com.emerge.app.data.BrowseEvent
import org.json.JSONArray
import org.json.JSONObject

/** Request timestamps identify attempts, not the material being checked. */
internal object MonitorTaskIdentity {
    fun fingerprint(event: BrowseEvent): String = MonitorPolicy.hash(JSONObject()
        .put("package", event.packageName).put("source", event.sourceType).put("text", event.visibleText)
        .put("timestamp", event.timestamp).put("audioSession", event.audioSessionId ?: JSONObject.NULL)
        .put("mediaStart", event.mediaStartMillis).put("mediaEnd", event.mediaEndMillis)
        .put("work", event.workKey ?: JSONObject.NULL).apply {
            event.parentEventId?.let { put("parentEventId", it) }
        }.toString())

    fun key(request: MonitorTaskRequest, legacy: Boolean = false): String = MonitorPolicy.hash(JSONObject()
        .apply { if (legacy) put("request", request.requestId) }
        .put("kind", request.kind).put("text", request.text)
        .put("service", request.serviceFingerprint).put("authorization", request.authorizationKey)
        .put("facts", request.checkFacts).put("inputs", JSONArray().apply {
            request.snapshots.sortedBy { it.id }.forEach {
                put(JSONObject().put("id", it.id).put("hash", fingerprint(it)))
            }
        }).toString())
}

/** Only validated responses enter this bounded cache. Failures never become successful checks. */
internal class MonitorResponseCache {
    private val entries = linkedMapOf<String, Pair<Long, String>>()

    @Synchronized fun get(key: String, now: Long): JSONObject? {
        entries.entries.removeAll { now - it.value.first !in 0 until TTL }
        return entries[key]?.second?.let(::JSONObject)
    }

    @Synchronized fun put(key: String, response: JSONObject, now: Long, checkFacts: Boolean) {
        if (!reusable(response, checkFacts)) return
        entries[key] = now to response.toString()
        while (entries.size > 32) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() = entries.clear()

    companion object {
        private const val TTL = 24 * 60 * 60 * 1000L

        fun retryableFailure(response: JSONObject): Boolean = response.getJSONArray("claims").let { claims ->
            (0 until claims.length()).any {
                val claim = claims.getJSONObject(it)
                claim.optString("verdict") == "not_checked" &&
                    claim.optJSONObject("check_error")?.optBoolean("retryable", false) == true
            }
        }

        fun reusable(response: JSONObject, checkFacts: Boolean): Boolean {
            if (!checkFacts) return true
            val claims = response.getJSONArray("claims")
            if ((0 until claims.length()).any { claims.getJSONObject(it).has("check_error") }) return false
            if ((0 until claims.length()).none { claims.getJSONObject(it).optString("verdict") == "not_checked" }) return true
            // The gateway checks at most one fact. Budget-excluded candidates must not cause
            // that completed check to be sent again. Missing retrieval/service failures do retry.
            val coverage = response.optJSONObject("coverage")
            return (coverage?.optBoolean("search_attempted", false) == true || coverage?.optBoolean("check_reused", false) == true) &&
                (0 until claims.length()).any { claims.getJSONObject(it).optString("verdict") in
                    setOf("supported", "contradicted", "conflicting", "insufficient") }
        }
    }
}

internal object MonitorFactResults {
    private fun normalized(value: String) = value.replace(Regex("\\s+"), " ").trim()

    fun scope(packageName: String, snapshots: List<BrowseEvent>, fallback: String): String {
        val owners = snapshots.map { event ->
            event.workKey?.takeIf(String::isNotBlank)?.let { "work:$it" }
                ?: "event:${event.parentEventId ?: event.id}"
        }.distinct().sorted()
        return MonitorPolicy.hash(packageName + "|" + owners.ifEmpty { listOf("input:$fallback") }.joinToString("|"))
    }

    fun id(scope: String, quote: String, conditions: String, subject: String = "", dimension: String = "",
        checkQuote: String = ""): String = "fact:" + MonitorPolicy.hash(JSONArray(listOf(
            scope, normalized(quote), normalized(conditions), normalized(subject), normalized(dimension),
            normalized(checkQuote))).toString())

    fun upsert(history: List<MonitorFinding>, finding: MonitorFinding): List<MonitorFinding> =
        (listOf(finding) + history.filterNot { old ->
            old.id == finding.id || finding.kind == "fact" && old.kind == "fact" &&
                old.packageName == finding.packageName && old.contentKey == finding.contentKey &&
                // Old releases used attempt IDs. Only collapse exact legacy duplicates.
                !old.id.startsWith("fact:") && normalized(old.claim) == normalized(finding.claim) &&
                old.verdict == finding.verdict && old.explanation == finding.explanation &&
                old.topic == finding.topic && old.sources == finding.sources
        }).take(80)

    fun restore(history: List<MonitorFinding>): List<MonitorFinding> =
        history.asReversed().fold(emptyList()) { retained, finding -> upsert(retained, finding) }
}
