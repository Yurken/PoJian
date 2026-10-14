package com.emerge.app.data

import com.emerge.app.analysis.SemanticsCodec
import com.emerge.app.capture.VisibilityInterval
import com.emerge.app.capture.VisibilityIntervals
import com.emerge.app.capture.VisibilityRegion
import org.json.JSONArray
import org.json.JSONObject

/** Pure adapter for developer evaluation and JVM regression. It never consumes increment gold or feedback. */
object ResearchProjection {
    fun decode(profileId: String, now: Long, contacts: List<JSONObject>, emotions: List<JSONObject>): SyntheticDataset {
        val works = linkedMapOf<String, ObservedWork>()
        val media = linkedMapOf<String, String>()
        val events = contacts.map { row ->
            require(row.getString("profile_id") == profileId)
            val contact = JSONObject(row.getString("contact_json"))
            val workId = row.getString("work_id")
            require(contact.getString("profile_id") == profileId && contact.getString("work_id") == workId)
            val timestamp = row.getLong("timestamp")
            require(contact.getLong("timestamp") == timestamp && timestamp <= now)
            require(row.getLong("observed_at") <= timestamp && row.getLong("available_at") <= timestamp)
            val text = row.getString("text")
            val key = "synthetic:$workId"
            val intervals = contact.getJSONArray("visible_intervals").let { list ->
                (0 until list.length()).map { index ->
                    val pair = list.getJSONArray(index)
                    require(pair.length() == 2)
                    val start = pair.getLong(0); val end = pair.getLong(1)
                    require(start >= timestamp && end <= now)
                    VisibilityInterval(start, end, end - start, VisibilityRegion.MAIN)
                }
            }
            require(intervals.isNotEmpty())
            val encoded = VisibilityIntervals.encode(intervals) // Reject overlap/corrupt spans, never infer full-day time.
            val packageName = row.getString("package_name")
            works.putIfAbsent(key, ObservedWork(key, packageName, text.lineSequence().first(),
                row.getString("author_id"), "synthetic_fixture", timestamp, "authored_work_identity", ""))
            media[key] = row.getString("media_type")
            BrowseEvent(id = row.getLong("row_id"), packageName = packageName, timestamp = timestamp,
                eventType = 0, visibleText = text, endTimestamp = intervals.last().end,
                durationMillis = VisibilityIntervals.duration(intervals), sampleCount = 1,
                sourceType = "accessibility", workKey = key, identityMethod = "synthetic_fixture", visibilityIntervals = encoded)
        }
        require(events.map { it.id }.distinct().size == events.size)
        val semantics = contacts.zip(events).map { (row, event) ->
            val claim = JSONObject(row.getString("argument_json"))
            val contact = JSONObject(row.getString("contact_json"))
            require(claim.getString("version_id") == contact.getString("version_id") &&
                claim.getString("work_id") == row.getString("work_id"))
            claim.put("evidence_origins", claim.getJSONArray("source_origins"))
            if (row.has("presentation_json") && !row.isNull("presentation_json")) {
                require(row.getString("presentation_version_id") == contact.getString("version_id") &&
                    row.getString("presentation_work_id") == row.getString("work_id") &&
                    row.getLong("presentation_available_at") <= event.timestamp &&
                    row.getString("presentation_provenance") == "AI_authored_rules_unreviewed")
                claim.put("presentation", JSONObject(row.getString("presentation_json")))
            }
            requireNotNull(SemanticsCodec.decode(BrowseSemantic(event.id, event.visibleText, event.timestamp,
                JSONArray().put(claim).toString(), "synthetic_annotation"))).also { require(it.validFor(event)) }
        }
        val contactKeys = contacts.map { Triple(it.getString("work_id"), it.getLong("timestamp"), it.getString("text")) }.toSet()
        val mappedEmotions = emotions.map { row ->
            require(row.getString("profile_id") == profileId)
            val text = row.getString("text")
            val workId = row.getString("work_id")
            val timestamp = row.getLong("timestamp")
            require(Triple(workId, timestamp, text) in contactKeys)
            val emotion = JSONObject(row.getString("emotion_json"))
            require(emotion.getString("work_id") == workId && emotion.isNull("viewer_emotion"))
            val span = emotion.getJSONArray("context_span")
            val context = text.substring(text.offsetByCodePoints(0, span.getInt(0)), text.offsetByCodePoints(0, span.getInt(1)))
            val holder = when (emotion.getString("holder")) {
                "author" -> "虚构作者"
                "quoted_person" -> "虚构被引述者"
                "commenter" -> "虚构评论者"
                else -> error("Unknown synthetic emotion holder")
            }
            SyntheticEmotion("synthetic:$workId", timestamp, holder, emotion.getString("expression"),
                emotion.getString("cause"), emotion.getString("attribution"), context)
        }
        // One encountered version per visit. Corpus observation versions are not extra visits/samples.
        val samples = events.map { BrowseSample(it.id, it.id, it.timestamp, it.eventType, it.visibleText) }
        return SyntheticDataset(now, mapOf("com.xingin.xhs" to "小红书", "com.ss.android.ugc.aweme" to "抖音",
            "com.smile.gifmaker" to "快手", "com.sina.weibo" to "微博", "com.tencent.mm" to "微信"),
            works.values.toList(), events, samples, semantics, mappedEmotions, media)
    }
}
