package com.emerge.app.analysis

internal sealed interface PdfDraw {
    data class Text(val text: String, val x: Float, val y: Float, val size: Float,
        val color: Int, val bold: Boolean = false) : PdfDraw
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float,
        val color: Int, val radius: Float = 0f) : PdfDraw
    data class Line(val x: Float, val y: Float, val endX: Float, val endY: Float,
        val color: Int, val width: Float = 1f) : PdfDraw
}
internal data class PdfPage(val commands: List<PdfDraw>)

internal class ReportPdfLayout(
    private val measure: (String, Float, Boolean) -> Float,
    private val checkCancellation: () -> Unit = {}
) {
    private val ink = 0xff0f172a.toInt()
    private val secondary = 0xff475569.toInt()
    private val muted = 0xff64748b.toInt()
    private val accent = 0xff2563eb.toInt()
    private val teal = 0xff0d9488.toInt()
    private val pale = 0xffeff6ff.toInt()
    private val subtle = 0xfff1f5f9.toInt()
    private val background = 0xfff8fafc.toInt()
    private val border = 0xffe2e8f0.toInt()
    private val white = 0xffffffff.toInt()
    private val left = 44f
    private val width = 507f
    private val top = 62f
    private val inset = 16f
    private val bottom = 766f
    private var y = 62f
    private var cardStart = -1
    private var cardTop = 0f
    private lateinit var content: ReportPdfContent
    private val pages = mutableListOf<PdfPage>()
    private var commands = mutableListOf<PdfDraw>()

    fun layout(content: ReportPdfContent): List<PdfPage> {
        this.content = content
        pages.clear()
        commands = mutableListOf()
        cardStart = -1
        newPage()
        startCard()
        val titleLines = wrap(content.title, width, 23f, true)
        titleLines.forEach { line ->
            ensure(32f)
            text(line, left, y + 23f, 23f, ink, true)
            y += 32f
        }
        y += 12f
        content.blocks.forEachIndexed { index, block ->
            checkCancellation()
            when (block) {
                is ReportPdfBlock.Heading -> heading(block, content.blocks.getOrNull(index + 1))
                is ReportPdfBlock.Paragraph -> paragraph(block, content.blocks.getOrNull(index + 1))
                is ReportPdfBlock.Metrics -> metrics(block)
                is ReportPdfBlock.Bars -> bars(block)
                is ReportPdfBlock.Table -> table(block)
                is ReportPdfBlock.Hours -> hours(block)
                ReportPdfBlock.PageBreak -> {
                    endCard()
                    if (y > top) newPage()
                }
            }
        }
        finishPage()
        return pages.toList()
    }

    private fun finishPage() {
        if (commands.isEmpty()) return
        endCard()
        line(left, 800f, left + width, 800f, border)
        text(content.period, left, 818f, 9f, muted)
        val number = (pages.size + 1).toString()
        text(number, left + width - measure(number, 9f, false), 818f, 9f, muted)
        pages += PdfPage(commands.toList())
        commands.clear()
    }

    private fun newPage() {
        checkCancellation()
        val continuing = cardStart >= 0
        finishPage()
        commands = mutableListOf()
        rect(0f, 0f, 595f, 842f, background)
        rect(left, 20f, 4f, 12f, accent, 2f)
        text("破茧 | 浏览回顾", left + 12f, 30f, 9f, secondary, true)
        line(left, 42f, left + width, 42f, border)
        y = top
        if (continuing) startCard()
    }

    private fun startCard() {
        cardTop = y
        cardStart = commands.size
        y += inset
    }

    private fun endCard() {
        if (cardStart < 0) return
        // Insert the measured background behind the content, including on continuation pages.
        val end = minOf(y + 8f, bottom + inset)
        if (commands.size > cardStart) commands.addAll(cardStart, listOf(
            PdfDraw.Rect(left - inset, cardTop, width + inset * 2, end - cardTop, border, 12f),
            PdfDraw.Rect(left - inset + .75f, cardTop + .75f, width + inset * 2 - 1.5f,
                end - cardTop - 1.5f, white, 11.25f)
        ))
        cardStart = -1
        y = end + 16f
    }

    private fun ensure(height: Float) { if (y + height > bottom) newPage() }
    private fun text(value: String, x: Float, baseline: Float, size: Float, color: Int = ink, bold: Boolean = false) {
        commands += PdfDraw.Text(value, x, baseline, size, color, bold)
    }
    private fun rect(x: Float, top: Float, w: Float, h: Float, color: Int, radius: Float = 0f) {
        commands += PdfDraw.Rect(x, top, w.coerceAtLeast(0f), h.coerceAtLeast(0f), color, radius)
    }
    private fun line(x: Float, top: Float, right: Float, end: Float, color: Int, stroke: Float = 1f) {
        commands += PdfDraw.Line(x, top, right, end, color, stroke)
    }

    private fun heading(block: ReportPdfBlock.Heading, following: ReportPdfBlock?) {
        val major = block.level == 1
        val size = if (major) 16f else if (block.level == 2) 13f else 10f
        val lines = wrap(block.text, width - if (block.level == 3) 20f else 0f, size, true)
        val height = lines.size * (size + 7f) + 14f
        val reserve = when (following) {
            is ReportPdfBlock.Hours -> 190f
            is ReportPdfBlock.Metrics -> 100f
            is ReportPdfBlock.Table -> 80f
            else -> 56f
        }
        if (major) endCard()
        ensure(minOf(height + reserve + if (cardStart < 0) inset else 0f, bottom - top - inset))
        if (cardStart < 0) startCard()
        if (!major && y > top + inset) y += 6f
        var offset = 0
        val lineHeight = size + 7f
        while (offset < lines.size) {
            val remainingHeight = (lines.size - offset) * lineHeight + 12f
            ensure(if (remainingHeight + reserve <= bottom - top - inset) remainingHeight + reserve else lineHeight + 12f)
            val count = ((bottom - y - 12f) / lineHeight).toInt().coerceAtLeast(1).coerceAtMost(lines.size - offset)
            val chunk = lines.subList(offset, offset + count)
            if (block.level == 3) rect(left, y, minOf(width,
                (chunk.maxOfOrNull { measure(it, size, true) } ?: 0f) + 20f), count * lineHeight + 6f, subtle, 5f)
            chunk.forEach {
                text(it, left + if (block.level == 3) 10f else 0f, y + size + if (block.level == 3) 3f else 0f,
                    size, if (major) ink else if (block.level == 2) secondary else accent, true)
                y += lineHeight
            }
            offset += count
            if (offset < lines.size) newPage()
        }
        if (major) {
            line(left, y + 1f, left + width, y + 1f, border, .75f)
            line(left, y + 1f, left + 26f, y + 1f, accent, 2f)
        }
        y += 12f
    }

    private fun paragraph(block: ReportPdfBlock.Paragraph, following: ReportPdfBlock?) {
        if (block.text.isEmpty()) return
        if (cardStart < 0) { ensure(56f); startCard() }
        val evidence = block.kind == ReportPdfBlock.Kind.EVIDENCE
        val quote = block.kind == ReportPdfBlock.Kind.QUOTE || evidence
        val source = block.kind == ReportPdfBlock.Kind.SOURCE
        val size = when (block.kind) {
            ReportPdfBlock.Kind.SOURCE, ReportPdfBlock.Kind.NOTE -> 9f
            ReportPdfBlock.Kind.EVIDENCE -> 10f
            else -> 11f
        }
        val lineHeight = size * 1.55f
        val textInset = if (evidence) 25f else if (quote) 14f else if (source) 10f else 0f
        val bold = block.kind == ReportPdfBlock.Kind.QUOTE
        val lines = wrap(block.text, width - textInset * 2, size, bold)
        val padding = if (quote) 12f else if (source) 6f else 0f
        val ownHeight = lines.size * lineHeight + padding * 2
        // Keep a short quote with its first attribution, and an evidence label with its excerpt.
        val keepNext = when {
            block.kind == ReportPdfBlock.Kind.QUOTE && following is ReportPdfBlock.Paragraph &&
                following.kind == ReportPdfBlock.Kind.SOURCE && !following.keepWithNext -> 36f
            block.keepWithNext && following is ReportPdfBlock.Paragraph && following.kind in
                listOf(ReportPdfBlock.Kind.QUOTE, ReportPdfBlock.Kind.EVIDENCE) -> 56f
            else -> 0f
        }
        if (keepNext > 0f && ownHeight + keepNext <= bottom - top - inset) ensure(ownHeight + keepNext)
        var offset = 0
        while (offset < lines.size) {
            checkCancellation()
            ensure(lineHeight + padding * 2)
            val count = ((bottom - y - padding * 2) / lineHeight).toInt().coerceAtLeast(1).coerceAtMost(lines.size - offset)
            val height = count * lineHeight + padding * 2
            if (quote) {
                val indent = if (evidence) 12f else 0f
                rect(left + indent, y, width - indent * 2, height, if (evidence) subtle else pale, 8f)
                rect(left + indent, y + 10f, 3f, height - 20f, if (evidence) teal else accent, 1.5f)
            }
            if (source) rect(left, y, width, height, background, 5f)
            repeat(count) { index -> text(lines[offset + index], left + textInset, y + padding + size + index * lineHeight,
                size, if (block.kind in listOf(ReportPdfBlock.Kind.SOURCE, ReportPdfBlock.Kind.NOTE)) muted else ink, bold) }
            y += height
            offset += count
            if (offset < lines.size) newPage()
        }
        y += if (source) 3f else 10f
    }

    private fun metrics(block: ReportPdfBlock.Metrics) {
        block.items.chunked(4).forEach { row ->
            val gap = 10f
            val cellWidth = (width - gap * (row.size - 1)) / row.size
            val labelLines = row.map { wrap(it.label, cellWidth - 24f, 9f, false) }
            val sizes = row.map { metric ->
                var size = 25f
                while (size > 12f && measure(metric.value, size, true) > cellWidth - 24f) size -= 1f
                size
            }
            val valueLines = row.mapIndexed { i, cell -> wrap(cell.value, cellWidth - 24f, sizes[i], true) }
            val labelHeight = labelLines.maxOf { it.size } * 13f
            val valueHeight = valueLines.indices.maxOf { valueLines[it].size * (sizes[it] + 5f) }
            val height = labelHeight + valueHeight + 32f
            ensure(height)
            row.forEachIndexed { index, _ ->
                val x = left + index * (cellWidth + gap)
                rect(x, y, cellWidth, height, border, 9f)
                rect(x + .75f, y + .75f, cellWidth - 1.5f, height - 1.5f, white, 8.25f)
                labelLines[index].forEachIndexed { j, label -> text(label, x + 12f, y + 18f + j * 13f, 9f, muted) }
                valueLines[index].forEachIndexed { j, value ->
                    text(value, x + 12f, y + 16f + labelHeight + sizes[index] + j * (sizes[index] + 5f),
                        sizes[index], if (index == 0) accent else if (index == 2) teal else ink, true)
                }
            }
            y += height + 14f
        }
    }

    private fun bars(block: ReportPdfBlock.Bars) {
        val maximum = block.rows.maxOfOrNull { it.value.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 } ?: 0.0
        block.rows.forEach { row ->
            checkCancellation()
            val labels = wrap(row.label, width * .55f, 10f, true)
            val values = wrap(row.display, width * .42f, 9f, false)
            val labelHeight = maxOf(labels.size, values.size) * 14f
            ensure(labelHeight + 22f)
            labels.forEachIndexed { index, value -> text(value, left, y + 10f + index * 14f, 10f, ink, true) }
            values.forEachIndexed { index, value -> text(value, left + width - measure(value, 9f, false),
                y + 10f + index * 14f, 9f, muted) }
            rect(left, y + labelHeight + 5f, width, 6f, subtle, 3f)
            val scale = row.total?.takeIf { it > 0 && it.isFinite() } ?: maximum
            val fraction = if (scale > 0 && row.value.isFinite()) (row.value / scale).coerceIn(0.0, 1.0) else 0.0
            if (fraction > 0) rect(left, y + labelHeight + 5f, (width * fraction).toFloat(), 6f, accent, 3f)
            y += labelHeight + 22f
        }
        y += 6f
    }

    private fun table(block: ReportPdfBlock.Table) {
        val n = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
        if (n == 0) return
        val keyed = block.headers.isEmpty()
        val rowLineHeight = if (keyed) 13f else 14f
        val rowPadding = 10f
        val weights = block.weights.takeIf { it.size == n && it.all { weight -> weight > 0 && weight.isFinite() } } ?: List(n) { 1f }
        val widths = weights.map { width * it / weights.sum() }
        val headers = block.headers.mapIndexed { index, value -> wrap(value, widths[index] - 16f, 9f, true) }
        val headerHeight = if (keyed) 0f else headers.maxOf { it.size } * 14f + 14f
        fun header() {
            if (keyed) return
            rect(left, y, width, headerHeight, subtle, 5f)
            var x = left
            headers.forEachIndexed { index, lines ->
                lines.forEachIndexed { lineIndex, value -> text(value, x + 8f, y + 16f + lineIndex * 14f, 9f, secondary, true) }
                x += widths[index]
            }
            y += headerHeight
        }
        ensure(headerHeight + 30f)
        header()
        block.rows.forEachIndexed { rowIndex, cells ->
            checkCancellation()
            val lines = (0 until n).map { index -> wrap(cells.getOrElse(index) { "" }, widths[index] - 16f, 9f, false) }
            val total = lines.maxOf { it.size }
            var offset = 0
            while (offset < total) {
                val desired = (total - offset) * rowLineHeight + rowPadding
                if (y + minOf(desired, bottom - top - inset - headerHeight) > bottom) { newPage(); header() }
                val count = ((bottom - y - rowPadding) / rowLineHeight).toInt().coerceAtLeast(1).coerceAtMost(total - offset)
                val height = count * rowLineHeight + rowPadding
                rect(left, y, width, height, if (rowIndex % 2 == 0) white else background)
                if (keyed) rect(left, y, widths[0], height, subtle)
                var x = left
                lines.forEachIndexed { index, cell ->
                    for (j in 0 until count) cell.getOrNull(offset + j)?.let {
                        text(it, x + 8f, y + rowPadding / 2 + 9f + j * rowLineHeight, 9f,
                            if (keyed || index > 0) secondary else ink)
                    }
                    x += widths[index]
                }
                y += height
                line(left, y, left + width, y, border, .5f)
                offset += count
                if (offset < total) { newPage(); header() }
            }
        }
        y += 14f
    }

    private fun hours(block: ReportPdfBlock.Hours) {
        val values = List(24) { block.values.getOrElse(it) { 0.0 }.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }
        val max = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        ensure(190f)
        text(block.unit, left, y + 10f, 9f, muted)
        val chartLeft = left + 35f
        val chartWidth = width - 35f
        val base = y + 155f
        val height = 120f
        listOf(0f, .5f, 1f).forEach { step ->
            val value = max * step
            val label = if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", value)
            text(label, left, base - step * height + 3f, 8f, muted)
            line(chartLeft, base - step * height, left + width, base - step * height, border, .5f)
        }
        val step = chartWidth / 24
        values.forEachIndexed { index, value ->
            val x = chartLeft + index * step + 3f
            rect(x, base - height, step - 6f, height, subtle, 3f)
            if (value > 0) rect(x, base - (height * value / max).toFloat(), step - 6f, (height * value / max).toFloat(), accent, 2f)
            if (index % 3 == 0 || index == 23) text(index.toString().padStart(2, '0'), x, base + 16f, 8f, muted)
        }
        y += 190f
    }

    /** Wrap on Unicode boundaries; preserve every quote character and explicit line break. */
    private fun wrap(value: String, available: Float, size: Float, bold: Boolean): List<String> = buildList {
        value.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) { add(""); return@forEach }
            val boundaries = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(paragraph) }
            var start = 0
            while (start < paragraph.length) {
                checkCancellation()
                var end = start
                while (end < paragraph.length) {
                    val next = boundaries.following(end)
                    if (end > start && measure(paragraph.substring(start, next), size, bold) > available) break
                    end = next
                }
                fun token(c: Char) = c.isLetterOrDigit() && c.code < 128 || c in "._/-"
                if (end < paragraph.length && token(paragraph[end - 1]) && token(paragraph[end])) {
                    var word = end
                    while (word > start && token(paragraph[word - 1])) word--
                    if (word > start) end = word
                }
                if (end < paragraph.length && paragraph[end] in "，。！？；：、）】》”’%" && end - start > 1) {
                    val previous = boundaries.preceding(end)
                    if (previous > start) end = previous
                }
                add(paragraph.substring(start, end))
                start = end
            }
        }
    }
}
