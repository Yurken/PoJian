package com.emerge.app.monitoring

import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.ClaimPresentation
import com.emerge.app.analysis.ContentEmotion
import com.emerge.app.analysis.EmotionCategory
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ImpactLevel
import com.emerge.app.analysis.ImpactSignal
import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class DiscoveryEmotionEvidenceTest {
    private val quote = "岗位减少让我很担心。"
    private val event = BrowseEvent(1, "reader", 1000, 32, quote)
    private val finding = MonitorFinding("emotion:1", "event:1", "reader", 1000, "emotion",
        "emotional_exposure", quote, "高强度 6 / 已分析 8；未知 2", topic = "就业 · 焦虑")
    private val signal = ImpactSignal(ImpactLevel.UNKNOWN, "未评估", "")
    private val presentation = ClaimPresentation("就业", "岗位减少引发担忧", signal, signal, signal,
        listOf("核对原文"), listOf(ContentEmotion(EmotionCategory.ANXIETY, ImpactLevel.HIGH, quote, "岗位减少")))
    private val claim = ArgumentUnit(quote, "就业", "岗位", "数量", "减少", uncertain = false, presentation = presentation)
    private val semantics = EventSemantics(1, quote, 2000, listOf(claim))

    @Test fun associatesOnlyVerbatimEvidenceWithItsRetainedOriginal() {
        val result = DiscoveryEmotionEvidence.resolve(finding, listOf(event), listOf(semantics))
        assertEquals(listOf(1L), result.map { it.eventId })
        assertEquals(quote, result.single().evidence)
        assertEquals("岗位减少", result.single().trigger)
    }

    @Test fun deletionAndTextRevisionRemoveEvidence() {
        assertTrue(DiscoveryEmotionEvidence.resolve(finding, emptyList(), listOf(semantics)).isEmpty())
        assertTrue(DiscoveryEmotionEvidence.resolve(finding, listOf(event.copy(visibleText = "修订后的原文")),
            listOf(semantics)).isEmpty())
    }

    @Test fun latestSemanticsSupersedesOlderEmotionInsteadOfFallingBack() {
        val revised = semantics.copy(analyzedAt = 3000, claims = listOf(claim.copy(presentation = presentation.copy(emotions = null))))
        assertTrue(DiscoveryEmotionEvidence.resolve(finding, listOf(event), listOf(semantics, revised)).isEmpty())
    }

    @Test fun anotherTopicCategoryPackageOrQuoteCannotBorrowEvidence() {
        for (mismatch in listOf(finding.copy(topic = "健康 · 焦虑"), finding.copy(topic = "就业 · 愤怒"),
                finding.copy(packageName = "another"), finding.copy(claim = "另一句原文"))) {
            assertTrue(DiscoveryEmotionEvidence.resolve(mismatch, listOf(event), listOf(semantics)).isEmpty())
        }
    }

    @Test fun unsupportedTriggerNeverAppearsAsAContentFact() {
        val forged = semantics.copy(claims = listOf(claim.copy(presentation = presentation.copy(emotions =
            listOf(ContentEmotion(EmotionCategory.ANXIETY, ImpactLevel.HIGH, quote, "不存在的诱因"))))))
        assertTrue(DiscoveryEmotionEvidence.resolve(finding, listOf(event), listOf(forged)).isEmpty())
    }
}
