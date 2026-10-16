package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class EmotionTimelineTest {
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
    private val now = start + 24 * 3_600_000L
    private val text = "岗位数量减少了。报告记录减少20%。想到求职就焦虑。家人支持让我喜悦。"
    private val signal = ImpactSignal(ImpactLevel.UNKNOWN, "未评估", "")
    private fun event(id: Long, at: Long = start + id * 60_000, duration: Long = 60_000,
        key: String = "work-$id") = BrowseEvent(id, "reader", at, 0, text, at + duration, duration, 1, workKey = key)
    private fun semantic(event: BrowseEvent, category: EmotionCategory? = EmotionCategory.ANXIETY,
        evidence: String = "", topic: String = "求职", uncertain: Boolean = false): EventSemantics {
        val emotion = category?.let { ContentEmotion(it, ImpactLevel.MEDIUM,
            when (it) {
                EmotionCategory.ANXIETY -> "想到求职就焦虑"
                EmotionCategory.JOY -> "家人支持让我喜悦"
                else -> "岗位数量减少了"
            }, if (it == EmotionCategory.ANXIETY) "岗位数量减少了" else null) }
        return EventSemantics(event.id, event.visibleText, 1, listOf(ArgumentUnit("岗位数量减少了", topic,
            "岗位", "数量", "减少", evidence, uncertain = uncertain,
            presentation = ClaimPresentation("求职", "岗位减少与求职感受", signal, signal, signal,
                listOf("比较岗位要求"), emotion?.let(::listOf)))))
    }
    private fun analyze(events: List<BrowseEvent>, semantics: List<EventSemantics>) =
        EmotionTimeline.analyze(events, semantics, now, zone)

    @Test fun threeColorsRequireSupportedEmotionAndEvidenceAndDoNotAbsorbJoyOrUnknown() {
        val events = (1L..5L).map(::event)
        val rows = listOf(semantic(events[0]), semantic(events[1], EmotionCategory.NEUTRAL, "报告记录减少20%"),
            semantic(events[2], EmotionCategory.NEUTRAL), semantic(events[3], EmotionCategory.JOY), semantic(events[4], null))
        assertTrue(rows.zip(events).all { (row, event) -> row.validFor(event) })
        val hour = analyze(events, rows).first()
        assertEquals(EmotionTone.entries.toSet(), hour.posts.map { it.tone }.toSet())
        assertEquals(300_000L, hour.durationMillis)
        assertTrue(hour.durations.values.all { it == 60_000L })
        assertEquals("想到求职就焦虑", hour.posts.single { it.tone == EmotionTone.NEGATIVE }.emotions.single().evidence)
        assertEquals("岗位数量减少了", hour.posts.single { it.tone == EmotionTone.NEGATIVE }.emotions.single().trigger)
    }

    @Test fun measuredTimeSplitsAtHoursAndOverlapsNeverMultiplyBrowsingTime() {
        val first = event(1, start + 3_540_000, 120_000)
        val overlap = event(2, start + 3_570_000, 60_000)
        val events = listOf(first, overlap)
        val hours = analyze(events, events.map { semantic(it) })
        assertEquals(60_000L, hours[0].durationMillis)
        assertEquals(60_000L, hours[1].durationMillis)
        assertEquals(ObservationWindow(start, now, zone).unionDuration(events), hours.sumOf { it.durationMillis })
    }

    @Test fun revisitCountsOneWorkWhileUnknownDurationStaysUnknown() {
        val first = event(1)
        val revisit = event(2, key = first.workKey!!)
        val unknown = event(3).copy(sampleCount = 0)
        val result = analyze(listOf(first, revisit, unknown), listOf(semantic(first), semantic(revisit))).first()
        assertEquals(2, result.workCount)
        assertEquals(2, result.posts.size)
        assertEquals(120_000L, result.durationMillis)
        assertEquals(1, result.unknownDurationWorks)
    }

    @Test fun validAttachmentsSupplyEvidenceWithoutDurationAndForeignOrFutureOnesDoNot() {
        val parent = event(1)
        val child = event(2, start + 75_000, 20_000, parent.workKey!!).copy(sourceType = "screen_ocr", parentEventId = 1)
        val foreign = child.copy(id = 3, workKey = "other")
        val future = child.copy(id = 4, timestamp = now + 1, endTimestamp = now + 20_001)
        val valid = analyze(listOf(parent, child), listOf(semantic(child))).first()
        assertEquals(60_000L, valid.durationMillis)
        assertEquals(1, valid.workCount)
        assertEquals(EmotionTone.NEGATIVE, valid.posts.single().tone)
        assertEquals(parent.id, valid.posts.single().event.id)
        assertEquals(child.id, valid.posts.single().emotions.single().eventId)
        val invalid = analyze(listOf(parent, foreign, future), listOf(semantic(foreign), semantic(future))).first()
        assertEquals(EmotionTone.UNKNOWN, invalid.posts.single().tone)
    }

    @Test fun staleLatestResultNeverFallsBackAndLaterHourCannotReclassifyEarlierHour() {
        val early = event(1)
        val late = event(2, start + 3_660_000, key = early.workKey!!)
        val old = semantic(early)
        val stale = old.copy(inputText = "不再保留的内容", analyzedAt = 2)
        val hours = analyze(listOf(early, late), listOf(old, stale, semantic(late)))
        assertEquals(EmotionTone.UNKNOWN, hours[0].posts.single().tone)
        assertEquals(EmotionTone.NEGATIVE, hours[1].posts.single().tone)
    }

    @Test fun topicEvidenceIsNotBorrowedFromAnotherTopic() {
        val event = event(1)
        val negative = semantic(event)
        val neutral = semantic(event, EmotionCategory.NEUTRAL, topic = "家庭")
        val hour = analyze(listOf(event), listOf(negative.copy(claims = negative.claims + neutral.claims))).first()
        assertEquals(1, hour.workCount)
        assertEquals(EmotionTone.NEGATIVE, hour.posts.single { it.topic == "求职" }.tone)
        assertEquals(EmotionTone.NEUTRAL, hour.posts.single { it.topic == "家庭" }.tone)
        assertTrue(hour.posts.single { it.topic == "家庭" }.emotions.all { it.category == EmotionCategory.NEUTRAL })
    }

    @Test fun clippingAndProportionalLegacySessionsConserveMeasuredTime() {
        val before = event(1, start - 60_000, 120_000).copy(durationMillis = 60_000)
        val future = event(2, now - 60_000, 120_000).copy(durationMillis = 60_000)
        val hours = analyze(listOf(before, future), listOf(semantic(before), semantic(future)))
        assertEquals(24, hours.size)
        assertEquals(30_000L, hours.first().durationMillis)
        assertEquals(30_000L, hours.last().durationMillis)
        assertEquals(60_000L, hours.sumOf { it.durationMillis })
    }
}
