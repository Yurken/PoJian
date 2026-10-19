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
    private val ink = 0xff203f3a.toInt()
    private val muted = 0xff52645f.toInt()
    private val accent = 0xff187568.toInt()
    private val pale = 0xffedf5f1.toInt()
    private val border = 0xffdbe5df.toInt()
    private val white = 0xffffffff.toInt()
    private val left = 44f
    private val width = 507f
    private val bottom = 782f
    private var y = 62f
    private lateinit var content: ReportPdfContent
    private val pages = mutableListOf<PdfPage>()
    private var commands = mutableListOf<PdfDraw>()

    fun layout(content: ReportPdfContent): List<PdfPage> {
        this.content = content
        pages.clear()
        commands = mutableListOf()
        newPage()
        val titleLines = wrap(content.title, width, 25f, true)
        titleLines.forEach { line -> text(line, left, y + 25f, 25f, ink, true); y += 34f }
        y += 14f
        content.blocks.forEachIndexed { index, block ->
            checkCancellation()
            when (block) {
                is ReportPdfBlock.Heading -> heading(block, content.blocks.getOrNull(index + 1))
                is ReportPdfBlock.Paragraph -> paragraph(block)
                is ReportPdfBlock.Metrics -> metrics(block)
                is ReportPdfBlock.Bars -> bars(block)
                is ReportPdfBlock.Table -> table(block)
                is ReportPdfBlock.Hours -> hours(block)
                ReportPdfBlock.PageBreak -> if (y > 62f) newPage()
            }
        }
        finishPage()
        return pages.toList()
    }

    private fun finishPage() {
        if (commands.isEmpty()) return
        line(left, 800f, left + width, 800f, border)
        text(content.period, left, 818f, 9f, muted)
        val number = (pages.size + 1).toString()
        text(number, left + width - measure(number, 9f, false), 818f, 9f, muted)
        pages += PdfPage(commands.toList())
        commands.clear()
    }

    private fun newPage() {
        checkCancellation()
        finishPage()
        commands = mutableListOf()
        rect(0f, 0f, 595f, 842f, white)
        text("破茧 | 浏览回顾", left, 30f, 9f, accent, true)
        line(left, 42f, left + width, 42f, border)
        y = 62f
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
        val size = if (block.level == 1) 17f else 12.5f
        val lines = wrap(block.text, width - 18f, size, true)
        val height = lines.size * (size + 7f) + 14f
        val reserve = when (following) {
            is ReportPdfBlock.Hours -> 190f
            is ReportPdfBlock.Metrics -> 100f
            is ReportPdfBlock.Table -> 80f
            else -> 38f
        }
        ensure(minOf(height + reserve, bottom - 62f))
        y += 8f
        if (block.level == 1) rect(left, y + 2f, 4f, size + 2f, accent)
        lines.forEach {
            ensure(size + 7f)
            text(it, left + if (block.level == 1) 14f else 0f, y + size, size, ink, true)
            y += size + 7f
        }
        y += 6f
    }

    private fun paragraph(block: ReportPdfBlock.Paragraph) {
        if (block.text.isEmpty()) return
        val evidence = block.kind == ReportPdfBlock.Kind.EVIDENCE
        val quote = block.kind == ReportPdfBlock.Kind.QUOTE || evidence
        val size = when (block.kind) {
            ReportPdfBlock.Kind.SOURCE, ReportPdfBlock.Kind.NOTE -> 9f
            ReportPdfBlock.Kind.EVIDENCE -> 10f
            else -> 11f
        }
        val lineHeight = size * 1.55f
        val inset = if (evidence) 25f else if (quote) 13f else 0f
        val lines = wrap(block.text, width - inset * 2, size, false)
        var offset = 0
        while (offset < lines.size) {
            checkCancellation()
            ensure(lineHeight + if (quote) 20f else 5f)
            val padding = if (quote) 10f else 0f
            val count = ((bottom - y - padding * 2) / lineHeight).toInt().coerceAtLeast(1).coerceAtMost(lines.size - offset)
            val height = count * lineHeight + padding * 2
            if (quote) {
                val indent = if (evidence) 12f else 0f
                rect(left + indent, y, width - indent * 2, height, if (evidence) 0xfff3f5f4.toInt() else pale, 6f)
                rect(left + indent, y + 8f, 3f, height - 16f, if (evidence) border else accent)
            }
            repeat(count) { index -> text(lines[offset + index], left + inset, y + padding + size + index * lineHeight,
                size, if (block.kind in listOf(ReportPdfBlock.Kind.SOURCE, ReportPdfBlock.Kind.NOTE)) muted else ink) }
            y += height
            offset += count
            if (offset < lines.size) newPage()
        }
        y += if (block.kind == ReportPdfBlock.Kind.SOURCE) 5f else 10f
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
                rect(x, y, cellWidth, height, pale, 7f)
                labelLines[index].forEachIndexed { j, label -> text(label, x + 12f, y + 18f + j * 13f, 9f, muted) }
                valueLines[index].forEachIndexed { j, value ->
                    text(value, x + 12f, y + 16f + labelHeight + sizes[index] + j * (sizes[index] + 5f), sizes[index], accent, true)
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
            ensure(labelHeight + 24f)
            labels.forEachIndexed { index, value -> text(value, left, y + 10f + index * 14f, 10f, ink, true) }
            values.forEachIndexed { index, value -> text(value, left + width - measure(value, 9f, false),
                y + 10f + index * 14f, 9f, muted) }
            rect(left, y + labelHeight + 5f, width, 6f, pale, 3f)
            val scale = row.total?.takeIf { it > 0 && it.isFinite() } ?: maximum
            val fraction = if (scale > 0 && row.value.isFinite()) (row.value / scale).coerceIn(0.0, 1.0) else 0.0
            if (fraction > 0) rect(left, y + labelHeight + 5f, (width * fraction).toFloat(), 6f, accent, 3f)
            y += labelHeight + 24f
        }
        y += 6f
    }

    private fun table(block: ReportPdfBlock.Table) {
        val n = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
        if (n == 0) return
        val keyed = block.headers.isEmpty()
        val rowLineHeight = if (keyed) 13f else 14f
        val rowPadding = if (keyed) 10f else 16f
        val weights = block.weights.takeIf { it.size == n && it.all { weight -> weight > 0 && weight.isFinite() } } ?: List(n) { 1f }
        val widths = weights.map { width * it / weights.sum() }
        val headers = block.headers.mapIndexed { index, value -> wrap(value, widths[index] - 16f, 9f, true) }
        val headerHeight = if (keyed) 0f else headers.maxOf { it.size } * 14f + 14f
        fun header() {
            if (keyed) return
            rect(left, y, width, headerHeight, ink, 3f)
            var x = left
            headers.forEachIndexed { index, lines ->
                lines.forEachIndexed { lineIndex, value -> text(value, x + 8f, y + 16f + lineIndex * 14f, 9f, white, true) }
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
                if (y + minOf(desired, bottom - 62f - headerHeight) > bottom) { newPage(); header() }
                val count = ((bottom - y - rowPadding) / rowLineHeight).toInt().coerceAtLeast(1).coerceAtMost(total - offset)
                val height = count * rowLineHeight + rowPadding
                rect(left, y, width, height, if (rowIndex % 2 == 0) pale else white)
                var x = left
                lines.forEachIndexed { index, cell ->
                    for (j in 0 until count) cell.getOrNull(offset + j)?.let {
                        text(it, x + 8f, y + rowPadding / 2 + 9f + j * rowLineHeight, 9f, if (index == 0) ink else muted)
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
            rect(x, base - height, step - 6f, height, pale, 2f)
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
