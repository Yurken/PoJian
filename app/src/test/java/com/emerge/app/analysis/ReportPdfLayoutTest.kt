package com.emerge.app.analysis

import org.junit.Assert.*
import org.junit.Test

class ReportPdfLayoutTest {
    private fun measure(text: String, size: Float, bold: Boolean): Float =
        text.codePoints().toArray().sumOf { if (it < 128) .55 else 1.0 }.toFloat() * size
    private fun render(vararg blocks: ReportPdfBlock) = ReportPdfLayout(::measure)
        .layout(ReportPdfContent("破茧 · 浏览回顾报告", "今天", blocks.toList()))

    @Test fun overviewRendersNumbersAndChartsAsSeparateVisualElements() {
        val pages = render(ReportPdfBlock.Metrics(listOf(PdfMetric("浏览作品（估算）", "185"), PdfMetric("核心观点", "184"))),
            ReportPdfBlock.Bars(listOf(PdfBar("健康与生活", 99.0, "99 / 185 条"))),
            ReportPdfBlock.Hours(List(24) { if (it == 12) 25.0 else 0.0 }, "条记录"))
        assertEquals(1, pages.size)
        val commands = pages.single().commands
        assertTrue(commands.filterIsInstance<PdfDraw.Text>().any { it.text == "185" && it.size >= 22f })
        assertTrue(commands.filterIsInstance<PdfDraw.Text>().any { it.text == "99 / 185 条" })
        assertTrue(commands.filterIsInstance<PdfDraw.Rect>().size >= 27)
    }

    @Test fun longQuoteContinuesWithoutLostCharactersOrFooterOverlap() {
        val quote = "应该逐字保留原文🙂。".repeat(600)
        val pages = render(ReportPdfBlock.Paragraph(quote, ReportPdfBlock.Kind.QUOTE))
        assertTrue(pages.size > 2)
        val lines = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Text>().filter { it.size == 11f }
        assertEquals(quote, lines.joinToString("") { it.text })
        assertTrue(lines.all { it.y in 50f..790f && it.x >= 40f && it.x + measure(it.text, it.size, it.bold) <= 555.1f })
        assertTrue(lines.all { !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
    }

    @Test fun longTableRowsRepeatHeadersAndKeepEveryCell() {
        val rows = List(80) { listOf("来源$it", "原文".repeat(60), "#$it") }
        val pages = render(ReportPdfBlock.Table(listOf("来源", "观点原文", "记录"), rows, listOf(2f, 5f, 1f)))
        assertTrue(pages.size > 3)
        pages.forEach { page ->
            assertTrue(page.commands.filterIsInstance<PdfDraw.Text>().any { it.text == "观点原文" })
        }
        val texts = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Text>().map { it.text }
        assertEquals(80, texts.count { it.startsWith("#") })
        assertTrue(pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Rect>()
            .filter { it.y > 0f }.all { it.y + it.height <= 790f })
    }

    @Test fun cancellationStopsPaginationBeforeProducingAFile() {
        var calls = 0
        val layout = ReportPdfLayout(::measure) { if (++calls > 2) throw java.util.concurrent.CancellationException() }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            layout.layout(ReportPdfContent("报告", "今天", List(100) { ReportPdfBlock.Paragraph("原文".repeat(200)) }))
        }
    }

    @Test fun fieldTablesWithoutHeadersRetainAllSourceDetails() {
        val pages = render(ReportPdfBlock.Table(emptyList(),
            listOf(listOf("观点来源", "云端分析（需核对）"), listOf("对象", "工作边界")), listOf(1f, 4f)))
        val text = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Text>().map { it.text }
        listOf("观点来源", "云端分析（需核对）", "对象", "工作边界").forEach { assertTrue(text.contains(it)) }
    }

    @Test fun proportionBarUsesItsDenominatorInsteadOfFillingTheLargestCategory() {
        val pages = render(ReportPdfBlock.Bars(listOf(PdfBar("健康", 99.0, "99/185 条", 185.0))))
        val bars = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Rect>().filter { it.height == 6f }
        assertEquals(2, bars.size)
        assertEquals(99.0 / 185.0, bars[1].width / bars[0].width.toDouble(), .0001)
    }

    @Test fun punctuationWrappingKeepsCombiningMarksAttachedToTheirLetter() {
        val quote = "甲".repeat(43) + "e\u0301。"
        val pages = ReportPdfLayout({ value, _, _ -> value.fold(0f) { sum, char ->
            sum + if (char == '\u0301') 0f else if (char.code < 128) 6f else 11f
        } })
            .layout(ReportPdfContent("报告", "今天", listOf(ReportPdfBlock.Paragraph(quote, ReportPdfBlock.Kind.QUOTE))))
        val lines = pages.flatMap { it.commands }.filterIsInstance<PdfDraw.Text>().filter { it.size == 11f }
        assertEquals(quote, lines.joinToString("") { it.text })
        assertTrue(lines.none { it.text.startsWith('\u0301') })
    }
}
