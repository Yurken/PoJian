package com.emerge.app.audioanalysis

import com.emerge.app.analysis.Increment
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioInsightsTest {
    private val archivedTime = Instant.parse("2020-01-15T12:00:00Z").toEpochMilli()
    private val original = "当前就业形势很差，因为招聘岗位同比减少20%"
    private fun segment(id: Long, session: String, time: Long, text: String = original) =
        TranscriptEvidence(id, session, text, time, 0, 8_000)

    @Test fun archivedAudioRemainsAnalyzableAndItsQuoteBelongsToTheSelectedTranscript() {
        val current = segment(41, "archived-audio", archivedTime)
        val report = LocalAudioInsights.analyze(listOf(current), emptyList())

        assertEquals(1, report.claims.size)
        val claim = report.claims.single()
        assertEquals(current.id, claim.segmentId)
        assertTrue(current.text.contains(claim.claim))
        assertTrue(current.text.contains(claim.evidence))
        assertNull(claim.historySegmentId)
        assertEquals(Increment.FIRST.label, claim.novelty)
    }

    @Test fun identicalNearbyAudioSegmentsRemainDistinctAndCanRevealReusedEvidence() {
        val first = segment(41, "audio-one", archivedTime)
        val second = segment(42, "audio-two", archivedTime + 5_000)
        val report = LocalAudioInsights.analyze(listOf(first, second), emptyList())

        assertEquals(listOf(41L, 42L), report.claims.map { it.segmentId })
        assertEquals(Increment.REPEATED.label, report.claims.last().novelty)
        assertEquals(41L, report.claims.last().historySegmentId)
        assertTrue(first.text.contains(report.claims.last().historyQuote!!))
    }

    @Test fun previousMaterialIsCitedWhileFutureAndSameSessionRowsCannotBecomeHistory() {
        val previous = segment(11, "earlier-audio", archivedTime - 86_400_000)
        val current = segment(41, "current-audio", archivedTime)
        val future = segment(61, "later-audio", archivedTime + 5_000)
        val sameSession = segment(12, "current-audio", archivedTime - 1_000)
        val report = LocalAudioInsights.analyze(listOf(current), listOf(future, previous, sameSession))

        val claim = report.claims.single()
        assertEquals(previous.id, claim.historySegmentId)
        assertEquals(Increment.REPEATED.label, claim.novelty)
        assertNotNull(claim.historyQuote)
        assertTrue(previous.text.contains(claim.historyQuote!!))
        assertTrue(current.text.contains(claim.evidence))
    }

    @Test fun changedEvidenceKeepsTheExactCurrentQuoteAndIsReportedAsNew() {
        val previous = segment(11, "earlier-audio", archivedTime - 300_000)
        val current = segment(41, "current-audio", archivedTime,
            "当前就业形势很差，因为招聘岗位同比减少40%")
        val report = LocalAudioInsights.analyze(listOf(current), listOf(previous))

        val claim = report.claims.single()
        assertEquals(Increment.NEW.label, claim.novelty)
        assertNull(claim.historySegmentId)
        assertNull(claim.historyQuote)
        assertEquals(previous.id, claim.comparedSegmentId)
        assertTrue(previous.text.contains(claim.comparedQuote!!))
        assertTrue(current.text.contains(claim.evidence))
        assertTrue(claim.evidence.contains("40%"))
        assertFalse(claim.evidence.contains("20%"))
    }

    @Test fun candidateRetrievalExcludesPresentFutureSameSessionAndDuplicateRows() {
        val current = segment(41, "current-audio", archivedTime)
        val previous = segment(11, "earlier-audio", archivedTime - 300_000)
        val other = segment(12, "older-audio", archivedTime - 600_000)
        val unrelated = segment(13, "music", archivedTime - 100_000, "今天的电影原声音乐很好听")
        val candidates = LocalAudioInsights.candidates(listOf(current), listOf(
            segment(61, "later-audio", archivedTime + 1),
            segment(62, "same-instant", archivedTime),
            segment(63, "current-audio", archivedTime - 100_000),
            current, previous, previous, other, unrelated), limit = 1)

        assertEquals(listOf(previous), candidates)
        assertTrue(LocalAudioInsights.candidates(emptyList(), listOf(previous)).isEmpty())
        assertTrue(LocalAudioInsights.candidates(listOf(current), listOf(previous), limit = 0).isEmpty())
    }

    @Test fun staleHistoryCannotOverrideTheSelectedTranscriptWithTheSameId() {
        val current = segment(41, "current-audio", archivedTime)
        val stale = segment(41, "different-session", archivedTime - 100_000,
            "这部电影的音乐很好，游戏过程也很有趣")

        val report = LocalAudioInsights.analyze(listOf(current), listOf(stale))
        assertEquals(current.id, report.claims.single().segmentId)
        assertTrue(current.text.contains(report.claims.single().evidence))
        assertNull(report.claims.single().historySegmentId)
    }
}
