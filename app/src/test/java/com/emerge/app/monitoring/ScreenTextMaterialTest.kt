package com.emerge.app.monitoring

import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.calculateStatistics
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ScreenTextMaterialTest {
    private val parent = BrowseEvent(id = 10, packageName = "reader", timestamp = 1000, eventType = 32,
        visibleText = "已经读取的页面文字", endTimestamp = 2000, durationMillis = 1000, sampleCount = 2, workKey = "work")

    @Test fun filtersExactAccessibleLinesWithoutDroppingNegationOrNumbers() {
        assertEquals("支持这项政策\n同比减少20%", ScreenTextMaterial.additionalText(
            "页面 标题\n支持这项政策\n同比减少20%", "页面标题\n不支持这项政策\n同比减少2%"))
        assertEquals("", ScreenTextMaterial.additionalText("已有文字\n已有文字", "已有文字"))
    }

    @Test fun linksToCapturedParentWithoutInventingAnotherVisitOrDuration() {
        val material = ScreenTextMaterial.event("reader", "图片中的招聘岗位观点", 2500, parent).copy(id = 11)
        assertEquals(parent.id, material.parentEventId)
        assertEquals(parent.workKey, material.workKey)
        assertEquals(0, material.sampleCount)
        assertEquals(0L, material.durationMillis)
        val window = ObservationWindow(0, 5000, ZoneId.of("UTC"))
        assertFalse(window.durationKnown(material))
        val stats = calculateStatistics(listOf(parent, material), StatisticsPeriod.HOURS24, 5000, ZoneId.of("UTC"))
        assertEquals(1, stats.visitCount)
        assertEquals(1000L, stats.observedDurationMillis)
    }

    @Test fun unknownParentStaysUnknownAndStaleOrDifferentParentsAreRejected() {
        assertNull(ScreenTextMaterial.event("reader", "图片文字", 2500, null).parentEventId)
        for (invalid in listOf(parent.copy(packageName = "other"), parent.copy(sourceType = "audio_playback"),
            parent.copy(timestamp = 3000), parent.copy(id = 0), parent.copy(endTimestamp = 1))) {
            try {
                ScreenTextMaterial.event("reader", "图片文字", if (invalid.endTimestamp == 1L) 10_000 else 2500, invalid)
                fail("Invalid parent must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun ocrEvidenceSurvivesInputLimitAndKeepsItsSource() {
        val material = ScreenTextMaterial.event("reader", "否定条件：今年并未减少", 2500, parent)
        val input = ScreenTextMaterial.inputText("旧页面文字".repeat(2000), material)
        assertTrue(input.startsWith(material.visibleText))
        assertEquals(6000, input.length)
        assertEquals(ScreenTextMaterial.SOURCE, material.sourceType)
    }

    @Test fun manyOcrFragmentsFromThreeVisitsCannotPassEightContactThreshold() {
        val now = 1_800_000_000_000L
        val parents = (0..2).map { day -> parent.copy(id = (day + 1).toLong(),
            timestamp = now - day * 86_400_000L - 2000, endTimestamp = now - day * 86_400_000L - 1000,
            visibleText = "招聘岗位正在减少。因为岗位同比减少20%。") }
        val fragments = parents.flatMap { visit -> (0..4).map { index ->
            ScreenTextMaterial.event("reader", "招聘岗位正在减少。因为岗位同比减少20%。", visit.endTimestamp + index, visit)
                .copy(id = visit.id * 100 + index)
        } }
        val report = CognitiveAnalyzer().analyze(parents + fragments, StatisticsPeriod.WEEK, now)
        assertTrue(report.findings.isNotEmpty())
        assertTrue(report.findings.none { it.title.contains("观察到持续低增量重复") })
    }
}
