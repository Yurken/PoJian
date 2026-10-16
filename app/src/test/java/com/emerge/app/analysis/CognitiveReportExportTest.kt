package com.emerge.app.analysis

import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CognitiveReportExportTest {
    private fun report() = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.MONTH,
        1_792_123_200_000L, ZoneId.of("Asia/Shanghai"))

    @Test fun boundedPdfProjectionLimitsTopicQuotesAndReportsOmissions() {
        val views = (1..8).map { n -> DigestViewpoint("v$n", "测试原句标记$n", "岗位", "增加", "2026年",
            setOf("work$n"), emptyList(), listOf(DigestEvidence("论据标记$n", EvidenceType.FACT,
                listOf(n.toLong()), emptyList(), false))) }
        val current = report().copy(topicDigests = listOf(TopicDigest("t", "就业", "岗位", views)))
        val bounded = ReportExporter.text(current, appendixLimit = 2)
        assertTrue(bounded.contains("测试原句标记1"))
        assertTrue(bounded.contains("测试原句标记2"))
        assertFalse(bounded.contains("测试原句标记3"))
        assertFalse(bounded.contains("论据标记3"))
        assertTrue(bounded.contains("主题观点仅列出前 2 项"))
        val complete = ReportExporter.text(current)
        assertTrue(complete.contains("测试原句标记8"))
        assertTrue(complete.contains("论据标记8"))
    }

    @Test fun topicAndEmotionSectionsUseTheReportWindowNotTheSevenDayObservationHeading() {
        val text = ReportExporter.text(report())
        assertTrue(text.contains("\n## 4. 主题观点\n"))
        assertTrue(text.contains("\n## 5. 内容情绪\n"))
        assertTrue(text.contains("最近 30 天范围内同一小时的合计"))
        assertFalse(text.contains("以下为当天 00:00"))
    }

    @Test fun unassessedEmotionIsExportedAsUnknownWithoutNeutralCounts() {
        val text = ReportExporter.text(report().copy(emotionExposure = EmotionExposureSummary(unknownWorks = 4)))
        val section = text.substringAfter("内容情绪").substringBefore("记录来源")
        assertTrue(section.contains("已分析 0 个作品；未分析 4 个作品"))
        assertFalse(section.contains("中性："))
    }
}
