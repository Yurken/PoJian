package com.emerge.app.analysis

import com.emerge.app.ui.AppShare
import com.emerge.app.ui.BrowseStatistics
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReportPdfContentTest {
    @Test fun supportingEvidenceCannotBecomeTheSharedClaimOfTheNextRecord() {
        val original = claim(11, "共同原文").copy(evidence = listOf("对应依据"),
            evidenceDetails = listOf(EvidenceFragment("对应依据", EvidenceType.FACT,
                historyMatches = listOf(EvidenceHistoryMatch(1, "历史依据")))))
        val next = claim(22, "共同原文")
        val content = ReportPdfContent.from(report().copy(claims = listOf(original, next),
            groups = listOf(ClaimGroup("就业", "共同原文", listOf(original, next)))))
        val paragraphs = content.blocks.filterIsInstance<ReportPdfBlock.Paragraph>()
        assertEquals(listOf("共同原文"), paragraphs.filter { it.kind == ReportPdfBlock.Kind.QUOTE }.map { it.text })
        assertEquals(listOf("对应依据", "历史依据"), paragraphs.filter { it.kind == ReportPdfBlock.Kind.EVIDENCE }.map { it.text })
        assertTrue(paragraphs.any { it.kind == ReportPdfBlock.Kind.SOURCE && it.text.startsWith("记录 #22 |") })
    }
    private fun report() = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.MONTH,
        1_792_123_200_000L, ZoneId.of("Asia/Shanghai"))

    private fun claim(id: Long, quote: String = "岗位需求增加。") = ClaimObservation(id,
        1_792_123_200_000L + id * 1000, "app.$id", "就业", quote, emptyList(), "2026年",
        false, Increment.FIRST, null, subject = "岗位")

    private fun ReportPdfContent.words() = blocks.joinToString("\n") { block ->
        when (block) {
            is ReportPdfBlock.Heading -> block.text
            is ReportPdfBlock.Paragraph -> block.text
            is ReportPdfBlock.Metrics -> block.items.joinToString("\n") { "${it.label}：${it.value}" }
            is ReportPdfBlock.Bars -> block.rows.joinToString("\n") { "${it.label}：${it.display}" }
            is ReportPdfBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { it.joinToString(" | ") }
            is ReportPdfBlock.Hours -> block.unit
            ReportPdfBlock.PageBreak -> ""
        }
    }

    @Test fun summarySeparatesWorksVisitsClaimsAndRecordDenominators() {
        val current = report().copy(rawRecords = 9, usableSnapshots = 5, coverageSessions = 5,
            claims = (1L..4L).map(::claim), observedDurationMillis = 20_000,
            topicExposure = listOf(TopicExposure("就业", 3, 15_000), TopicExposure("娱乐", 2, 5_000)),
            workStatistics = BrowseStatistics(2, 1, 100, emptyList(),
                listOf(AppShare("app.one", 2, 12_000, visitCount = 3)),
                observedDurationMillis = 12_000, visitCount = 3))
        val content = ReportPdfContent.from(current)
        val metrics = content.blocks.filterIsInstance<ReportPdfBlock.Metrics>().first().items
        assertEquals(listOf("2 个", "3 次", "12.0 秒", "4 个"), metrics.map { it.value })
        val topic = content.blocks.filterIsInstance<ReportPdfBlock.Bars>()
            .flatMap { it.rows }.first { it.label == "就业" }
        assertTrue(topic.display.contains("3/5 条"))
        assertTrue(topic.display.contains("60.0%"))
        val text = content.words()
        assertTrue(text.contains("15.0 秒"))
        assertTrue(text.contains("75.0%"))
        assertTrue(text.contains("9 条原始记录"))
        assertTrue(text.contains("停留时长合计 20.0 秒"))
    }

    @Test fun absentWorkAndEmotionAnalysisRemainUnknownInsteadOfBecomingNeutralOrZeroWorks() {
        val content = ReportPdfContent.from(report().copy(workStatistics = null,
            emotionExposure = EmotionExposureSummary(unknownWorks = 4)))
        val metrics = content.blocks.filterIsInstance<ReportPdfBlock.Metrics>().first().items
        assertEquals("未统计", metrics[0].value)
        assertEquals("未统计", metrics[1].value)
        assertTrue(content.words().contains("已分析 0 个作品；未分析 4 个作品"))
        assertFalse(content.blocks.filterIsInstance<ReportPdfBlock.Bars>().flatMap { it.rows }
            .any { it.label == "中性" })
        assertFalse(content.words().contains("NaN"))
        assertFalse(content.words().contains("Infinity"))
    }

    @Test fun sevenDayRepeatedQuoteAppearsOnceWithEveryRecordSource() {
        val quote = "第一行原文。\n第二行原文 # 仍属引用。"
        val claims = listOf(claim(11, quote), claim(22, quote))
        val current = report().copy(findings = listOf(ObservationFinding("就业", "观察不足", "样本待核实", listOf(11, 22))),
            exposure = ExposureAssessment(0, 1, ExposureDistribution(2, 1, 2, emptyMap(), emptyMap()),
                null, null, emptyList(), claims))
        val blocks = ReportPdfContent.from(current).blocks
        val start = blocks.indexOfFirst { it is ReportPdfBlock.Heading && it.text.startsWith("3.") }
        val end = blocks.indexOfFirst { it is ReportPdfBlock.Heading && it.text.startsWith("4.") }
        assertTrue(start >= 0 && end > start)
        val paragraphs = blocks.subList(start, end).filterIsInstance<ReportPdfBlock.Paragraph>()
        assertEquals(listOf(quote), paragraphs.filter { it.kind == ReportPdfBlock.Kind.QUOTE }.map { it.text })
        val sources = paragraphs.filter { it.kind == ReportPdfBlock.Kind.SOURCE }.map { it.text }
        assertTrue(sources.any { it.contains("#11") && it.contains("app.11") && it.contains("2026-") })
        assertTrue(sources.any { it.contains("#22") && it.contains("app.22") && it.contains("2026-") })
    }

    @Test fun appendixAndSignalLimitsStillReportOmissions() {
        val claims = (1L..101L).map { claim(it, "附录唯一原文-$it") }
        val content = ReportPdfContent.from(report().copy(claims = claims,
            groups = listOf(ClaimGroup("就业", "代表原文", claims)),
            contentSignals = (1L..21L).map { ContentSignal(it, "原文措辞", "措辞唯一-$it") }))
        val quotes = content.blocks.filterIsInstance<ReportPdfBlock.Paragraph>()
            .filter { it.kind == ReportPdfBlock.Kind.QUOTE }.map { it.text }
        assertTrue(quotes.contains("附录唯一原文-100"))
        assertFalse(quotes.contains("附录唯一原文-101"))
        assertTrue(quotes.contains("措辞唯一-20"))
        assertFalse(quotes.contains("措辞唯一-21"))
        assertTrue(content.words().contains("PDF 附录最多列出前 100 个观点"))
        assertTrue(content.words().contains("PDF 只列出前 20 条"))
    }

    @Test fun appendixSourcesRemainAttachedToTheirExactQuoteWhenSimilarQuotesAlternate() {
        val claims = listOf(claim(11, "共同原文"), claim(22, "另一原文"), claim(33, "共同原文"))
        val blocks = ReportPdfContent.from(report().copy(claims = claims,
            groups = listOf(ClaimGroup("就业", "共同原文", claims)))).blocks
        val from = blocks.indexOfFirst { it is ReportPdfBlock.Heading && it.text.startsWith("8.") }
        val to = blocks.indexOfFirst { it is ReportPdfBlock.Heading && it.text.startsWith("9.") }
        assertTrue(from >= 0 && to > from)
        var activeQuote = ""
        val quoteByRecord = mutableMapOf<Long, String>()
        blocks.subList(from, to).filterIsInstance<ReportPdfBlock.Paragraph>().forEach { block ->
            if (block.kind == ReportPdfBlock.Kind.QUOTE) activeQuote = block.text
            if (block.kind == ReportPdfBlock.Kind.SOURCE) {
                Regex("^记录 #(\\d+)").find(block.text)?.groupValues?.get(1)?.toLong()?.let {
                    quoteByRecord[it] = activeQuote
                }
            }
        }
        assertEquals(mapOf(11L to "共同原文", 22L to "另一原文", 33L to "共同原文"), quoteByRecord)
    }

    @Test fun digestAndEmotionLimitsPreserveEvidenceAndOmissionNotices() {
        val views = (1L..101L).map { id -> DigestViewpoint("v$id", "主题原文-$id", "岗位", "增加", "2026年",
            setOf("w$id"), listOf(claim(id)), listOf(DigestEvidence("主题依据-$id", EvidenceType.FACT,
                listOf(id), listOf(EvidenceHistoryMatch(id + 200, "历史依据-$id")), false))) }
        val emotions = (1L..101L).map { EmotionEvidence(it, EmotionCategory.ANXIETY, ImpactLevel.UNKNOWN, "情绪原文-$it") }
        val content = ReportPdfContent.from(report().copy(topicDigests = listOf(TopicDigest("t", "就业", "岗位", views)),
            emotionExposure = EmotionExposureSummary(knownWorks = 101,
                works = listOf(EmotionWorkExposure("w", listOf(1), emptyList(), emptyList(), emotions)))))
        val text = content.words()
        listOf("主题原文-100", "主题依据-100", "历史依据-100", "情绪原文-100").forEach { assertTrue(text.contains(it)) }
        listOf("主题原文-101", "主题依据-101", "历史依据-101", "情绪原文-101").forEach { assertFalse(text.contains(it)) }
        listOf("主题观点仅列出前 100 项", "主题依据仅列出前 100 项", "情绪原文仅列出前 100 项").forEach {
            assertTrue(text.contains(it))
        }
    }

    @Test fun originalMethodAndLimitationsAreKeptAtTheEndOutsideTheSummary() {
        val current = report()
        val content = ReportPdfContent.from(current)
        val methodIndex = content.blocks.indexOfFirst { it is ReportPdfBlock.Heading && it.text == "9. 方法与局限" }
        assertTrue(methodIndex > 0)
        val methodText = content.copy(blocks = content.blocks.drop(methodIndex)).words()
        ReportExporter.text(current, 100).substringAfter("## 9. 方法与局限\n").trim().lines().forEach {
            assertTrue("Missing original method paragraph: $it", methodText.contains(it))
        }
        assertTrue(methodText.contains("停留时长不能代表实际阅读、理解或观看时间"))
        assertTrue(methodText.contains("判定规则：回查近7天"))
        assertFalse(content.copy(blocks = content.blocks.take(8)).words().contains("判定规则："))
    }

    @Test fun hoursUseSplitDurationAndEmotionCountsKeepTheirKnownWorkDenominator() {
        val content = ReportPdfContent.from(report().copy(hourlyReviews = listOf(
            HourlyReview(3, 2, 5_000, 1, listOf(11, 22), listOf(TopicCount("就业", 2))),
            HourlyReview(4, 1, 7_000, 0, listOf(22), listOf(TopicCount("就业", 1)))),
            emotionExposure = EmotionExposureSummary(knownWorks = 2, unknownWorks = 3, counts = listOf(
                EmotionCategoryCount(EmotionCategory.ANXIETY, 2, listOf(11, 22)),
                EmotionCategoryCount(EmotionCategory.ANGER, 2, listOf(11, 22))))))
        val hours = content.blocks.filterIsInstance<ReportPdfBlock.Hours>().single()
        assertEquals(24, hours.values.size)
        assertEquals(5.0, hours.values[3], 0.0)
        assertEquals(7.0, hours.values[4], 0.0)
        assertEquals("秒", hours.unit)
        val emotionBars = content.blocks.filterIsInstance<ReportPdfBlock.Bars>().flatMap { it.rows }
            .filter { it.label in listOf("焦虑", "愤怒") }
        assertEquals(2, emotionBars.size)
        assertTrue(emotionBars.all { it.display == "2/2 个作品" && !it.display.contains("%") })
        assertTrue(content.words().contains("已分析 2 个作品；未分析 3 个作品"))
    }
}
