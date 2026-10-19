package com.emerge.app.analysis

/** Page compositor: cards in a row share a grid, but each column keeps its own flow cursor. */
internal class ReportPdfDocumentLayout(
    private val measure: (String, Float, Boolean) -> Float,
    private val checkCancellation: () -> Unit = {}
) {
    private val c = PdfReportColors
    private val flow = ReportPdfCardFlow(measure, checkCancellation)
    private val left = 28f
    private val width = 539f
    private val top = 54f
    private val bottom = 784f
    private val padding = 12f
    private val gap = 12f
    private val pages = mutableListOf<PdfPage>()
    private var commands = mutableListOf<PdfDraw>()
    private val pendingTitles = mutableListOf<PdfFlowPiece>()
    private var y = top
    private lateinit var document: ReportPdfDocument

    private data class CardFlow(val width: Float, val pieces: List<PdfFlowPiece>,
        val continuation: List<PdfFlowPiece>, var offset: Int = 0)

    fun layout(document: ReportPdfDocument): List<PdfPage> {
        this.document = document
        pages.clear()
        pendingTitles.clear()
        commands = mutableListOf()
        page()
        val hero = flow.text(document.title, width - padding * 2, 20f, true, gap = 8f, keep = true) +
            document.metadata.flatMap { flow.text(it, width - padding * 2, 8f, color = c.muted, gap = 3f, keep = true) } +
            flow.text("浏览目的：${document.purpose}", width - padding * 2, 9f, fill = c.subtle, gap = 0f)
        row(listOf(CardFlow(width, hero, emptyList())))
        document.metrics.chunked(4).forEach { metrics ->
            val cellWidth = (width - gap * (metrics.size - 1)) / metrics.size
            row(metrics.map { CardFlow(cellWidth, flow.metric(it, cellWidth - padding * 2), emptyList()) })
        }
        document.sections.forEach { section ->
            checkCancellation()
            if (section.title.isNotBlank()) sectionTitle(section.title)
            if (section.intro.isNotEmpty()) row(listOf(CardFlow(width,
                flow.content(section.intro, width - padding * 2), emptyList())))
            section.rows.forEach { model ->
                if (model.cards.isEmpty()) return@forEach
                val weights = model.weights.takeIf { it.size == model.cards.size && it.all { w -> w > 0 && w.isFinite() } }
                    ?: List(model.cards.size) { 1f }
                val available = width - gap * (model.cards.size - 1)
                row(model.cards.mapIndexed { index, card -> prepare(card, available * weights[index] / weights.sum()) })
            }
        }
        flushTitles()
        finish()
        if (document.appendix.blocks.isNotEmpty()) {
            pages += ReportPdfLayout(measure, pageNumberOffset = pages.size, checkCancellation = checkCancellation).layout(document.appendix)
        }
        return pages.toList()
    }

    private fun prepare(card: PdfDashboardCard, width: Float): CardFlow {
        val inner = width - padding * 2
        val title = flow.text(card.title, inner, 11f, true, gap = 7f, keep = true, hardKeep = true)
        val badge = flow.text(card.badge, inner, 8f, true, c.ink(card.tone), 7f, true, c.soft(card.tone), hardKeep = true)
        val pieces = title + badge + card.gauge?.let { flow.gauge(it, inner, card.tone) }.orEmpty() + flow.content(card.blocks, inner)
        val continuationTitle = flow.wrap(card.title, inner, 9f, true).firstOrNull().orEmpty()
        val continuation = flow.text(continuationTitle, inner, 9f, true, color = c.secondary, gap = 7f)
        return CardFlow(width, pieces, continuation)
    }

    private fun sectionTitle(title: String) {
        pendingTitles += flow.text(title, width, 13f, true, gap = 8f)
    }

    private fun flushTitles() {
        pendingTitles.forEach { piece ->
            if (y + piece.height > bottom) page()
            commands += piece.commands.map { it.shift(left, y) }
            y += piece.height
        }
        pendingTitles.clear()
    }

    private fun sum(pieces: List<PdfFlowPiece>) = pieces.sumOf { it.height.toDouble() }.toFloat()

    /** Prefer a semantic boundary. An oversized paragraph can always make progress line by line. */
    private fun count(card: CardFlow, available: Float, fullPage: Float): Int {
        val remainingHeight = sum(card.pieces.subList(card.offset, card.pieces.size))
        if (remainingHeight <= fullPage + .01f)
            return if (remainingHeight <= available + .01f) card.pieces.size - card.offset else 0
        var used = 0f
        var end = card.offset
        while (end < card.pieces.size && used + card.pieces[end].height <= available + .01f) {
            used += card.pieces[end].height
            end++
        }
        if (end == card.pieces.size) return end - card.offset
        var boundary = end
        while (boundary > card.offset && card.pieces[boundary - 1].keepWithNext) boundary--
        if (boundary > card.offset) return boundary - card.offset
        var chain = 0f
        var cursor = card.offset
        do {
            chain += card.pieces[cursor].height
            cursor++
        } while (cursor < card.pieces.size && card.pieces[cursor - 1].keepWithNext)
        if (chain <= fullPage + .01f) return 0
        // Split oversized paragraphs, but keep a heading/source attached to the first body line.
        boundary = end
        while (boundary > card.offset && card.pieces[boundary - 1].mustKeepWithNext) boundary--
        if (boundary > card.offset) return boundary - card.offset
        var requiredHeight = 0f
        cursor = card.offset
        do {
            requiredHeight += card.pieces[cursor].height
            cursor++
        } while (cursor < card.pieces.size && card.pieces[cursor - 1].mustKeepWithNext)
        // Even an unbounded source list must be able to continue across full pages.
        return if (requiredHeight > fullPage + .01f) end - card.offset else 0
    }

    private fun row(cards: List<CardFlow>) {
        while (cards.any { it.offset < it.pieces.size }) {
            checkCancellation()
            // Keep section headings with the first measured card chunk, including long quotes.
            // Extremely long user-provided titles still flow across pages line by line.
            while (sum(pendingTitles) > bottom - top - padding * 2 - 48f) {
                val piece = pendingTitles.removeAt(0)
                if (y + piece.height > bottom) page()
                commands += piece.commands.map { it.shift(left, y) }
                y += piece.height
            }
            val headingHeight = sum(pendingTitles)
            if (y + headingHeight + padding * 2 + 24f > bottom) page()
            val available = bottom - y - padding * 2 - headingHeight
            val capacity = bottom - top - padding * 2 - headingHeight
            val prefixes = cards.map { card ->
                if (card.offset == 0 || card.offset == card.pieces.size) emptyList()
                else card.continuation + listOfNotNull(card.pieces[card.offset].repeatBefore)
            }
            val counts = cards.mapIndexed { i, card ->
                if (card.offset == card.pieces.size) 0
                else count(card, available - sum(prefixes[i]), capacity - sum(prefixes[i]))
            }
            val waiting = cards.indices.any { cards[it].offset < cards[it].pieces.size && counts[it] == 0 }
            if (waiting) {
                if (y > top + .01f) { page(); continue }
                // All content producers emit line-sized pieces or bounded chart/metric pieces.
                check(cards.indices.none { cards[it].offset < cards[it].pieces.size && counts[it] == 0 }) {
                    "Report card contains an element taller than the printable page"
                }
            }
            val heights = cards.indices.map { i ->
                if (counts[i] == 0) 0f else sum(prefixes[i]) + sum(cards[i].pieces.subList(cards[i].offset, cards[i].offset + counts[i])) + padding * 2
            }
            val height = heights.maxOrNull() ?: 0f
            flushTitles()
            var x = left
            cards.forEachIndexed { index, card ->
                if (counts[index] > 0) {
                    commands += PdfDraw.Rect(x, y, card.width, height, c.line, 10f)
                    commands += PdfDraw.Rect(x + .65f, y + .65f, card.width - 1.3f, height - 1.3f, c.white, 9.35f)
                    var baseline = y + padding
                    val selected = prefixes[index] + card.pieces.subList(card.offset, card.offset + counts[index])
                    selected.forEach { piece ->
                        checkCancellation()
                        commands += piece.commands.map { it.shift(x + padding, baseline) }
                        baseline += piece.height
                    }
                    card.offset += counts[index]
                }
                x += card.width + gap
            }
            y += height + gap
            if (cards.any { it.offset < it.pieces.size }) page()
        }
    }

    private fun finish() {
        if (commands.isEmpty()) return
        commands += PdfDraw.Line(left, 801f, left + width, 801f, c.line, .5f)
        commands += PdfDraw.Text(document.period, left, 817f, 8f, c.muted)
        val number = (pages.size + 1).toString()
        commands += PdfDraw.Text(number, left + width - measure(number, 8f, false), 817f, 8f, c.muted)
        pages += PdfPage(commands.toList())
        commands.clear()
    }

    private fun page() {
        checkCancellation()
        finish()
        commands = mutableListOf(PdfDraw.Rect(0f, 0f, 595f, 842f, c.background))
        commands += PdfDraw.Text("破茧 | 浏览回顾", left, 29f, 9f, c.ink, true)
        commands += PdfDraw.Line(left, 40f, left + width, 40f, c.line, .5f)
        y = top
    }
}
