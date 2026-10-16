package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ContentEmotionTest {
    private val text = "岗位正在减少，想到找不到工作就感到焦虑。庆幸家人愿意支持我。"
    private val signal = ImpactSignal(ImpactLevel.UNKNOWN, "尚未评估", "")
    private fun presentation(emotions: List<ContentEmotion>? = null) = ClaimPresentation("求职", "求职压力与家庭支持",
        signal, signal, signal, listOf("比较岗位要求"), emotions)
    private fun emotion(category: EmotionCategory = EmotionCategory.ANXIETY, level: ImpactLevel = ImpactLevel.HIGH) =
        ContentEmotion(category, level, "想到找不到工作就感到焦虑", "岗位正在减少")

    @Test fun optionalEmotionRoundTripKeepsOldRowsUnknownAndRetainsVerbatimTrigger() {
        for (emotions in listOf(null, listOf(emotion(), emotion(EmotionCategory.JOY, ImpactLevel.LOW).copy(
            evidence = "庆幸家人愿意支持我", trigger = null)))) {
            val annotation = presentation(emotions)
            val encoded = ClaimPresentationCodec.encode(annotation)
            assertEquals(emotions != null, encoded.has("emotions"))
            assertEquals(annotation, ClaimPresentationCodec.decode(encoded))
            assertTrue(annotation.validFor(text))
        }
    }

    @Test fun emotionRequiresExactObservedEvidenceAndOptionalTriggerIsNotInvented() {
        assertTrue(emotion().validFor(text))
        assertTrue(emotion(level = ImpactLevel.UNKNOWN).copy(trigger = null).validFor(text))
        assertFalse(emotion().copy(evidence = "我很愤怒").validFor(text))
        assertFalse(emotion().copy(evidence = "").validFor(text))
        assertFalse(emotion().copy(trigger = "用户有心理障碍").validFor(text))
        assertFalse(emotion().copy(trigger = "").validFor(text))
    }

    @Test fun malformedEmotionCategoryNeutralMixtureAndDuplicateLabelsAreRejected() {
        assertFalse(presentation(emptyList()).validFor(text))
        assertFalse(presentation(List(4) { emotion() }).validFor(text))
        assertFalse(presentation(listOf(emotion(), emotion())).validFor(text))
        assertFalse(presentation(listOf(emotion(), emotion(EmotionCategory.NEUTRAL))).validFor(text))
        val encoded = ClaimPresentationCodec.encode(presentation()).put("emotions", JSONArray().put(
            org.json.JSONObject().put("category", "depression").put("level", "high")
                .put("evidence", "想到找不到工作就感到焦虑")))
        assertTrue(runCatching { ClaimPresentationCodec.decode(encoded) }.isFailure)
    }

    @Test fun invalidEmotionCannotMakeDeletedOrChangedMaterialValid() {
        val event = BrowseEvent(1, "reader", 1, 0, text)
        val annotation = presentation(listOf(emotion()))
        val claim = ArgumentUnit(text, "求职", "岗位", "机会", "减少", presentation = annotation)
        val semantics = EventSemantics(1, text, 1, listOf(claim))
        assertTrue(semantics.validFor(event))
        assertFalse(semantics.validFor(event.copy(visibleText = "已经更正的内容")))
        assertFalse(semantics.copy(claims = listOf(claim.copy(presentation = annotation.copy(
            emotions = listOf(emotion().copy(evidence = "其他作品原文")))))).validFor(event))
    }
}
