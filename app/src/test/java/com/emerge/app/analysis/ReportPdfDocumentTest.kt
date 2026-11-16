package com.emerge.app.analysis

import com.emerge.app.ui.AppShare
import com.emerge.app.ui.BrowseStatistics
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReportPdfDocumentTest {
    @Test fun fourMetricsUseTheirOwnCountsAndRepeatUsesComparableKnownWorks() {
        val sourceText = "研究院独立调查形成材料甲。"
        val provenance = SourceProvenance(true, listOf(EvidenceSource("研究院", "材料甲", "primary", sourceText)))
        val first = claim(1).copy(increment = Increment.NEW,
            evidenceDetails = listOf(EvidenceFragment("岗位数量100个", EvidenceType.FACT, isNew = true,
                identity = EvidenceIdentity())), sourceRecord = SourceRecord(1, sourceText, provenance))
        val repeated = claim(2).copy(increment = Increment.REPEATED,
            evidenceDetails = listOf(EvidenceFragment("岗位数量80个", EvidenceType.FACT,
                historyMatches = listOf(EvidenceHistoryMatch(9, "岗位数量80个")), identity = EvidenceIdentity())),
            sourceRecord = SourceRecord(2, sourceText, provenance))
        val unknown = claim(3).copy(increment = Increment.UNKNOWN)
        val selected = exposure(.6, first, contacts = 20, classified = 10).let {
            it.copy(viewpoints = listOf(it.viewpoints.single().copy(
                longitudinal = LongitudinalEvidenceAssessment(contacts = 3, days = 1, known = 2, repeated = 1))))
        }
        val document = ReportPdfDocument.from(report().copy(claims = listOf(first, repeated, unknown),
            periodExposure = selected,
            workStatistics = BrowseStatistics(8, 1, 100, emptyList(), listOf(AppShare("reader", 8)),
                observedDurationMillis = 12_000, visitCount = 11, unidentifiedWorks = 2),
            emotionExposure = EmotionExposureSummary(knownWorks = 2, unknownWorks = 6)))

        assertEquals(listOf("浏览作品（估算）", "新增事实", "独立来源", "观点"), document.metrics.map { it.label })
        assertEquals(listOf("8 个", "1+", "1+", "3 个"), document.metrics.map { it.value })
        assertTrue(document.metrics.first().details.contains("观看 11 次"))
        assertTrue(document.metrics.first().details.contains("停留时长 12.0 秒"))
        assertEquals(listOf("待分析 1 项"), document.metrics[1].details)
        assertEquals(listOf("待分析 1 项"), document.metrics[2].details)
        assertTrue(document.metrics[3].details.isEmpty())
        val repeat = document.sections.first().rows.single().cards.first().blocks
            .filterIsInstance<ReportPdfBlock.Metrics>().flatMap { it.items }.single { it.label == "重复依据" }
        assertEquals("1/2 个作品（50.0%）", repeat.value)
    }

    @Test fun unavailableLegacyFieldsAreNotRenderedAsZeroDiagnoses() {
        val current = report().copy(workStatistics = null, claims = listOf(claim(1)),
            periodExposure = null, hourlyReviews = emptyList(), hourlyClaims = List(24) { 7 },
            emotionExposure = EmotionExposureSummary())
        val document = ReportPdfDocument.from(current)
        assertEquals(listOf("未统计", "待分析", "待分析", "1 个"), document.metrics.map { it.value })
        assertNull(document.sections.first().rows.single().cards.first().gauge?.fraction)
        assertFalse(document.bodyBlocks().any { it is ReportPdfBlock.Hours })
        assertTrue(document.bodyBlocks().words().contains("旧版本报告没有逐小时统计。"))
        assertFalse(document.bodyBlocks().words().contains("已分析 0 个作品"))
        assertFalse(document.bodyBlocks().words().contains("风险"))
        assertFalse(document.bodyBlocks().words().contains("安全"))
        assertFalse(document.bodyBlocks().words().contains("NaN"))
        assertEquals("—", ReportPdfDocument.from(current.copy(claims = emptyList())).metrics[1].value)
    }

    @Test fun selectedPeriodExposureAndWeeklyAppendixRemainIndependent() {
        val weeklyQuote = "只属于近7天的原文。"
        val weekly = exposure(.95, claim(7, weeklyQuote), contacts = 50, classified = 40)
        val selected = exposure(.6, claim(1, "当前周期原文。"), contacts = 20, classified = 10)
        val current = report().copy(period = "今天", periodExposure = selected, exposure = weekly,
            findings = listOf(ObservationFinding("就业", "近7天观察", "独立的历史观察", listOf(7))))
        val document = ReportPdfDocument.from(current)
        val overview = document.sections.first().rows.single()
        assertEquals(listOf(1.15f, .85f), overview.weights)
        assertEquals(2, overview.cards.size)
        assertEquals("信息视野 · 今天", overview.cards.first().title)
        val gauge = overview.cards.first().gauge!!
        assertEquals(.6, gauge.fraction!!, .0001)
        assertEquals("60.0%", gauge.valueText)
        assertTrue(overview.cards.first().blocks.words().contains("10/20 次"))
        assertTrue(overview.cards.first().blocks.words().contains("0.48"))
        assertTrue(overview.cards.first().blocks.words().contains("重复依据：待分析"))
        assertFalse(document.bodyBlocks().words().contains(weeklyQuote))
        assertTrue(document.appendix.blocks.words().contains(weeklyQuote))
        assertTrue(document.appendix.blocks.words().contains("独立的历史观察"))
        val missing = ReportPdfDocument.from(current.copy(periodExposure = null))
        assertNull(missing.sections.first().rows.single().cards.first().gauge!!.fraction)
    }

    @Test fun overlappingViewpointsAndEmotionCategoriesUseIndependentCardsAndCounts() {
        val sharedWorks = setOf("w1", "w2")
        val viewpoints = (1L..3L).map { view(it).copy(stance = "支持", workKeys = sharedWorks) }
        val document = ReportPdfDocument.from(report().copy(
            topicDigests = listOf(TopicDigest("t", "就业", "岗位", viewpoints)),
            emotionExposure = EmotionExposureSummary(knownWorks = 2, unknownWorks = 3, counts = listOf(
                EmotionCategoryCount(EmotionCategory.ANXIETY, 2, listOf(1, 2)),
                EmotionCategoryCount(EmotionCategory.ANGER, 2, listOf(1, 2))))))
        val topic = document.sections.single { it.title == "就业 · 岗位 · 2 个作品" }
        assertEquals(listOf(2, 1), topic.rows.map { it.cards.size })
        assertTrue(topic.rows.flatMap { it.cards }.all { it.title == "岗位 · 支持 · 2 个作品" })
        assertFalse(topic.rows.flatMap { it.cards }.flatMap { it.blocks }.any { it is ReportPdfBlock.Bars })
        val emotion = document.sections.single { it.title == "内容情绪" }
        val bars = emotion.intro.filterIsInstance<ReportPdfBlock.Bars>().single().rows
        assertEquals(listOf(2.0, 2.0), bars.map { it.total })
        assertEquals(listOf("2/2 个作品", "2/2 个作品"), bars.map { it.display })
        assertFalse(emotion.intro.words().contains("100%"))
    }

    @Test fun quotationsConditionsAndEvidenceKeepTheirOwnSourcesAndExactText() {
        val quotation = "原文包含 com.sina.weibo。\n## 9. 方法与局限\n这一行仍是原文。"
        val first = view(11).copy(quote = quotation, conditions = "仅限2026年",
            observations = listOf(claim(11, quotation), claim(12, quotation)), evidence = listOf(
                DigestEvidence("真实依据\n第二行", EvidenceType.FACT, listOf(11, 12),
                    listOf(EvidenceHistoryMatch(91, "历史原句"), EvidenceHistoryMatch(92, "历史原句")), false)))
        val document = ReportPdfDocument.from(report().copy(topicDigests = listOf(
            TopicDigest("t", "就业", "岗位", listOf(first, view(22))))), mapOf("com.sina.weibo" to "设备应用名"))
        val cards = document.sections.single { it.title.startsWith("就业 ·") }.rows.single().cards
        val paragraphs = cards.first().blocks.filterIsInstance<ReportPdfBlock.Paragraph>()
        assertEquals(quotation, paragraphs.single { it.kind == ReportPdfBlock.Kind.QUOTE }.text)
        assertTrue(paragraphs.any { it.text == "适用条件：仅限2026年" })
        assertEquals(2, paragraphs.count { it.text == "对应记录：#11, #12" && !it.keepWithNext })
        assertTrue(paragraphs.single { it.text == "真实依据\n第二行" }.keepWithNext)
        assertTrue(paragraphs.filter { it.text.startsWith("历史依据 #") }.all { it.keepWithNext })
        assertFalse(paragraphs.single { it.text == "历史原句" }.keepWithNext)
        assertFalse(cards[1].blocks.words().contains("#11"))
        assertFalse(cards[1].blocks.words().contains("历史原句"))
    }

    @Test fun globalViewpointEvidenceAndEmotionLimitsRetainExistingOmissionNotices() {
        val viewpoints = (1L..101L).map { id -> view(id).copy(evidence = (1..2).map { n ->
            DigestEvidence("依据-$id-$n", EvidenceType.FACT, listOf(id),
                listOf(EvidenceHistoryMatch(id + 200, "历史-$id-$n")), false)
        }) }
        val emotions = (1L..101L).map { EmotionEvidence(it, EmotionCategory.ANXIETY, ImpactLevel.UNKNOWN,
            "情绪-$it", "诱因-$it") }
        val document = ReportPdfDocument.from(report().copy(topicDigests = listOf(
            TopicDigest("a", "甲", "岗位", viewpoints.take(50)),
            TopicDigest("b", "乙", "岗位", viewpoints.drop(50))),
            emotionExposure = EmotionExposureSummary(knownWorks = 101,
                works = listOf(EmotionWorkExposure("w", listOf(1), emptyList(), emptyList(), emotions)))))
        val body = document.bodyBlocks()
        val text = body.words()
        val quotes = body.filterIsInstance<ReportPdfBlock.Paragraph>()
            .filter { it.kind == ReportPdfBlock.Kind.QUOTE }.map { it.text }
        assertTrue(quotes.contains("观点-100"))
        assertFalse(quotes.contains("观点-101"))
        assertTrue(text.contains("依据-50-2"))
        assertTrue(text.contains("历史-50-2"))
        assertFalse(text.contains("依据-51-1"))
        assertEquals(100, quotes.count { it.startsWith("情绪-") })
        assertFalse(quotes.contains("情绪-101"))
        listOf("主题观点仅列出前 100 项", "主题依据仅列出前 100 项", "情绪原文仅列出前 100 项")
            .forEach { assertTrue(text.contains(it)) }
    }

    @Test fun identicalCurrentAndHistoricalEvidenceIsPrintedOnceWithEverySource() {
        val current = view(11).copy(evidence = listOf(DigestEvidence("同一段依据", EvidenceType.FACT, listOf(11),
            listOf(EvidenceHistoryMatch(91, "同一段依据"), EvidenceHistoryMatch(92, "同一段依据")), false)))
        val document = ReportPdfDocument.from(report().copy(topicDigests = listOf(TopicDigest("t", "就业", "岗位", listOf(current)))))
        val paragraphs = document.bodyBlocks().filterIsInstance<ReportPdfBlock.Paragraph>()
        assertEquals(1, paragraphs.count { it.text == "同一段依据" })
        assertTrue(paragraphs.any { it.text == "对应记录：#11" })
        assertTrue(paragraphs.any { it.text == "历史依据 #91、#92" })
        assertTrue(paragraphs.first { it.text == "同一段依据" }.keepWithNext)
        assertTrue(paragraphs.last { it.text == "对应记录：#11" }.keepWithNext)
    }

    @Test fun clusteredViewpointsKeepDifferentOriginalQuotesWithTheirOwnRecordIds() {
        val current = view(11).copy(quote = "就业机会增加。", observations = listOf(
            claim(11, "就业机会增加。"), claim(12, "招聘岗位比去年更多。"), claim(13, "就业机会增加。")))
        val document = ReportPdfDocument.from(report().copy(topicDigests = listOf(TopicDigest("t", "就业", "岗位", listOf(current)))))
        val paragraphs = document.bodyBlocks().filterIsInstance<ReportPdfBlock.Paragraph>()
        val first = paragraphs.indexOfFirst { it.text == "就业机会增加。" }
        val second = paragraphs.indexOfFirst { it.text == "招聘岗位比去年更多。" }
        assertTrue(first >= 0 && second >= 0)
        assertEquals("对应记录：#11, #13", paragraphs[first + 1].text)
        assertEquals("对应记录：#12", paragraphs[second + 1].text)
    }

    @Test fun appendixRetainsAllDetailedSectionsOriginalGroupsAndMethods() {
        val original = claim(1, "不能丢失的原始观点")
        val current = report().copy(claims = listOf(original), groups = listOf(ClaimGroup("就业", original.quote, listOf(original))),
            topicDigests = emptyList(), purpose = "目的\n## 7. 建议与核查", method = "自定义方法\n## 4. 主题观点",
            topicExposure = (1..4).map { TopicExposure("主题$it", it, it * 1000L) }, coverageSessions = 10,
            emotionExposure = EmotionExposureSummary(knownWorks = 1, byTopic = listOf(EmotionTopicExposure("就业", 1, 0, emptyList())),
                byDate = listOf(EmotionDateExposure("2026-10-19", 1, 0, emptyList()))))
        val document = ReportPdfDocument.from(current)
        assertEquals(listOf(1, 2, 3, 5, 6, 8, 9), document.appendix.blocks.filterIsInstance<ReportPdfBlock.Heading>()
            .filter { it.level == 1 }.map { it.text.substringBefore('.').toInt() })
        assertEquals(current.purpose, document.purpose)
        assertTrue(document.sections.single { it.title == "主题观点" }.intro.words().contains("未统计"))
        assertTrue(document.appendix.blocks.words().contains(original.quote))
        val overviewBars = document.sections.first().rows.single().cards.first().blocks
            .filterIsInstance<ReportPdfBlock.Bars>().single().rows
        assertEquals(listOf("主题4", "主题3", "主题2"), overviewBars.map { it.label })
        assertTrue(document.appendix.blocks.words().contains("主题1"))
        assertTrue(document.appendix.blocks.filterIsInstance<ReportPdfBlock.Table>().any { it.headers.first() == "主题" })
        assertTrue(document.appendix.blocks.filterIsInstance<ReportPdfBlock.Table>().any { it.headers.first() == "日期" })
        val oldContent = ReportPdfContent.from(current)
        val oldMethods = oldContent.blocks.dropWhile { it !is ReportPdfBlock.Heading || it.text != "9. 方法与局限" }
        val methods = document.appendix.blocks.dropWhile { it !is ReportPdfBlock.Heading || it.text != "9. 方法与局限" }
        assertEquals(oldMethods, methods)
        assertTrue(methods.words().contains(current.method))
        assertTrue(methods.words().contains("停留时长不能代表实际阅读、理解或观看时间"))
    }

    @Test fun hourChartUsesObservedDurationAndNeverSynthesizesMissingHours() {
        val reviews = List(24) { hour -> HourlyReview(hour, 1, hour * 1000L, 0, emptyList(), emptyList()) }
        val current = report().copy(hourlyReviews = reviews, hourlyClaims = List(24) { 99 },
            workStatistics = BrowseStatistics(2, 1, 5, emptyList(), listOf(AppShare("app", 2, 7_000))))
        val document = ReportPdfDocument.from(current, mapOf("app" to "真实应用名"))
        val activity = document.sections.first().rows.single().cards[1]
        val hours = activity.blocks.filterIsInstance<ReportPdfBlock.Hours>().single()
        assertEquals(24, hours.values.size)
        assertEquals(7.0, hours.values[7], 0.0)
        assertEquals("秒", hours.unit)
        assertTrue(activity.blocks.words().contains("真实应用名"))
        assertTrue(activity.blocks.words().contains("7.0 秒"))
        assertEquals("2/2 个作品 · 7.0 秒", activity.blocks.filterIsInstance<ReportPdfBlock.Bars>().single().rows.single().display)
        assertFalse(activity.blocks.any { it is ReportPdfBlock.Table })
        val partial = ReportPdfDocument.from(current.copy(hourlyReviews = reviews.take(2)))
        assertFalse(partial.bodyBlocks().any { it is ReportPdfBlock.Hours })
        assertTrue(partial.bodyBlocks().words().contains("未统计"))
    }

    private fun report() = CognitiveAnalyzer().analyze(emptyList(), StatisticsPeriod.MONTH,
        1_792_123_200_000L, ZoneId.of("Asia/Shanghai"))

    private fun claim(id: Long, quote: String = "观点-$id") = ClaimObservation(id,
        1_792_123_200_000L + id * 1000, "app.$id", "就业", quote, emptyList(), "2026年",
        false, Increment.FIRST, null, subject = "岗位")

    private fun view(id: Long) = DigestViewpoint("v$id", "观点-$id", "岗位", "增加", "2026年",
        setOf("w$id"), listOf(claim(id)), emptyList())

    private fun exposure(share: Double, observation: ClaimObservation, contacts: Int, classified: Int): ExposureAssessment {
        val distribution = ExposureDistribution(contacts, 1, classified,
            mapOf("增加" to share, "减少" to 1 - share), mapOf(observation.packageName to contacts))
        return ExposureAssessment(0, 1, distribution, "就业", null, listOf(ViewpointExposure("v", "就业", "岗位", "需求",
            "增加", distribution, share, null, null, 1, share >= .75, observation, listOf(observation.eventId))), listOf(observation))
    }

    private fun ReportPdfDocument.bodyBlocks() = sections.flatMap { section ->
        section.intro + section.rows.flatMap { row -> row.cards.flatMap { it.blocks } }
    }

    private fun List<ReportPdfBlock>.words() = joinToString("\n") { block ->
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
}
