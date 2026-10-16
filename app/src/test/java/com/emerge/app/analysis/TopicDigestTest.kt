package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TopicDigestTest {
    private val zone = ZoneId.of("UTC")
    private fun event(id: Long, work: String, text: String = "就业机会增加。岗位新增100个。") =
        BrowseEvent(id = id, packageName = "reader", timestamp = id * 1000, eventType = 32,
            visibleText = text, workKey = work)
    private fun claim(id: Long, conditions: String = "", quote: String = "就业机会增加。") =
        ClaimObservation(id, id * 1000, "reader", "就业", quote, listOf("岗位新增100个。"),
            conditions, false, Increment.NEW, null, subject = "招聘", dimension = "岗位数量",
            stance = "增加", analysisSource = "structured_model",
            evidenceDetails = listOf(EvidenceFragment("岗位新增100个。", EvidenceType.FACT, isNew = true)))

    @Test fun repeatedVisitsAndBoundMaterialsDoNotMultiplySupportingWorks() {
        val a = event(1, "a")
        val again = event(2, "a")
        val b = event(3, "b")
        val ocr = event(4, "a").copy(sourceType = "screen_ocr", parentEventId = 1)
        val digest = TopicDigests.build(listOf(claim(1), claim(2), claim(3), claim(4)),
            listOf(a, again, b, ocr), zone).single()
        assertEquals(2, digest.workCount)
        assertEquals(2, digest.viewpoints.single().workCount)
        assertEquals(setOf(1L, 2L, 3L, 4L), digest.viewpoints.single().observations.map { it.eventId }.toSet())
    }

    @Test fun changedConditionsAndOpposingStancesRemainSeparate() {
        val rows = listOf(event(1, "a"), event(2, "b"), event(3, "c", "就业机会减少。岗位新增100个。"))
        val claims = listOf(claim(1, "2025年"), claim(2, "2026年"),
            claim(3, "2026年", "就业机会减少。").copy(stance = "减少"))
        val digest = TopicDigests.build(claims, rows, zone).single()
        assertEquals(3, digest.viewpoints.size)
        assertEquals(setOf("2025年", "2026年"), digest.viewpoints.map { it.conditions }.toSet())
    }

    @Test fun deletedOrChangedOriginalCannotSupportASummary() {
        val invalid = event(1, "a", "天气晴朗")
        val valid = event(2, "b")
        val digest = TopicDigests.build(listOf(claim(1), claim(2), claim(3)), listOf(invalid, valid), zone).single()
        assertEquals(1, digest.workCount)
        assertEquals(listOf(2L), digest.viewpoints.single().observations.map { it.eventId })
    }

    @Test fun identicalEvidenceRetainsSourcesWithoutClaimingIndependence() {
        val rows = listOf(event(1, "a"), event(2, "b"))
        val repeated = claim(2).copy(increment = Increment.REPEATED, evidenceDetails = listOf(
            EvidenceFragment("岗位新增100个。", EvidenceType.FACT, historyMatches = listOf(
                EvidenceHistoryMatch(1, "岗位新增100个。")))))
        val evidence = TopicDigests.build(listOf(claim(1), repeated), rows, zone)
            .single().viewpoints.single().evidence.single()
        assertEquals(setOf(1L, 2L), evidence.eventIds.toSet())
        assertEquals(listOf(1L), evidence.historyMatches.map { it.eventId })
        assertTrue(evidence.hasNew)
    }

    @Test fun unboundAudioDoesNotBecomeABrowsedWork() {
        val audio = event(1, "session").copy(sourceType = "audio_playback")
        assertTrue(TopicDigests.build(listOf(claim(1)), listOf(audio), zone).isEmpty())
    }

    @Test fun oneOriginalSentenceCanSupportDifferentViewpointDimensions() {
        val quote = "就业机会增加，学历要求提高。"
        val row = event(1, "a", "$quote 岗位新增100个。")
        val quantity = claim(1, quote = quote)
        val requirement = quantity.copy(dimension = "学历要求", stance = "提高")
        val digest = TopicDigests.build(listOf(quantity, requirement, quantity), listOf(row), zone).single()
        assertEquals(1, digest.workCount)
        assertEquals(setOf("岗位数量", "学历要求"), digest.viewpoints.map { it.dimension }.toSet())
        assertEquals(2, digest.viewpoints.sumOf { it.observations.size })
    }

    @Test fun uncertainAndLocalClaimsKeepRelativeConditionsBoundToTheirObservationDates() {
        val quote = "今年就业机会增加。"
        val december = Instant.parse("2025-12-31T12:00:00Z").toEpochMilli()
        val january = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val rows = listOf(event(1, "a", "$quote 岗位新增100个。").copy(timestamp = december),
            event(2, "b", "$quote 岗位新增100个。").copy(timestamp = january))
        for ((source, uncertain) in listOf("local_rules" to false, "structured_model" to true)) {
            val claims = listOf(claim(1, "今年", quote).copy(timestamp = december),
                claim(2, "今年", quote).copy(timestamp = january)).map {
                    it.copy(analysisSource = source, uncertainAttribution = uncertain)
                }
            val digest = TopicDigests.build(claims, rows, zone).single()
            assertEquals(source, 2, digest.viewpoints.size)
            assertTrue(digest.viewpoints.all { it.workCount == 1 })
        }
    }
}
