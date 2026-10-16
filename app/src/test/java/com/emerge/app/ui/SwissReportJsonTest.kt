package com.emerge.app.ui

import com.emerge.app.analysis.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwissReportJsonTest {
    @Test fun preservesWindowNullIndependenceAndQuotedText() {
        val report = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.HOUR, 1_792_000_000_000L)
        val quoted = "他说\"数据\"\n下一行"
        val claim = ClaimObservation(7, report.end, "app", "话题", quoted, listOf(quoted), "", false,
            Increment.REPEATED, 4, evidenceDetails = listOf(EvidenceFragment(quoted, EvidenceType.FACT,
                historyMatches = listOf(EvidenceHistoryMatch(4, quoted)))))
        val json = JSONObject(swissReportJson(report.copy(claims = listOf(claim), purpose = "比较行业资料")))
        assertEquals(report.start, json.getLong("start"))
        assertEquals(report.end, json.getLong("end"))
        assertEquals(report.period, json.getString("period"))
        assertEquals("比较行业资料", json.getString("purpose"))
        assertTrue(json.isNull("independentSources"))
        val value = json.getJSONArray("claims").getJSONObject(0)
        assertEquals(quoted, value.getString("quote"))
        assertEquals(4, value.getJSONArray("evidence").getJSONObject(0).getJSONArray("historyMatches").getJSONObject(0).getLong("eventId"))
    }
}
