package com.emerge.app.data

import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.SemanticsCodec
import org.json.JSONArray
import org.json.JSONObject

/** Fictional authored annotations, kept out of capture, upload, and personal history. */
data class SyntheticEmotion(val workKey: String, val timestamp: Long, val holder: String,
    val expression: String, val cause: String, val attribution: String, val context: String)

data class SyntheticDataset(val now: Long, val appNames: Map<String, String>, val works: List<ObservedWork>,
    val events: List<BrowseEvent>, val samples: List<BrowseSample>, val semantics: List<EventSemantics>,
    val emotions: List<SyntheticEmotion>, val mediaTypes: Map<String, String> = emptyMap()) {
    companion object {
        fun decode(text: String): SyntheticDataset {
            val root = JSONObject(text)
            require(root.getBoolean("synthetic") && root.getInt("formatVersion") == 1)
            require(root.getInt("participantCount") == 0 && root.getJSONArray("media").length() == 0)
            val names = root.getJSONObject("appNames")
            val works = root.getJSONArray("works").objects { row ->
                ObservedWork(row.getString("workKey"), row.getString("packageName"), row.getString("title"),
                    row.getString("author"), row.getString("identityMethod"), row.getLong("firstSeenAt"),
                    row.getString("identityEvidence"), row.getString("nativeId")).also {
                    require(it.workKey.startsWith("synthetic:") && it.nativeId.isEmpty())
                }
            }
            val events = root.getJSONArray("events").objects { row ->
                BrowseEvent(id = row.getLong("id"), packageName = row.getString("packageName"),
                    timestamp = row.getLong("timestamp"), eventType = row.getInt("eventType"),
                    visibleText = row.getString("visibleText"), endTimestamp = row.getLong("endTimestamp"),
                    durationMillis = row.getLong("durationMillis"), sampleCount = row.getInt("sampleCount"),
                    sourceType = row.getString("sourceType"), workKey = row.getString("workKey"),
                    identityMethod = row.getString("identityMethod"),
                    visibilityIntervals = if (row.isNull("visibilityIntervals")) null else row.getString("visibilityIntervals"),
                    parentEventId = if (row.isNull("parentEventId")) null else row.getLong("parentEventId"))
            }
            val eventIndex = events.associateBy { it.id }
            require(eventIndex.size == events.size && works.map { it.workKey }.distinct().size == works.size)
            val workIndex = works.associateBy { it.workKey }
            require(events.all { workIndex[it.workKey]?.packageName == it.packageName &&
                (it.parentEventId == null || eventIndex[it.parentEventId]?.workKey == it.workKey) })
            val semantics = root.getJSONArray("semantics").objects { row ->
                requireNotNull(SemanticsCodec.decode(BrowseSemantic(row.getLong("eventId"), row.getString("inputText"),
                    row.getLong("analyzedAt"), row.getJSONArray("claims").toString(), row.getString("inputKind")))).also {
                    require(it.inputKind == "synthetic_annotation" && it.validFor(eventIndex.getValue(it.eventId)))
                }
            }
            val samples = root.getJSONArray("samples").objects { row ->
                BrowseSample(row.getLong("id"), row.getLong("visitId"), row.getLong("capturedAt"),
                    row.getInt("eventType"), row.getString("visibleText"))
            }
            val emotions = root.getJSONArray("emotions").objects { row ->
                SyntheticEmotion(row.getString("workKey"), row.getLong("timestamp"), row.getString("holder"),
                    row.getString("expression"), row.getString("cause"), row.getString("attribution"), row.getString("context"))
            }
            return SyntheticDataset(root.getLong("now"), names.keys().asSequence().associateWith(names::getString),
                works, events, samples, semantics, emotions)
        }

        private fun <T> JSONArray.objects(read: (JSONObject) -> T): List<T> = (0 until length()).map { read(getJSONObject(it)) }
    }
}
