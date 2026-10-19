package com.emerge.app.analysis

import org.junit.Assert.*
import org.junit.Test

class ReportPdfDocumentLayoutTest {
    private fun measure(text: String, size: Float, bold: Boolean) =
        text.codePoints().toArray().sumOf { if (it < 128) .55 else 1.0 }.toFloat() * size
    private fun document(vararg sections: PdfDashboardSection) = ReportPdfDocument("浏览回顾报告", "今天",
        listOf("统计范围：今天"), "未填写", emptyList(), sections.toList(), ReportPdfContent("附录", "今天", emptyList()))
    private fun render(value: ReportPdfDocument) = ReportPdfDocumentLayout(::measure).layout(value)

    @Test fun overviewUsesTwoActualColumnsWithTheRequestedRatio() {
        val section = PdfDashboardSection("", listOf(PdfDashboardRow(listOf(
            PdfDashboardCard("信息视野", listOf(ReportPdfBlock.Paragraph("左侧数据"))),
            PdfDashboardCard("24 小时分布", listOf(ReportPdfBlock.Paragraph("右侧数据")))
        ), listOf(1.15f, .85f))))
        val page = render(document(section)).single()
        val texts = page.commands.filterIsInstance<PdfDraw.Text>()
        val left = texts.single { it.text == "左侧数据" }
        val right = texts.single { it.text == "右侧数据" }
        assertEquals(left.y, right.y, .01f)
        assertTrue(right.x - left.x > 300f)
        val cards = page.commands.filterIsInstance<PdfDraw.Rect>().filter { it.x > 0f && it.radius == 10f }
        assertEquals(1.15 / .85, cards[cards.size - 2].width / cards.last().width.toDouble(), .0001)
    }

