package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class StructuredSemanticsTest {
    @Test fun retainedEvidenceAndChronologyRegressionsPass() {
        StructuredSemanticsCases.cases().forEach { case ->
            try { case.verify() } catch (error: Throwable) {
                throw AssertionError(case.name, error)
            }
        }
    }

    private val evidence = "小林引用《就业报告》指出招聘数量下降"
    private val event = BrowseEvent(id = 1, packageName = "fixture.reader", timestamp = 0, eventType = 0,
        visibleText = "发布者阿明认为岗位正在减少，因为$evidence")
    private val unit = ArgumentUnit("岗位正在减少", "就业", "岗位", "数量", "减少", evidence,
        uncertain = false, evidenceActors = listOf("小林"), evidenceOrigins = listOf("《就业报告》"))
    private fun valid(claim: ArgumentUnit): Boolean = EventSemantics(event.id, event.visibleText, 0,
        listOf(claim)).validFor(event)

    @Test fun evidenceIdentityAnchorsAreRetainedWithinTheEvidenceRatherThanThePost() {
        assertTrue(valid(unit))
        assertTrue(valid(unit.copy(evidenceActors = emptyList(), evidenceOrigins = emptyList())))
        assertFalse(valid(unit.copy(evidenceActors = listOf("阿明"))))
        assertFalse(valid(unit.copy(evidenceActors = listOf("岗位"))))
        assertFalse(valid(unit.copy(evidenceOrigins = listOf("不存在的报告"))))
        assertFalse(valid(unit.copy(evidenceActors = listOf("小林招聘"))))
    }

    @Test fun evidenceIdentityListsRejectBlankDuplicateAndUnanchoredEntries() {
        listOf(listOf(""), listOf(" "), listOf("小林", "小林"), listOf("小林", "小王")).forEach { bad ->
            assertFalse(valid(unit.copy(evidenceActors = bad)))
            assertFalse(valid(unit.copy(evidenceOrigins = bad)))
        }
    }

    @Test fun evidenceIdentityListsEnforceIndependentCountAndLengthBounds() {
        val four = listOf("小林", "引用", "报告", "招聘")
        assertTrue(valid(unit.copy(evidenceActors = four, evidenceOrigins = four)))
        assertFalse(valid(unit.copy(evidenceActors = four + "下降")))
        assertFalse(valid(unit.copy(evidenceOrigins = four + "下降")))
        val eighty = "甲".repeat(80)
        val longEvent = event.copy(visibleText = "岗位正在减少，因为${eighty}甲")
        val longUnit = unit.copy(evidence = eighty + "甲", evidenceActors = listOf(eighty),
            evidenceOrigins = listOf(eighty))
        assertTrue(EventSemantics(longEvent.id, longEvent.visibleText, 0, listOf(longUnit)).validFor(longEvent))
        assertFalse(EventSemantics(longEvent.id, longEvent.visibleText, 0,
            listOf(longUnit.copy(evidenceActors = listOf(eighty + "甲")))).validFor(longEvent))
        assertFalse(EventSemantics(longEvent.id, longEvent.visibleText, 0,
            listOf(longUnit.copy(evidenceOrigins = listOf(eighty + "甲")))).validFor(longEvent))
    }

    @Test fun absentEvidenceCannotCarryIdentityAnchors() {
        assertTrue(valid(unit.copy(evidence = "", evidenceActors = emptyList(), evidenceOrigins = emptyList())))
        assertFalse(valid(unit.copy(evidence = "", evidenceOrigins = emptyList())))
        assertFalse(valid(unit.copy(evidence = "", evidenceActors = emptyList())))
    }
}
