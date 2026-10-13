package com.emerge.app.monitoring

import com.emerge.app.data.BrowseEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MonitorDeduplicationTest {
    private val event = BrowseEvent(id = 7, packageName = "reader", timestamp = 1000, eventType = 0,
        visibleText = "2022年冬奥会在北京举行。另一个事实需要单独核查。", workKey = "post:7")
    private fun request() = MonitorTaskRequest("event:7", "reader", "text", event.visibleText,
        1000, "screen", "visit", listOf(event), "service", "permission", true)
    private fun response(vararg verdicts: String) = JSONObject().put("id", "attempt:1")
        .put("claims", JSONArray().apply { verdicts.forEach { put(JSONObject().put("verdict", it)) } })
        .put("coverage", JSONObject().put("search_attempted", true))
    private fun finding(id: String = "attempt:1", quote: String = "冬奥会在北京举行") =
        MonitorFinding(id, "same-input", "reader", 1000, "fact", "insufficient", quote, "未取得足够证据")

    @Test fun automaticAndRepeatedManualClicksHaveOneDurableTaskIdentity() {
        val automatic = request()
        val attempts = listOf(automatic) + (1..10).map { index ->
            automatic.copy(requestId = "manual:${1000 + index}", capturedAt = 1000L + index,
                contextToken = "screen:$index", encounter = "visit:$index")
        }
        assertEquals(11, attempts.map { MonitorTaskIdentity.key(it, legacy = true) }.distinct().size)
        assertEquals(1, attempts.map { MonitorTaskIdentity.key(it) }.distinct().size)
    }

    @Test fun modeAuthorizationServiceMaterialAndDependenciesStaySeparate() {
        val original = request()
        val variants = listOf(original, original.copy(checkFacts = false), original.copy(kind = "audio"),
            original.copy(serviceFingerprint = "other"), original.copy(authorizationKey = "revoked"),
            original.copy(text = "2022年冬奥会不在北京举行"),
            original.copy(snapshots = listOf(event.copy(workKey = "other-work"))),
            original.copy(snapshots = listOf(event.copy(visibleText = event.visibleText + "新增条件"))),
            original.copy(snapshots = listOf(event.copy(id = 8))))
        assertEquals(variants.size, variants.map { MonitorTaskIdentity.key(it) }.distinct().size)
        assertEquals(MonitorTaskIdentity.key(original), MonitorTaskIdentity.key(original.copy(
            snapshots = listOf(event.copy(sampleCount = 20, durationMillis = 60_000, endTimestamp = 61_000)))))
    }

    @Test fun oneCompletedFactAndBudgetExcludedCandidatesDoNotRepeatNetworkRequests() {
        val cache = MonitorResponseCache()
        val result = response("supported", "not_checked", "not_checked")
        var sends = 0
        listOf(1000L, 2000L, 400_000L, 3_600_000L).forEach { at ->
            if (cache.get("same-work-and-input", at) == null) {
                sends++
                cache.put("same-work-and-input", result, at, checkFacts = true)
            }
        }
        assertEquals(1, sends)
        val reused = requireNotNull(cache.get("same-work-and-input", 3_600_001))
        assertEquals("not_checked", reused.getJSONArray("claims").getJSONObject(1).getString("verdict"))
        reused.put("id", "mutated")
        assertEquals("attempt:1", cache.get("same-work-and-input", 3_600_002)!!.getString("id"))
    }

    @Test fun actualFailuresDoNotBlockRetriesOrUpgradeAnalysisOnlyToFactChecks() {
        val cache = MonitorResponseCache()
        val failed = response("not_checked").apply {
            getJSONArray("claims").getJSONObject(0).put("check_error", JSONObject().put("retryable", true))
        }
        assertTrue(MonitorResponseCache.retryableFailure(failed))
        cache.put("facts", failed, 1000, true)
        assertNull(cache.get("facts", 1001))
        val unconfigured = response("not_checked").put("coverage", JSONObject().put("search_attempted", false))
        cache.put("facts", unconfigured, 1000, true)
        assertNull(cache.get("facts", 1001))
        cache.put("analysis", unconfigured, 1000, false)
        assertNotNull(cache.get("analysis", 1001))
        assertNull(cache.get("facts", 1001))
    }

    @Test fun cacheExpiresIsBoundedAndCanBeRevoked() {
        val cache = MonitorResponseCache()
        repeat(33) { cache.put("input:$it", response("opinion"), 1000, true) }
        assertNull(cache.get("input:0", 1001))
        assertNotNull(cache.get("input:32", 1001))
        assertNull(cache.get("input:32", 86_401_000))
        cache.put("input", response("opinion"), 1000, true)
        cache.clear()
        assertNull(cache.get("input", 1001))
    }

    @Test fun workClaimIdentitySurvivesRevisitsModalityAndCandidateOrder() {
        val scope = MonitorFactResults.scope("reader", listOf(event), "pixels:1")
        val revisit = event.copy(id = 8)
        val ocr = event.copy(id = 9, sourceType = "screen_ocr", parentEventId = 8)
        assertEquals(scope, MonitorFactResults.scope("reader", listOf(revisit, ocr), "pixels:2"))
        val first = finding(MonitorFactResults.id(scope, "冬奥会在北京举行", "2022年"))
        val next = first.copy(contentKey = "changed-frame", capturedAt = 2000, verdict = "supported")
        val result = MonitorFactResults.upsert(listOf(first), next)
        assertEquals(listOf(next), result)
        assertNotEquals(first.id, MonitorFactResults.id(scope, "冬奥会在北京举行", "2026年"))
        assertNotEquals(first.id, MonitorFactResults.id(scope, "冬奥会不在北京举行", "2022年"))
        assertNotEquals(scope, MonitorFactResults.scope("reader", listOf(event.copy(workKey = "post:8")), "pixels:1"))
    }

    @Test fun unknownWorksAndDifferentPlatformsAreNotForcedTogether() {
        val unknown = event.copy(workKey = null)
        assertNotEquals(MonitorFactResults.scope("reader", listOf(unknown), "input"),
            MonitorFactResults.scope("reader", listOf(unknown.copy(id = 8)), "input"))
        assertNotEquals(MonitorFactResults.scope("reader", listOf(event), "input"),
            MonitorFactResults.scope("another-reader", listOf(event), "input"))
    }

    @Test fun independentFactsInsideTheSameParagraphAreNotCollapsed() {
        val first = MonitorFactResults.id("work", "产品销量增长但利润下降", "2025年", "产品", "销量", "产品销量增长")
        val second = MonitorFactResults.id("work", "产品销量增长但利润下降", "2025年", "产品", "利润", "利润下降")
        assertNotEquals(first, second)
        assertEquals(2, MonitorFactResults.upsert(listOf(finding(first)), finding(second)).size)
    }

    @Test fun restoreRepairsOnlyExactLegacyDuplicatesKeepingTheLatestResult() {
        val old = finding()
        val newer = old.copy(id = "attempt:2", capturedAt = 2000)
        val distinct = finding("attempt:3", "冬奥会不在北京举行")
        assertEquals(listOf(newer, distinct), MonitorFactResults.restore(listOf(newer, old, distinct)))
        val anotherInput = old.copy(id = "attempt:4", contentKey = "another-input")
        assertEquals(2, MonitorFactResults.restore(listOf(old, anotherInput)).size)
        // Historical rows do not retain atomic check quotes or conditions; different results
        // on the same paragraph cannot safely be assumed to be duplicate checks.
        val anotherResult = old.copy(id = "attempt:5", verdict = "supported", explanation = "另一事实的证据")
        assertEquals(2, MonitorFactResults.restore(listOf(old, anotherResult)).size)
    }
}
