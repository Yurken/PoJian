package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class EmotionExposureTest {
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
    private val end = start + 2 * 86_400_000L - 1
    private val text = "岗位正在减少，想到找不到工作就感到焦虑。庆幸家人愿意支持我。"
    private val signal = ImpactSignal(ImpactLevel.UNKNOWN, "尚未评估", "")
    private fun event(id: Long, at: Long = start + id * 1_000, key: String = "work-$id") =
        BrowseEvent(id, "reader", at, 0, text, workKey = key)
    private fun semantics(event: BrowseEvent, emotions: List<ContentEmotion>? = listOf(
        ContentEmotion(EmotionCategory.ANXIETY, ImpactLevel.HIGH, "想到找不到工作就感到焦虑", "岗位正在减少")),
        topic: String = "求职", analyzedAt: Long = 1): EventSemantics {
        val annotation = ClaimPresentation("求职", "求职压力与家庭支持", signal, signal, signal, listOf("比较岗位要求"), emotions)
        return EventSemantics(event.id, event.visibleText, analyzedAt, listOf(
            ArgumentUnit(text, topic, "岗位", "机会", "减少", presentation = annotation)))
    }
    private fun analyze(events: List<BrowseEvent>, semantics: List<EventSemantics>) =
        EmotionExposure.analyze(events, semantics, start, end, zone)

    @Test fun worksAreDeduplicatedAndMultilabelCountsShareOneKnownDenominator() {
        val one = event(1)
        val revisit = event(2, start + 86_400_000, "work-1")
        val unknown = event(3)
        val joy = ContentEmotion(EmotionCategory.JOY, ImpactLevel.LOW, "庆幸家人愿意支持我")
        val result = analyze(listOf(one, revisit, unknown), listOf(semantics(one).let {
            it.copy(claims = it.claims.map { claim -> claim.copy(presentation = claim.presentation!!.copy(
                emotions = claim.presentation.emotions!! + joy)) }) }, semantics(revisit), semantics(unknown, null)))
        assertEquals(2, result.totalWorks)
        assertEquals(1, result.knownWorks)
        assertEquals(1, result.unknownWorks)
        assertEquals(mapOf(EmotionCategory.ANXIETY to 1, EmotionCategory.JOY to 1), result.counts.associate { it.category to it.works })
        assertEquals(listOf(1L, 2L), result.works.single { it.emotions.isNotEmpty() }.eventIds)
        assertEquals(listOf("2026-10-15", "2026-10-16"), result.byDate.map { it.date })
        assertEquals(listOf(1, 1), result.byDate.map { it.knownWorks })
        assertEquals(1, result.byTopic.single().knownWorks)
    }

    @Test fun attachedOcrAndAudioSupplyEvidenceWithoutCreatingWorksOrUnknownDenominators() {
        val parent = event(1)
        val ocr = event(2, key = "work-1").copy(sourceType = "screen_ocr", parentEventId = 1)
        val audio = event(3, key = "work-1").copy(sourceType = "audio_playback", parentEventId = 1)
        val orphan = event(4).copy(sourceType = "screen_ocr", parentEventId = 99)
        val result = analyze(listOf(parent, ocr, audio, orphan), listOf(semantics(ocr), semantics(audio), semantics(orphan)))
        assertEquals(1, result.totalWorks)
        assertEquals(1, result.knownWorks)
        assertEquals(1, result.counts.single().works)
        assertEquals(listOf(2L, 3L), result.counts.single().eventIds)
        assertEquals(listOf(1L, 2L, 3L), result.works.single().eventIds)
    }

    @Test fun attachedMaterialWithAnotherWorkKeyCannotReclassifyTheParentWork() {
        val parent = event(1)
        for (source in listOf("screen_ocr", "visual_model", "audio_playback")) {
            val foreign = event(2, key = "another-work").copy(sourceType = source, parentEventId = parent.id)
            val result = analyze(listOf(parent, foreign), listOf(semantics(foreign)))
            assertEquals(source, 0, result.knownWorks)
            assertEquals(source, 1, result.unknownWorks)
            assertTrue(source, result.counts.isEmpty())
            assertEquals(listOf(parent.id), result.works.single().eventIds)
        }
    }

    @Test fun staleAnalysisOutOfWindowAndForeignParentCannotLeakIntoCurrentReport() {
        val old = event(1, start - 1)
        val current = event(2)
        val future = event(3, end + 1)
        val wrong = event(4).copy(sourceType = "visual_model", parentEventId = 2, packageName = "other")
        val oldChild = event(5, start - 1).copy(sourceType = "audio_playback", parentEventId = 2)
        val invalid = semantics(current).copy(inputText = "已经删除的原文")
        val result = analyze(listOf(old, current, future, wrong, oldChild), listOf(semantics(old), semantics(current),
            invalid.copy(analyzedAt = 2), semantics(future), semantics(wrong), semantics(oldChild)))
        assertEquals(1, result.totalWorks)
        assertEquals(0, result.knownWorks)
        assertEquals(1, result.unknownWorks)
        assertTrue(result.counts.isEmpty())
    }

    @Test fun sameWorkInDifferentTopicsUsesEachTopicWithoutMultiplyingOverallCount() {
        val one = event(1)
        val second = event(2, key = "work-1")
        val result = analyze(listOf(one, second), listOf(semantics(one), semantics(second, topic = "家庭")))
        assertEquals(1, result.knownWorks)
        assertEquals(setOf("求职", "家庭"), result.byTopic.map { it.topic }.toSet())
        assertTrue(result.byTopic.all { it.knownWorks == 1 && it.counts.single().works == 1 })
        assertEquals(mapOf(1L to "求职", 2L to "家庭"), result.works.single().emotions.associate { it.eventId to it.topic })
    }

    @Test fun dailyAndTopicStatsDoNotBorrowEmotionFromAnotherDayOrTopicOfTheSameWork() {
        val one = event(1)
        val second = event(2, start + 86_400_000, "work-1")
        val result = analyze(listOf(one, second), listOf(semantics(one), semantics(second, null, "家庭")))
        assertEquals(1, result.knownWorks)
        assertEquals(listOf(1, 0), result.byDate.map { it.knownWorks })
        assertEquals(listOf(0, 1), result.byDate.map { it.unknownWorks })
        assertEquals(0, result.byTopic.single { it.topic == "家庭" }.knownWorks)
        assertEquals(1, result.byTopic.single { it.topic == "家庭" }.unknownWorks)
    }

    @Test fun reportIntegrationUsesRequestedPeriodRatherThanAllHistory() {
        val old = event(1, start - 1)
        val current = event(2)
        val report = CognitiveAnalyzer().analyze(listOf(old, current), StatisticsPeriod.DAY, start + 60_000, zone,
            semantics = listOf(semantics(old), semantics(current)))
        assertEquals(1, report.emotionExposure.knownWorks)
        assertEquals(listOf(2L), report.emotionExposure.counts.single().eventIds)
    }
}
