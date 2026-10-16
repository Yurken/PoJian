package com.emerge.app.monitoring

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class EmotionAlertPolicyTest {
    private val text = "找不到工作让我非常焦虑，担心生活失去保障。"
    private val quote = "找不到工作让我非常焦虑"
    private val start = 1_000_000L
    private data class Fixture(val summary: EmotionExposureSummary, val events: List<BrowseEvent>)
    private fun fixture(count: Int = 8, minutes: Long = 5, known: Int = count, high: Int = known,
        category: EmotionCategory = EmotionCategory.ANXIETY): Fixture {
        val events = List(count) { index -> BrowseEvent(index + 1L, "reader", start + index * minutes * 60_000,
            32, text, workKey = "post-$index") }
        val works = events.mapIndexed { index, event -> EmotionWorkExposure(BrowseWorkKey.of(event), listOf(event.id),
            listOf("2026-10-16"), listOf("求职"), if (index < known) listOf(EmotionEvidence(event.id, category,
                if (index < high) ImpactLevel.HIGH else ImpactLevel.LOW, quote)) else emptyList()) }
        return Fixture(EmotionExposureSummary(known, count - known, works = works), events)
    }
    private fun assess(sample: Fixture) = EmotionAlertPolicy.assess(sample.summary, sample.events)

    @Test fun sustainedHighContentHasTraceableCountsAndOriginalEvidence() {
        val sample = fixture()
        val signals = assess(sample)
        assertEquals(1, signals.size)
        val result = signals.single()
        assertEquals("求职", result.topic)
        assertEquals(EmotionCategory.ANXIETY, result.category)
        assertEquals(8, result.known)
        assertEquals(8, result.high)
        assertEquals(0, result.unknown)
        assertEquals(8, result.workCount)
        assertEquals((1L..8L).toList(), result.eventIds)
        assertEquals(listOf(quote), result.evidence.map { it.evidence }.distinct())
        assertEquals(start, result.earliest)
        assertEquals(start + 35 * 60_000, result.latest)
    }

    @Test fun onlyFourNegativeCategoriesCanCreateAlerts() {
        for (category in listOf(EmotionCategory.ANXIETY, EmotionCategory.ANGER, EmotionCategory.FEAR, EmotionCategory.SADNESS))
            assertEquals(category.name, 1, assess(fixture(category = category)).size)
        for (category in listOf(EmotionCategory.JOY, EmotionCategory.NEUTRAL))
            assertTrue(category.name, assess(fixture(category = category)).isEmpty())
    }

    @Test fun tooFewKnownWorksOrInsufficientCoverageCannotTrigger() {
        assertTrue(assess(fixture(count = 7)).isEmpty())
        assertTrue(assess(fixture(count = 14, known = 8)).isEmpty())
        assertEquals(1, assess(fixture(count = 15, known = 9)).size)
    }

    @Test fun highShareUsesAllKnownWorksAndAcceptsTheExactBoundary() {
        assertTrue(assess(fixture(count = 10, high = 5)).isEmpty())
        assertEquals(1, assess(fixture(count = 10, minutes = 6, high = 6)).size)
    }

    @Test fun missingIntensityCannotBecomeLowOrNeutralEvidence() {
        val sample = fixture()
        val unknown = sample.summary.copy(works = sample.summary.works.map { work -> work.copy(emotions =
            work.emotions.map { it.copy(level = ImpactLevel.UNKNOWN) }) })
        assertTrue(EmotionAlertPolicy.assess(unknown, sample.events).isEmpty())
    }

    @Test fun aShortBurstOrLateResamplingOfOneWorkCannotManufactureDuration() {
        assertTrue(assess(fixture(minutes = 4)).isEmpty())
        val sample = fixture(minutes = 1)
        val revisit = sample.events.first().copy(id = 99, timestamp = start + 60 * 60_000)
        val first = sample.summary.works.first()
        val duplicated = first.copy(eventIds = first.eventIds + 99L,
            emotions = first.emotions + first.emotions.single().copy(eventId = 99))
        assertTrue(EmotionAlertPolicy.assess(sample.summary.copy(works = listOf(duplicated) + sample.summary.works.drop(1)),
            sample.events + revisit).isEmpty())
    }

    @Test fun duplicateSummaryRowsAndSameWorkSamplesDoNotMultiplySupport() {
        val sample = fixture(count = 1)
        assertTrue(EmotionAlertPolicy.assess(sample.summary.copy(knownWorks = 8,
            works = List(8) { sample.summary.works.single() }), sample.events).isEmpty())
    }

    @Test fun deletedOrChangedOriginalEvidenceCannotSupplyTheEighthKnownWork() {
        val sample = fixture()
        assertTrue(EmotionAlertPolicy.assess(sample.summary, sample.events.dropLast(1)).isEmpty())
        assertTrue(EmotionAlertPolicy.assess(sample.summary, sample.events.dropLast(1) +
            sample.events.last().copy(visibleText = "原文已更正")).isEmpty())
    }

    @Test fun currentContentMustMatchTheSameCategoryIntensityAndRetainedQuote() {
        val signals = assess(fixture())
        assertEquals(1, signals.size)
        val signal = signals.single()
        val current = ContentEmotion(EmotionCategory.ANXIETY, ImpactLevel.HIGH, quote)
        assertTrue(signal.matchesCurrent(8L, current))
        assertFalse(signal.matchesCurrent(99L, current))
        assertFalse(signal.matchesCurrent(8L, current.copy(level = ImpactLevel.LOW)))
        assertFalse(signal.matchesCurrent(8L, current.copy(category = EmotionCategory.ANGER)))
        assertFalse(signal.matchesCurrent(8L, current.copy(evidence = "别的原句")))
        assertFalse(signal.matchesCurrent(8L, current, "家庭"))
    }

    @Test fun emotionEvidenceIsNotBorrowedByAnotherTopicOfTheSameWorks() {
        val sample = fixture()
        val summary = sample.summary.copy(works = sample.summary.works.map { work -> work.copy(
            topics = listOf("求职", "家庭"), emotions = work.emotions.map { it.copy(topic = "求职") }) })
        val signals = EmotionAlertPolicy.assess(summary, sample.events)
        assertEquals(1, signals.size)
        assertEquals("求职", signals.single().topic)
    }

    @Test fun attachedEvidenceRequiresARetainedParentWithMatchingWorkIdentity() {
        val sample = fixture()
        val children = sample.events.map { event -> event.copy(id = event.id + 100,
            sourceType = "screen_ocr", parentEventId = event.id) }
        val summary = sample.summary.copy(works = sample.summary.works.map { work -> work.copy(
            eventIds = work.eventIds + work.eventIds.map { it + 100 }, emotions = work.emotions.map { it.copy(eventId = it.eventId + 100) }) })
        assertEquals(1, EmotionAlertPolicy.assess(summary, sample.events + children).size)
        assertTrue(EmotionAlertPolicy.assess(summary, sample.events + children.map { it.copy(workKey = "foreign") }).isEmpty())
        assertTrue(EmotionAlertPolicy.assess(summary, children).isEmpty())
    }
}