    @Test fun unevenColumnsPreserveEveryQuoteAndSourceWithoutCrossingTheGutter() {
        val quote = "逐字保留🙂组合e\u0301。".repeat(700)
        val section = PdfDashboardSection("主题观点", listOf(PdfDashboardRow(listOf(
            PdfDashboardCard("观点甲", listOf(ReportPdfBlock.Paragraph(quote, ReportPdfBlock.Kind.QUOTE),
                ReportPdfBlock.Paragraph("记录 #1", ReportPdfBlock.Kind.SOURCE))),
            PdfDashboardCard("观点乙", listOf(ReportPdfBlock.Paragraph("短观点", ReportPdfBlock.Kind.QUOTE),
                ReportPdfBlock.Paragraph("记录 #2", ReportPdfBlock.Kind.SOURCE)))
        ))))
        val pages = render(document(section))
        val text = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Text>()
        val quotes = text.filter { it.size == 9.5f && it.bold && it.text != "短观点" }
        assertEquals(quote, quotes.joinToString("") { it.text })
        assertEquals(1, text.count { it.text == "记录 #1" })
        assertEquals(1, text.count { it.text == "记录 #2" })
        assertTrue(quotes.all { it.x + measure(it.text, it.size, it.bold) < 292f && it.y < 784f })
        assertTrue(quotes.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() || it.text.startsWith('\u0301') })
        assertTrue(pages.all { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.y in 50f..790f } })
    }

    @Test fun shortQuoteAndItsAttributionShareACardPageEvenWhenAnotherQuoteFollows() {
        (10..35).forEach { count ->
            val blocks = List(count) { ReportPdfBlock.Paragraph("正文") } + listOf(
                ReportPdfBlock.Paragraph("原文甲", ReportPdfBlock.Kind.QUOTE),
                ReportPdfBlock.Paragraph("记录甲", ReportPdfBlock.Kind.SOURCE),
                ReportPdfBlock.Paragraph("原文乙", ReportPdfBlock.Kind.QUOTE))
            val pages = render(document(PdfDashboardSection("", listOf(PdfDashboardRow(listOf(PdfDashboardCard("观点", blocks)))))))
            fun pageOf(s: String) = pages.indexOfFirst { it.commands.filterIsInstance<PdfDraw.Text>().any { text -> text.text == s } }
            assertEquals(pageOf("原文甲"), pageOf("记录甲"))
        }
    }

    @Test fun longTableRepeatsItsHeaderAndRetainsEveryRow() {
        val table = ReportPdfBlock.Table(listOf("来源", "依据"), List(50) { listOf("#$it", "依据内容".repeat(8)) }, listOf(1f, 3f))
        val pages = render(document(PdfDashboardSection("主题观点", listOf(PdfDashboardRow(listOf(
            PdfDashboardCard("记录", listOf(table)), PdfDashboardCard("对照", listOf(ReportPdfBlock.Paragraph("短内容")))
        ))))))
        val text = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Text>()
        assertEquals(50, text.count { it.text.startsWith("#") })
        pages.filter { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text.startsWith("#") } }.forEach { page ->
            assertTrue(page.commands.filterIsInstance<PdfDraw.Text>().any { it.text == "来源" })
        }
    }

    @Test fun titleAndLeadingSourceStayWithTheFirstLineOfAnOversizedQuote() {
        (20..24).forEach { count ->
            val pages = render(document(PdfDashboardSection("", listOf(
                PdfDashboardRow(listOf(PdfDashboardCard("占位", List(count) { ReportPdfBlock.Paragraph("正文") }))),
                PdfDashboardRow(listOf(PdfDashboardCard("情绪", listOf(
                    ReportPdfBlock.Paragraph("记录 #42", ReportPdfBlock.Kind.SOURCE, keepWithNext = true),
                    ReportPdfBlock.Paragraph("长原文".repeat(1000), ReportPdfBlock.Kind.QUOTE)))))
            ))))
            val firstQuote = pages.indexOfFirst { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text.startsWith("长原文") } }
            val source = pages.indexOfFirst { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text == "记录 #42" } }
            val title = pages.indexOfFirst { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text == "情绪" } }
            assertEquals("source at filler count $count", firstQuote, source)
            assertEquals("title at filler count $count", firstQuote, title)
        }
    }

    @Test fun tableSpacingCannotCreateAnEmptyContinuationCard() {
        val pages = render(document(PdfDashboardSection("", listOf(
            PdfDashboardRow(listOf(PdfDashboardCard("占位", emptyList()))),
            PdfDashboardRow(listOf(PdfDashboardCard("记录", listOf(
                ReportPdfBlock.Table(listOf("来源"), List(22) { listOf("#$it") })))))
        ))))
        pages.forEach { page ->
            val texts = page.commands.filterIsInstance<PdfDraw.Text>()
            if (texts.any { it.text == "记录" }) assertTrue(texts.any { it.text.startsWith("#") })
        }
    }

    @Test fun sectionHeadingStaysWithItsFirstCardWhenTheQuoteMovesToANewPage() {
        (15..28).forEach { count ->
            val pages = render(document(
                PdfDashboardSection("", listOf(PdfDashboardRow(listOf(
                    PdfDashboardCard("占位", List(count) { ReportPdfBlock.Paragraph("正文") }))))),
                PdfDashboardSection("主题标题", listOf(PdfDashboardRow(listOf(PdfDashboardCard("观点",
                    listOf(ReportPdfBlock.Paragraph("主题原文".repeat(60), ReportPdfBlock.Kind.QUOTE)))))))
            ))
            val heading = pages.indexOfFirst { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text == "主题标题" } }
            val quote = pages.indexOfFirst { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text.startsWith("主题原文") } }
            assertEquals("filler count $count", quote, heading)
        }
    }

    @Test fun deduplicatedHistoricalSourcesStayWithTheirEvidenceAtThePageBoundary() {
        (20..32).forEach { count ->
            val blocks = List(count) { ReportPdfBlock.Paragraph("正文") } + listOf(
                ReportPdfBlock.Paragraph("同一段依据", ReportPdfBlock.Kind.EVIDENCE, keepWithNext = true),
                ReportPdfBlock.Paragraph("对应记录：#11", ReportPdfBlock.Kind.SOURCE, keepWithNext = true),
                ReportPdfBlock.Paragraph("历史依据 #91、#92", ReportPdfBlock.Kind.SOURCE),
                ReportPdfBlock.Paragraph("下一项依据", ReportPdfBlock.Kind.EVIDENCE))
            val pages = render(document(PdfDashboardSection("", listOf(PdfDashboardRow(listOf(PdfDashboardCard("依据", blocks)))))))
            fun pageOf(text: String) = pages.indexOfFirst { page -> page.commands.filterIsInstance<PdfDraw.Text>().any { it.text == text } }
            assertEquals(pageOf("同一段依据"), pageOf("历史依据 #91、#92"))
        }
    }

    @Test fun chartCardMovesAsAWholeWhenItFitsOnOnePage() {
        val chart = PdfDashboardSection("内容情绪", emptyList(), listOf(ReportPdfBlock.Bars(
            List(5) { PdfBar("情绪类别$it", it.toDouble(), "$it/10", 10.0) })))
        val pages = render(document(PdfDashboardSection("", listOf(PdfDashboardRow(listOf(
            PdfDashboardCard("占位", List(23) { ReportPdfBlock.Paragraph("正文") }))))) , chart))
        val chartPages = pages.mapIndexedNotNull { index, page ->
            index.takeIf { page.commands.filterIsInstance<PdfDraw.Text>().any { it.text.startsWith("情绪类别") } }
        }
        assertEquals(1, chartPages.size)
    }

    @Test fun appendixPageNumbersContinueFromTheDashboard() {
        val value = document().copy(appendix = ReportPdfContent("附录", "今天", listOf(ReportPdfBlock.Paragraph("原始记录"))))
        val pages = render(value)
        assertEquals(2, pages.size)
        val footer = pages.last().commands.filterIsInstance<PdfDraw.Text>().single { it.y > 800f && it.x > 500f }
        assertEquals("2", footer.text)
    }

    @Test fun cancellationInterruptsCardMeasurement() {
        var calls = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ReportPdfDocumentLayout(::measure) { if (++calls > 5) throw java.util.concurrent.CancellationException() }
                .layout(document(PdfDashboardSection("", listOf(PdfDashboardRow(listOf(PdfDashboardCard("观点",
                    listOf(ReportPdfBlock.Paragraph("长原文".repeat(1000))))))))))
        }
    }
}
