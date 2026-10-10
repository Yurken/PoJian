package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId

/** Offline regression fixtures for provenance and ordering, not model accuracy measurements. */
object StructuredSemanticsCases {
    data class Case(val name: String, val verify: () -> Unit)
    private val now = Instant.parse("2026-10-08T03:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private const val quote = "当前就业形势很差"
    private const val fact = "招聘岗位同比减少20%"
    private fun event(id: Long, text: String = "$quote，因为$fact", at: Long = now - (10 - id) * 60_000) =
        BrowseEvent(id, "fixture.reader", at, 32, text, sampleCount = 1)
    private fun claim(q: String = quote, e: String = fact) =
        ArgumentUnit(q, "就业与职场", "就业形势", "岗位需求", "下降", e, "当前", false)
    private fun semantics(event: BrowseEvent, claims: List<ArgumentUnit> = listOf(claim()), analyzedAt: Long = now) =
        EventSemantics(event.id, event.visibleText, analyzedAt, claims)
    private fun report(events: List<BrowseEvent>, semantics: List<EventSemantics>) =
        CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics)

    fun cases(): List<Case> = listOf(
        Case("valid full snapshots can exceed request excerpts") {
            val event = event(1, "$quote，因为$fact\n" + "背景".repeat(4_000))
            check(semantics(event).validFor(event))
            check(!semantics(event.copy(visibleText = "字".repeat(16_001)), emptyList())
                .validFor(event.copy(visibleText = "字".repeat(16_001))))
        },
        Case("stale text and wrong event identities are rejected") {
            val event = event(1)
            check(!semantics(event).validFor(event.copy(visibleText = event.visibleText.replace("20%", "40%"))))
            check(!semantics(event).copy(eventId = 2).validFor(event))
            check(!semantics(event).copy(inputText = event.visibleText.take(6)).validFor(event))
        },
        Case("invented or stitched quotations cannot enter retained history") {
            val event = event(1)
            check(!semantics(event, listOf(claim(q = "当前就业形势很好"))).validFor(event))
            check(!semantics(event, listOf(claim(e = "招聘岗位同比减少40%"))).validFor(event))
            check(!semantics(event, listOf(claim(q = "就业形势$fact"))).validFor(event))
        },
        Case("bounded fields permit unknown information but reject oversized results") {
            val event = event(1)
            val unknown = ArgumentUnit(quote, "", "", "", "")
            check(semantics(event, listOf(unknown)).validFor(event))
            check(!semantics(event, listOf(claim().copy(subject = "字".repeat(101)))).validFor(event))
            check(!semantics(event, List(4) { claim() }).validFor(event))
            check(!semantics(event).copy(inputKind = "字".repeat(41)).validFor(event))
            check(!semantics(event).copy(analyzedAt = -1).validFor(event))
        },
        Case("multiple model viewpoints replace rather than append local guesses") {
            val event = event(1, "$quote，因为$fact\n未来可能恢复，因为企业扩招")
            val future = claim(q = "未来可能恢复", e = "企业扩招").copy(stance = "恢复", conditions = "未来")
            val result = report(listOf(event), listOf(semantics(event, listOf(claim(), future))))
            check(result.claims.size == 2 && result.groups.size == 2)
            check(result.claims.all { it.analysisSource == "structured_model" })
            check(result.claims.map { it.quote } == listOf(quote, "未来可能恢复"))
            check(result.structuredAnalyzedRecords == 1 && result.localFallbackRecords == 0)
            check(result.coverageSessions == 1)
        },
        Case("valid zero-claim analysis does not fall back to a keyword viewpoint") {
            val event = event(1)
            val result = report(listOf(event), listOf(semantics(event, emptyList())))
            check(result.claims.isEmpty())
            check(result.structuredAnalyzedRecords == 1 && result.localFallbackRecords == 0)
            check(result.usableSnapshots == 1)
        },
        Case("model evidence supports short text outside the local topic dictionary") {
            val event = event(1, "河水清澈\n透明度80厘米")
            val unit = ArgumentUnit("河水清澈", "水质", "河水", "透明度", "清澈", "透明度80厘米", uncertain = false)
            val result = report(listOf(event), listOf(semantics(event, listOf(unit))))
            check(result.claims.single().quote == "河水清澈")
            check(result.claims.single().evidence == listOf("透明度80厘米"))
            check(result.topicExposure.single().topic == "水质")
        },
        Case("invalid analysis falls back locally and reports rejected coverage") {
            val event = event(1)
            val result = report(listOf(event), listOf(semantics(event).copy(inputText = "旧页面")))
            check(result.claims.single().analysisSource == "local_rules")
            check(result.structuredAnalyzedRecords == 0 && result.localFallbackRecords == 1)
            check(result.rejectedSemanticsRecords == 1)
        },
        Case("latest result can retract previously extracted model viewpoints") {
            val event = event(1)
            val result = report(listOf(event), listOf(semantics(event, emptyList(), now),
                semantics(event, analyzedAt = now - 1)))
            check(result.claims.isEmpty() && result.structuredAnalyzedRecords == 1)
        },
        Case("an invalid newest version cannot silently revive an older model result") {
            val event = event(1)
            val result = report(listOf(event), listOf(semantics(event, analyzedAt = now - 1),
                semantics(event).copy(inputText = "旧页面")))
            check(result.claims.single().analysisSource == "local_rules")
            check(result.rejectedSemanticsRecords == 1)
        },
        Case("analysis completion order cannot rewrite browsing chronology") {
            val early = event(1)
            val late = event(2)
            val rows = listOf(semantics(early, analyzedAt = now + 5_000), semantics(late, analyzedAt = now - 5_000))
            val result = report(listOf(late, early), rows.reversed())
            check(result.claims.first().timestamp == early.timestamp)
            check(result.claims.first().increment == Increment.FIRST)
            check(result.claims.last().increment == Increment.REPEATED)
            check(result.claims.last().previousEventId == early.id)
            check(result.claims.last().evidenceDetails.single().historyMatches == listOf(EvidenceHistoryMatch(early.id, fact)))
        },
        Case("simultaneous model events cannot serve as each other's past") {
            val events = listOf(event(1), event(2, at = event(1).timestamp))
            val result = report(events, events.map { semantics(it) })
            check(result.claims.all { it.increment == Increment.FIRST && it.previousEventId == null })
        },
        Case("same object with a different dimension or stance stays separate") {
            val events = (1L..4L).map { event(it) }
            val units = listOf(claim(), claim().copy(subject = "另一行业"),
                claim().copy(dimension = "工资"), claim().copy(stance = "增长"))
            val result = report(events, events.zip(units).map { (event, unit) -> semantics(event, listOf(unit)) })
            check(result.groups.size == 4)
            check(result.claims.all { it.increment == Increment.FIRST && it.previousEventId == null })
        },
        Case("different conditions keep original evidence without asserting duplication") {
            val events = listOf(event(1), event(2))
            val result = report(events, listOf(semantics(events[0]),
                semantics(events[1], listOf(claim().copy(conditions = "未来")))))
            check(result.groups.size == 2)
            check(result.claims.last().increment == Increment.CONDITIONS)
            check(result.claims.last().evidenceDetails.single().historyMatches.isEmpty())
            check(result.claims.last().evidence == listOf(fact))
        },
        Case("unknown model fields remain visible without fabricated matches") {
            val events = listOf(event(1), event(2))
            val result = report(events, events.map { semantics(it, listOf(claim().copy(dimension = ""))) })
            check(result.claims.size == 2 && result.groups.size == 2)
            check(result.claims.all { it.increment == Increment.UNKNOWN && it.previousEventId == null })
            check(result.claims.all { it.quote == quote && it.evidence == listOf(fact) })
        },
        Case("missing supporting evidence does not become a novelty assertion") {
            val events = listOf(event(1), event(2))
            val result = report(events, events.map { semantics(it, listOf(claim(e = ""))) })
            check(result.claims.last().increment == Increment.UNKNOWN)
            check(result.claims.none { it.increment == Increment.NEW || it.increment == Increment.REPEATED })
        },
        Case("model and rule inferences do not manufacture mutual evidence matches") {
            val events = listOf(event(1), event(2))
            val result = report(events, listOf(semantics(events[1])))
            check(result.claims.map { it.analysisSource } == listOf("local_rules", "structured_model"))
            check(result.claims.last().previousEventId == null && result.claims.last().increment == Increment.FIRST)
            check(result.groups.size == 2)
        },
        Case("changed numbers survive model evidence comparison") {
            val events = listOf(event(1), event(2, "$quote，因为招聘岗位同比减少40%"))
            val result = report(events, listOf(semantics(events[0]),
                semantics(events[1], listOf(claim(e = "招聘岗位同比减少40%")))))
            check(result.claims.last().increment == Increment.NEW)
            check(result.claims.last().evidenceDetails.single().isNew)
        },
        Case("deleting a historical event removes its model evidence dependencies") {
            val events = listOf(event(1), event(2))
            val rows = events.map { semantics(it) }
            val result = report(listOf(events.last()), rows)
            check(result.claims.single().increment == Increment.FIRST)
            check(result.claims.single().comparedHistoryEventIds.isEmpty())
            check(result.structuredAnalyzedRecords == 1)
        },
        Case("coverage counts current events while older model history stays comparable") {
            val early = event(1, at = now - 8 * 86_400_000L)
            val current = event(2)
            val result = report(listOf(early, current), listOf(semantics(early), semantics(current)))
            check(result.claims.single().increment == Increment.REPEATED)
            check(result.structuredAnalyzedRecords == 1 && result.localFallbackRecords == 0)
            check(result.comparisonRecords == 1)
        }
    )

    @JvmStatic fun main(args: Array<String>) {
        val cases = cases()
        cases.forEach { case ->
            try { case.verify() } catch (error: Throwable) {
                throw AssertionError("Structured semantics case failed: ${case.name}", error)
            }
        }
        println("Structured semantics: ${cases.size} offline regression cases passed.")
    }
}
