package com.emerge.app.data

import com.emerge.app.analysis.ObservationWindow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ResearchProjectionTest {
    private fun fixture() = JSONObject(javaClass.getResource("/research-projection.json")!!.readText())
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun decode(root: JSONObject): SyntheticDataset = ResearchProjection.decode(
        root.getJSONObject("profile").getString("id"), root.getJSONObject("profile").getLong("now"),
        root.getJSONArray("contacts").objects(), root.getJSONArray("emotions").objects())

    @Test fun corpusRevisitKeepsTwoVisitsOneWorkAndOneEncounteredVersionPerVisit() {
        val data = decode(fixture())
        assertEquals(1, data.works.size)
        assertEquals(2, data.events.size)
        assertEquals(2, data.samples.size)
        assertEquals(2, data.semantics.size)
        assertTrue(data.events.all { it.sampleCount == 1 && it.parentEventId == null })
        assertTrue(data.semantics.all { it.inputKind == "synthetic_annotation" && it.validFor(data.events.single { e -> e.id == it.eventId }) })
        val window = ObservationWindow(data.events.minOf { it.timestamp }, data.now, ZoneId.of("Asia/Shanghai"))
        assertEquals(data.events.sumOf { it.durationMillis }, window.unionDuration(data.events))
        assertTrue(data.works.all { it.nativeId.isEmpty() && it.workKey.startsWith("synthetic:") })
        assertEquals(1, data.mediaTypes.size)
    }

    @Test fun anotherProfileCannotBeCombinedWithSelectedHistory() {
        val root = fixture()
        root.getJSONArray("contacts").getJSONObject(1).put("profile_id", "another-profile")
        assertThrows(IllegalArgumentException::class.java) { decode(root) }
    }

    @Test fun futureObservationOrCompletionCannotReplaceEncounteredVersion() {
        for (field in listOf("observed_at", "available_at")) {
            val root = fixture()
            val row = root.getJSONArray("contacts").getJSONObject(0)
            row.put(field, row.getLong("timestamp") + 1)
            assertThrows(IllegalArgumentException::class.java) { decode(root) }
        }
    }

    @Test fun duplicatedVisitIdAndUnrelatedAnnotationAreRejected() {
        val duplicate = fixture()
        val rows = duplicate.getJSONArray("contacts")
        rows.getJSONObject(1).put("row_id", rows.getJSONObject(0).getLong("row_id"))
        assertThrows(IllegalArgumentException::class.java) { decode(duplicate) }
        val unrelated = fixture()
        val row = unrelated.getJSONArray("contacts").getJSONObject(0)
        row.put("argument_json", JSONObject(row.getString("argument_json")).put("version_id", "future-version").toString())
        assertThrows(IllegalArgumentException::class.java) { decode(unrelated) }
    }

    @Test fun contentEmotionIsNotConvertedToViewerEmotion() {
        val root = fixture()
        val row = root.getJSONArray("emotions").getJSONObject(0)
        row.put("emotion_json", JSONObject(row.getString("emotion_json")).put("viewer_emotion", "焦虑").toString())
        assertThrows(IllegalArgumentException::class.java) { decode(root) }
    }

    @Test fun invalidVisibilityCannotBecomeWholeVisitDuration() {
        val root = fixture()
        val row = root.getJSONArray("contacts").getJSONObject(0)
        val contact = JSONObject(row.getString("contact_json"))
        val spans = contact.getJSONArray("visible_intervals")
        spans.put(spans.getJSONArray(0))
        row.put("contact_json", contact.toString())
        assertThrows(IllegalArgumentException::class.java) { decode(root) }
    }
}
