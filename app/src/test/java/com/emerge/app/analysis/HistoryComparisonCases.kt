package com.emerge.app.analysis

import com.emerge.app.audioanalysis.LocalAudioInsights
import com.emerge.app.audioanalysis.TranscriptEvidence
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

/** Deterministic engineering fixtures, not accuracy measurements or user browsing data. */
object HistoryComparisonCases {
    data class Case(val name: String, val verify: () -> Unit) {
        override fun toString() = name
    }
    private val base = Instant.parse("2026-10-08T03:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private const val claim = "程序员求职竞争越来越激烈"
    private const val factA = "根据调查数据，报名人数达到120人"
    private const val factB = "根据统计数据，录取名额只有20个"
    private const val advice = "建议优先核对岗位技能要求"

    private fun event(id: Long, vararg evidence: String, text: String = claim,
        timestamp: Long = base + id * 60_000) = BrowseEvent(id, "fixture.app", timestamp, 0,
        (listOf(text) + evidence).joinToString("\n"), sourceType = "audio_transcript")
    private fun report(events: List<BrowseEvent>, analyzer: CognitiveAnalyzer = CognitiveAnalyzer()) =
        analyzer.analyze(events, StatisticsPeriod.WEEK, events.maxOf { it.timestamp } + 1, zone)
    private fun current(events: List<BrowseEvent>, id: Long = events.last().id,
        analyzer: CognitiveAnalyzer = CognitiveAnalyzer()) = report(events, analyzer).claims.single { it.eventId == id }
    private fun unionEvents() = listOf(event(1, factA), event(2, factB), event(3, factA, factB))
    private fun audio(event: BrowseEvent) = TranscriptEvidence(event.id, "session-${event.id}",
        event.visibleText, event.timestamp, 0, 1_000)
    private fun references(observation: ClaimObservation) = observation.evidenceDetails
        .flatMap { it.historyMatches }.map { it.eventId }.toSet()

    fun cases(): List<Case> = listOf(
        Case("multiple earlier records jointly cover current evidence") {
            val result = current(unionEvents())
            check(result.increment == Increment.REPEATED)
            check(result.evidenceDetails.none { it.isNew })
        },
        Case("each covered fragment retains its own exact historical quote") {
            val result = current(unionEvents())
            check(result.evidenceDetails.single { it.text == factA }.historyMatches == listOf(EvidenceHistoryMatch(1, factA)))
            check(result.evidenceDetails.single { it.text == factB }.historyMatches == listOf(EvidenceHistoryMatch(2, factB)))
            check(result.comparedHistoryEventIds == listOf(2L, 1L))
        },
        Case("mixed old and new evidence flags only the new fragment") {
            val result = current(listOf(event(1, factA), event(2, factA, advice)))
            check(result.increment == Increment.NEW)
            check(result.evidenceDetails.filter { it.isNew }.map { it.text } == listOf(advice))
            check(result.evidenceDetails.single { it.text == advice }.historyMatches.isEmpty())
        },
        Case("an empty latest record does not hide older supporting evidence") {
            check(current(listOf(event(1, factA), event(2), event(3, factA))).increment == Increment.REPEATED)
        },
        Case("missing evidence remains unknown rather than repeated") {
            check(current(listOf(event(1), event(2))).increment == Increment.UNKNOWN)
        },
        Case("unresolved subject is unknown rather than first occurrence") {
            check(current(listOf(event(1, factA, text = "可能程序员求职竞争继续加剧"))).increment == Increment.UNKNOWN)
        },
        Case("reported or quoted speech is not assigned a definite position") {
            check(current(listOf(event(1, factA, text = "有人说程序员求职竞争越来越激烈"))).increment == Increment.UNKNOWN)
        },
        Case("simultaneous events cannot serve as each other's history") {
            val events = listOf(event(1, factA), event(2, factA, timestamp = base + 60_000))
            check(report(events).claims.all { it.increment == Increment.FIRST && it.previousEventId == null })
        },
        Case("future evidence cannot change an earlier event") {
            val events = listOf(event(2, factA, factB), event(1, factA))
            check(current(events, 1).increment == Increment.FIRST)
            check(current(events, 1).comparedHistoryEventIds.isEmpty())
        },
        Case("late arrival is recomputed in observation time order") {
            check(report(unionEvents()).claims == report(unionEvents().reversed()).claims)
        },
        Case("same-event candidate claims are not historical evidence") {
            val event = event(1, factA, "$claim，仍然需要认真准备", factA)
            check(report(listOf(event)).claims.all { it.previousEventId == null })
        },
        Case("different regions stay separate") {
            val result = current(listOf(event(1, factA, text = "北京$claim"), event(2, factA, text = "上海$claim")))
            check(result.increment == Increment.CONDITIONS)
            check(references(result).isEmpty())
        },
        Case("intervening different scope does not hide a valid older match") {
            val events = listOf(event(1, factA, text = "北京$claim"), event(2, factB, text = "上海$claim"),
                event(3, factA, text = "北京$claim"))
            check(current(events).increment == Increment.REPEATED)
            check(references(current(events)) == setOf(1L))
        },
        Case("unrelated same-topic records cannot crowd out history") {
            val unrelated = (2L..132L).map { event(it, factB, text = "工程${it}岗位竞争越来越激烈") }
            val result = current(listOf(event(1, factA)) + unrelated + event(133, factA))
            check(result.increment == Increment.REPEATED)
            check(result.comparedHistoryEventIds == listOf(1L))
        },
        Case("truncated unmatched history is unknown rather than new") {
            val events = listOf(event(1, factA)) + (2L..122L).map { event(it, factB) } + event(123, factA)
            val result = current(events)
            check(result.historyComparisonTruncated)
            check(result.comparedHistoryEventIds.size == 120)
            check(result.increment == Increment.UNKNOWN)
            check(result.evidenceDetails.none { it.isNew })
        },
        Case("fully covered evidence remains covered with bounded provenance") {
            val result = current((1L..125L).map { event(it, factA) })
            check(result.historyComparisonTruncated)
            check(result.increment == Increment.REPEATED)
            check(result.evidenceDetails.single().historyMatches.size == 3)
        },
        Case("deleting history removes dependencies and recomputes novelty") {
            val analyzer = CognitiveAnalyzer()
            check(current(unionEvents(), analyzer = analyzer).increment == Increment.REPEATED)
            val result = current(unionEvents().drop(1), analyzer = analyzer)
            check(result.increment == Increment.NEW)
            check(1L !in references(result) && 1L !in result.comparedHistoryEventIds)
        },
        Case("editing history cannot reuse its old evidence match") {
            val analyzer = CognitiveAnalyzer()
            current(unionEvents(), analyzer = analyzer)
            val result = current(listOf(event(1, advice), event(2, factB), event(3, factA, factB)), analyzer = analyzer)
            check(result.increment == Increment.NEW)
            check(result.evidenceDetails.single { it.text == factA }.historyMatches.isEmpty())
        },
        Case("negation changes are not normalized into repeated evidence") {
            val a = "根据调查数据，在相同统计口径和条件下企业提供稳定的岗位录用机会"
            val b = "根据调查数据，在相同统计口径和条件下企业未提供稳定的岗位录用机会"
            check(current(listOf(event(1, a), event(2, b))).increment == Increment.NEW)
        },
        Case("signed numerical changes are not normalized away") {
            val text = "根据调查数据，在相同统计口径下企业整体岗位录用数量变化为"
            check(current(listOf(event(1, text + "10%"), event(2, text + "-10%"))).increment == Increment.NEW)
        },
        Case("inequality changes are not normalized away") {
            val text = "根据调查数据，在相同统计口径下企业整体岗位录用比例为"
            check(current(listOf(event(1, text + "<10%"), event(2, text + ">10%"))).increment == Increment.NEW)
        },
        Case("other claim groups cannot supply the dominant group's repeat numerator") {
            val otherClaim = "设计师求职竞争越来越激烈"
            val earlier = event(90, factA, text = otherClaim, timestamp = base - 10 * 86_400_000L)
            val dominant = (1L..7L).map { event(it, when(it) { 1L -> factA; 2L -> factB; else -> advice },
                timestamp = base + it * 43_200_000) }
            val minority = (8L..10L).map { event(it, factA, text = otherClaim, timestamp = base + it * 43_200_000) }
            val result = report(listOf(earlier) + dominant + minority)
            check(result.groups.first().observations.size == 7)
            check(result.findings.none { "持续低增量重复" in it.title })
        },
        Case("audio quote points to the matching record, not the latest record") {
            val events = unionEvents().map(::audio)
            val result = LocalAudioInsights.analyze(listOf(events.last()), events.dropLast(1)).claims.single()
            check(result.novelty == Increment.REPEATED.label)
            check(result.historySegmentId == 1L && result.historyQuote == factA)
            check(result.evidence == factA)
        },
        Case("audio novelty shows the changed fragment without a fabricated history link") {
            val old = audio(event(1, factA))
            val fresh = audio(event(2, factA, advice))
            val result = LocalAudioInsights.analyze(listOf(fresh), listOf(old)).claims.single()
            check(result.evidence == advice && result.novelty == Increment.NEW.label)
            check(result.historySegmentId == null && result.historyQuote == null)
        },
        Case("audio observation does not become measured reading time") {
            val result = report(unionEvents())
            check(result.observedDurationMillis == 0L && result.unknownDurationRecords == 3)
        },
        Case("cancellation is propagated during analysis") {
            var checks = 0
            val failure = runCatching {
                CognitiveAnalyzer().analyze((1L..50L).map { event(it, factA) }, StatisticsPeriod.WEEK,
                    base + 60 * 60_000, zone, checkCancellation = {
                        if (++checks == 40) throw CancellationException("fixture cancellation")
                    })
            }.exceptionOrNull()
            check(failure is CancellationException && checks == 40)
        }
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var failed = 0
        val fixtures = cases()
        fixtures.forEach { case ->
            try { case.verify(); println("PASS ${case.name}") }
            catch (failure: Throwable) {
                failed++
                System.err.println("FAIL ${case.name}: ${failure.message}")
                failure.printStackTrace()
            }
        }
        println("RESULT ${fixtures.size - failed}/${fixtures.size} passed")
        check(failed == 0) { "$failed engineering regression cases failed" }
    }
}
