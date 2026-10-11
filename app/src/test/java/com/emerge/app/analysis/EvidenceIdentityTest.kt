package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorPolicy
import com.emerge.app.monitoring.ObservedArgument
import com.emerge.app.ui.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Actual report and alert consumers, with independently specified identities and expected relationships. */
class EvidenceIdentityTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val quote = "当前互联网招聘需求下降"
    private fun story(actor: String) = "根据${actor}的本人记录，今年向互联网企业投递简历后，在约定时间内仍没有收到任何面试通知"
    private fun report(evidence: List<String>, actors: List<List<String>> = List(evidence.size) { emptyList() },
        origins: List<List<String>> = List(evidence.size) { emptyList() }, conditions: List<String> = List(evidence.size) { "当前" }): CognitiveReport {
        val events = evidence.mapIndexed { i, value -> BrowseEvent(i + 1L, "reader.${i % 3}",
            now - 4 * 86_400_000L + i * 30_000_000L, 32, "$quote，因为$value", sampleCount = 1,
            workKey = "publisher-post-$i") }
        val semantics = events.mapIndexed { i, event -> EventSemantics(event.id, event.visibleText, now,
            listOf(ArgumentUnit(quote, "就业与职场", "互联网招聘需求", "岗位数量", "下降", evidence[i],
                conditions[i], false, actors[i], origins[i]))) }
        return CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics)
    }
    private fun live(evidence: List<String>, actors: List<List<String>> = List(evidence.size) { emptyList() }) =
        evidence.mapIndexed { i, value -> ObservedArgument("content-$i", "reader.${i % 3}",
            now - 4 * 86_400_000L + i * 30_000_000L, "就业与职场", "互联网招聘需求", "岗位数量", "下降",
            value, "当前", encounterKey = "post-$i", evidenceActors = actors[i]) }

    @Test fun differentNamedCasesKeepOneViewpointButAddIndividualFacts() {
        val result = report(listOf(story("工程师李青"), story("设计师陈海")))
        assertEquals(listOf(Increment.FIRST, Increment.NEW), result.claims.map { it.increment })
        assertEquals(1, result.groups.size)
        assertEquals(listOf("设计师陈海"), result.claims.last().evidenceDetails.single().identity.actors)
        assertTrue(result.claims.last().evidenceDetails.single().historyMatches.isEmpty())
    }

    @Test fun repostingTheSameCaseAcrossAccountsDoesNotInventIndependentEvidence() {
        val result = report(List(2) { story("工程师李青") })
        assertEquals(Increment.REPEATED, result.claims.last().increment)
        assertEquals(1L, result.claims.last().evidenceDetails.single().historyMatches.single().eventId)
    }

    @Test fun structuredActorsProtectNonTemplateNamesAndOrganizations() {
        val evidence = listOf("患者Alice在本月规定的观察期内都没有出现任何康复迹象",
            "患者Bruno在本月规定的观察期内都没有出现任何康复迹象")
        assertEquals(Increment.NEW, report(evidence, listOf(listOf("Alice"), listOf("Bruno"))).claims.last().increment)
        assertEquals(EvidenceRelation.DIFFERENT_CASE, EvidenceIdentityComparator.compare(
            "星河诊所的患者等待处理", "远山诊所的患者等待处理",
            EvidenceIdentity(listOf("星河诊所")), EvidenceIdentity(listOf("远山诊所"))))
    }

    @Test fun unnamedAndAnonymousPersonalRecordsDoNotEstablishReuse() {
        for (actor in listOf("我", "某求职者", "王某", "张先生", "一位居民")) {
            val result = report(List(2) { story(actor) })
            assertEquals(actor, Increment.UNKNOWN, result.claims.last().increment)
            assertTrue(actor, result.claims.last().evidenceDetails.single().identityUnresolved)
            assertTrue(result.claims.last().evidenceDetails.single().historyMatches.isEmpty())
        }
    }

    @Test fun losingAnActorReferenceDoesNotAssertNoveltyOrRepetition() {
        val value = "患者Alice在本月规定的观察期内都没有出现任何康复迹象"
        val result = report(listOf(value, value), listOf(listOf("Alice"), emptyList()))
        assertEquals(Increment.UNKNOWN, result.claims.last().increment)
        assertFalse(result.claims.last().evidenceDetails.single().isNew)
    }

    @Test fun differentOrMissingMaterialReferencesDoNotProveIndependentFacts() {
        val first = "报告星河显示当前企业招聘岗位在全部调查城市中均没有出现任何改善迹象"
        val second = first.replace("星河", "远山")
        assertEquals(Increment.UNKNOWN, report(listOf(first, second)).claims.last().increment)
        val value = "原材料星河指出当前企业招聘岗位在全部调查城市中均没有出现任何改善迹象"
        assertEquals(Increment.UNKNOWN, report(listOf(value, value),
            origins = listOf(listOf("原材料星河"), emptyList())).claims.last().increment)
    }

    @Test fun actorEqualityNeverErasesChangedQuantityNegationOrScope() {
        val prefix = "根据工程师李青的本人记录，"
        val pairs = listOf("投递20份简历" to "投递30份简历", "录用率增加+10%" to "录用率增加-10%",
            "至少收到20封回复" to "至多收到20封回复", "今年在上海投递简历" to "今年在北京投递简历")
        for ((a, b) in pairs) assertEquals(a, Increment.NEW, report(listOf(prefix + a, prefix + b)).claims.last().increment)
        assertEquals(Increment.CONDITIONS, report(List(2) { story("工程师李青") },
            conditions = listOf("去年", "今年")).claims.last().increment)
    }

    @Test fun olderMatchingCaseRemainsAvailableBeyondADifferentRecentCase() {
        val result = report(listOf(story("工程师李青"), story("设计师陈海"), story("工程师李青")))
        assertEquals(Increment.REPEATED, result.claims.last().increment)
        assertEquals(1L, result.claims.last().evidenceDetails.single().historyMatches.single().eventId)
    }

    @Test fun numbersInMaterialAndActorLabelsDoNotBypassUnresolvedIdentity() {
        val evidence = "《调查2025》记录企业招聘岗位在全部调查城市中均没有出现任何改善迹象"
        assertEquals(Increment.UNKNOWN, report(listOf(evidence, evidence.replace("2025", "2026"))).claims.last().increment)
        assertEquals(Increment.UNKNOWN, report(listOf(story("受访者A12"), story("某求职者"))).claims.last().increment)
        assertEquals(Increment.UNKNOWN, report(listOf(story("我"), "根据某求职者的个人记录，面试通知一直没有送达本人")).claims.last().increment)
    }

    @Test fun longSameActorNarrativesKeepTheirChangedTimeAndPlace() {
        val value = story("工程师李青").replace("互联网企业", "上海互联网企业")
        for (changed in listOf(value.replace("今年", "去年"), value.replace("上海", "北京")))
            assertEquals(Increment.NEW, report(listOf(value, changed)).claims.last().increment)
    }

    @Test fun identityPunctuationAndExtractionCapacityCannotEraseDifferentCases() {
        assertEquals(Increment.NEW, report(listOf(story("受访者A-12"), story("受访者A12"))).claims.last().increment)
        val first = listOf("李青", "陈海", "赵云", "林森", "王海").joinToString("；") { story(it) }
        val changed = first.replace("王海", "周悦")
        assertEquals(Increment.UNKNOWN, report(listOf(first, changed)).claims.last().increment)
        assertTrue(EvidenceIdentityComparator.identity(first).unresolvedCase)
        val reports = (1..5).joinToString("；") { "《调查$it》显示岗位状况没有改善" }
        assertEquals(EvidenceRelation.UNRESOLVED, EvidenceIdentityComparator.compare(reports, reports.replace("调查5", "调查6")))
        val actors = (1..5).joinToString("；") { story("受访者A$it") }
        assertEquals(EvidenceRelation.UNRESOLVED, EvidenceIdentityComparator.compare(actors, actors.replace("A5", "A6")))
    }

    @Test fun aRolePrefixInTheModelSpanDoesNotInventAnotherPerson() {
        val value = story("工程师李青")
        assertEquals(Increment.REPEATED, report(listOf(value, value),
            listOf(listOf("工程师李青"), listOf("李青"))).claims.last().increment)
    }

    @Test fun sustainedDistinctCasesDoNotTriggerReportOrLiveRepetitionAlerts() {
        val evidence = (0 until 12).map { story("当事对象${('A'.code + it).toChar()}") }
        val result = report(evidence)
        assertEquals(11, result.claims.count { it.increment == Increment.NEW })
        assertTrue(result.findings.none { it.title.contains("持续低增量重复") })
        assertNull(MonitorPolicy.repetition(live(evidence), now, zone))
    }

    @Test fun sustainedRepostsStillTriggerTheExistingReviewHint() {
        val evidence = List(12) { story("工程师李青") }
        assertTrue(report(evidence).findings.any { it.title.contains("持续低增量重复") })
        assertNotNull(MonitorPolicy.repetition(live(evidence), now, zone))
    }

    @Test fun anonymousExactStoriesAndSimultaneousEventsNeverTriggerLiveReuse() {
        val evidence = List(12) { story("我") }
        assertNull(MonitorPolicy.repetition(live(evidence), now, zone))
        assertNull(MonitorPolicy.repetition(live(List(12) { story("工程师李青") }).map { it.copy(capturedAt = now) }, now, zone))
    }
}
