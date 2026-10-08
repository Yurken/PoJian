package com.emerge.app.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emerge.app.audioanalysis.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioInsightContractTest {
    private val current = listOf(TranscriptEvidence(9, "now", "远程办公可以提高效率，调查中有60%的人表示认可。", 200, 100, 2000))
    private val history = listOf(TranscriptEvidence(2, "old", "远程办公可以提高效率，调查中有50%的人表示认可。", 100, 0, 3000))
    private fun response() = JSONObject("""{"summary":"相同结论，数据发生变化","model":"fixture","claims":[{"segmentId":9,"claim":"远程办公可以提高效率","evidence":"调查中有60%的人表示认可","relation":"支持","novelty":"新增数据","historySegmentId":2,"historyQuote":"调查中有50%的人表示认可"}]}""")

    @Test fun exactQuotesAndHistorySurvivePersistence() {
        val report = RemoteAudioInsights.validate(response(), current, history)
        assertEquals("调查中有60%的人表示认可", report.claims.single().evidence)
        assertEquals(report, AudioInsightCodec.decode(AudioInsightCodec.encode(report)))
    }
    @Test fun inventedCurrentQuoteIsRejected() {
        val json = response(); json.getJSONArray("claims").getJSONObject(0).put("evidence", "调查中有90%的人表示认可")
        assertTrue(runCatching { RemoteAudioInsights.validate(json, current, history) }.isFailure)
    }
    @Test fun unprovidedHistoricalSourceIsRejected() {
        val json = response(); json.getJSONArray("claims").getJSONObject(0).put("historySegmentId", 999)
        assertTrue(runCatching { RemoteAudioInsights.validate(json, current, history) }.isFailure)
    }
    @Test fun historyQuoteWithoutSourceIsRejected() {
        val json = response(); json.getJSONArray("claims").getJSONObject(0).put("historySegmentId", JSONObject.NULL)
        assertTrue(runCatching { RemoteAudioInsights.validate(json, current, history) }.isFailure)
    }
    @Test fun corruptedSavedReportDoesNotCrashRecordList() {
        assertNull(AudioInsightCodec.decode("broken json"))
    }
}
