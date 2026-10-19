package com.emerge.app.analysis

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

internal data class PdfFlowPiece(
    val height: Float,
    val commands: List<PdfDraw>,
    val keepWithNext: Boolean = false,
    val repeatBefore: PdfFlowPiece? = null,
    val mustKeepWithNext: Boolean = false
)

internal object PdfReportColors {
    val ink = 0xff0f172a.toInt()
    val secondary = 0xff475569.toInt()
    val muted = 0xff64748b.toInt()
    val line = 0xffe2e8f0.toInt()
    val background = 0xfff8fafc.toInt()
    val subtle = 0xfff1f5f9.toInt()
    val white = 0xffffffff.toInt()
    fun ink(tone: PdfDashboardTone) = when (tone) {
        PdfDashboardTone.BLUE -> 0xff2563eb.toInt()
        PdfDashboardTone.TEAL -> 0xff0d9488.toInt()
        PdfDashboardTone.AMBER -> 0xff92400e.toInt()
        PdfDashboardTone.ROSE -> 0xff9f1239.toInt()
        PdfDashboardTone.NEUTRAL -> secondary
    }
    fun soft(tone: PdfDashboardTone) = when (tone) {
        PdfDashboardTone.BLUE -> 0xffeff6ff.toInt()
        PdfDashboardTone.TEAL -> 0xfff0fdfa.toInt()
        PdfDashboardTone.AMBER -> 0xfffffbeb.toInt()
        PdfDashboardTone.ROSE -> 0xfffff1f2.toInt()
        PdfDashboardTone.NEUTRAL -> subtle
    }
}

/** Measures content at its actual column width before page placement. No text is clipped. */
internal class ReportPdfCardFlow(
    private val measure: (String, Float, Boolean) -> Float,
    private val cancel: () -> Unit
) {
    private val c = PdfReportColors

    fun wrap(value: String, width: Float, size: Float, bold: Boolean = false): List<String> = buildList {
        value.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) { add(""); return@forEach }
            val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(paragraph) }
            var start = 0
            while (start < paragraph.length) {
                cancel()
                var end = start
                while (end < paragraph.length) {
                    val next = boundaries.following(end)
                    if (end > start && measure(paragraph.substring(start, next), size, bold) > width) break
                    end = next
                }
                fun token(char: Char) = char.isLetterOrDigit() && char.code < 128 || char in "._/-"
                if (end < paragraph.length && token(paragraph[end - 1]) && token(paragraph[end])) {
                    var word = end
                    while (word > start && token(paragraph[word - 1])) word--
                    if (word > start) end = word
                }
                if (end < paragraph.length && paragraph[end] in "，。！？；：、）】》”’%") {
                    val previous = boundaries.preceding(end)
                    if (previous > start) end = previous
                }
                add(paragraph.substring(start, end))
                start = end
            }
        }
    }

    fun text(value: String, width: Float, size: Float = 9f, bold: Boolean = false,
        color: Int = c.ink, gap: Float = 6f, keep: Boolean = false,
        fill: Int? = null, stripe: Int? = null, hardKeep: Boolean = false): List<PdfFlowPiece> {
        if (value.isEmpty()) return emptyList()
        val padding = if (fill != null) 7f else 0f
        val lines = wrap(value, width - padding * 2, size, bold)
        return lines.mapIndexed { index, line ->
            val first = index == 0
            val last = index == lines.lastIndex
            val top = if (first) padding else 0f
            val height = size * 1.5f + top + if (last) padding else 0f
            val commands = buildList {
                if (fill != null) add(PdfDraw.Rect(0f, 0f, width, height, fill, if (lines.size == 1) 5f else 0f))
                if (stripe != null) add(PdfDraw.Rect(0f, 0f, 2f, height, stripe))
                add(PdfDraw.Text(line, padding, top + size, size, color, bold))
            }
            PdfFlowPiece(height + if (last) gap else 0f, commands, !last || keep,
                mustKeepWithNext = if (last) keep else hardKeep)
        }
    }

    fun content(blocks: List<ReportPdfBlock>, width: Float): List<PdfFlowPiece> = buildList {
        blocks.forEachIndexed { index, block ->
            cancel()
            when (block) {
                is ReportPdfBlock.Heading -> addAll(text(block.text, width,
                    if (block.level == 1) 18f else if (block.level == 2) 10.5f else 8.5f,
                    true, if (block.level == 3) c.secondary else c.ink, gap = 7f, keep = true, hardKeep = true))
                is ReportPdfBlock.Paragraph -> {
                    val next = blocks.getOrNull(index + 1) as? ReportPdfBlock.Paragraph
                    val keep = block.keepWithNext || block.kind == ReportPdfBlock.Kind.QUOTE &&
                        next?.kind == ReportPdfBlock.Kind.SOURCE && !next.keepWithNext
                    val quote = block.kind == ReportPdfBlock.Kind.QUOTE
                    val evidence = block.kind == ReportPdfBlock.Kind.EVIDENCE
                    val source = block.kind == ReportPdfBlock.Kind.SOURCE
                    val small = source || block.kind == ReportPdfBlock.Kind.NOTE
                    addAll(text(block.text, width, if (small) 8f else 9.5f,
                        bold = quote, color = if (small) c.muted else c.ink,
                        gap = if (source) 3f else 7f, keep = keep,
                        fill = if (quote) c.soft(PdfDashboardTone.BLUE) else if (evidence) c.subtle else if (source) c.background else null,
                        stripe = if (evidence) c.ink(PdfDashboardTone.TEAL) else null,
                        hardKeep = source && keep))
                }
                is ReportPdfBlock.Metrics -> block.items.chunked(2).forEach { row -> add(metricRow(row, width)) }
                is ReportPdfBlock.Bars -> {
                    val max = block.rows.maxOfOrNull { it.value.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 } ?: 0.0
                    block.rows.forEach { addAll(bar(it, width, max)) }
                }
                is ReportPdfBlock.Hours -> add(hours(block, width))
                is ReportPdfBlock.Table -> addAll(table(block, width))
                ReportPdfBlock.PageBreak -> Unit // Card flow chooses page boundaries from measured content.
            }
        }
    }

    fun gauge(gauge: PdfDashboardGauge, width: Float, tone: PdfDashboardTone): List<PdfFlowPiece> {
        val color = c.ink(tone)
        val detail = text(gauge.label + if (gauge.detail.isBlank()) "" else "\n${gauge.detail}", width - 68f, 8.5f,
            color = c.secondary, gap = 0f)
        val height = maxOf(62f, detail.sumOf { it.height.toDouble() }.toFloat() + 8f)
        // An unusually long scope must still be splittable; keep its full text below the gauge.
        if (height > 160f) return gauge(gauge.copy(label = "", detail = ""), width, tone) +
            text(gauge.label + "\n" + gauge.detail, width, 8.5f)
        val commands = mutableListOf<PdfDraw>()
        val fraction = gauge.fraction?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
        for (step in 0 until 96) {
            val from = Math.PI * 2 * step / 96 - Math.PI / 2
            val to = Math.PI * 2 * (step + 1) / 96 - Math.PI / 2
            commands += PdfDraw.Line(27f + cos(from).toFloat() * 23f, 29f + sin(from).toFloat() * 23f,
                27f + cos(to).toFloat() * 23f, 29f + sin(to).toFloat() * 23f,
                if (fraction != null && step < fraction * 96) color else c.line, 4f)
        }
        var size = 12f
        while (size > 7f && measure(gauge.valueText, size, true) > 41f) size -= 1f
        commands += PdfDraw.Text(gauge.valueText, 27f - measure(gauge.valueText, size, true) / 2, 29f + size / 3, size, color, true)
        var y = 6f
        detail.forEach { piece -> commands += piece.commands.map { it.shift(68f, y) }; y += piece.height }
        return listOf(PdfFlowPiece(height + 7f, commands))
    }

    fun metric(metric: PdfDashboardMetric, width: Float, valueSize: Float = 23f): List<PdfFlowPiece> = buildList {
        addAll(text(metric.label, width, 8.5f, color = c.muted, gap = 5f, keep = true))
        var size = valueSize
        while (size > 12f && measure(metric.value, size, true) > width) size -= 1f
        addAll(text(metric.value, width, size, true, c.ink, gap = 5f, keep = metric.details.isNotEmpty()))
        metric.details.forEachIndexed { index, detail ->
            addAll(text(detail, width, 7.5f, color = c.secondary, gap = 2f, keep = index < metric.details.lastIndex))
        }
    }

    private fun metricRow(row: List<PdfMetric>, width: Float): PdfFlowPiece {
        val cell = (width - 6f * (row.size - 1)) / row.size
        val cells = row.map { metric(PdfDashboardMetric(it.label, it.value), cell - 14f, valueSize = 16f) }
        val height = cells.maxOf { it.sumOf { piece -> piece.height.toDouble() }.toFloat() } + 14f
        val commands = buildList {
            cells.forEachIndexed { index, pieces ->
                val x = index * (cell + 6f)
                add(PdfDraw.Rect(x, 0f, cell, height, c.subtle, 5f))
                var y = 7f
                pieces.forEach { piece -> addAll(piece.commands.map { it.shift(x + 7f, y) }); y += piece.height }
            }
        }
        return PdfFlowPiece(height + 6f, commands)
    }

    private fun bar(row: PdfBar, width: Float, maximum: Double): List<PdfFlowPiece> {
        val labels = wrap(row.label, width * .48f, 8.5f, true)
        val values = wrap(row.display, width * .49f, 7.5f)
        val denominator = row.total?.takeIf { it > 0 && it.isFinite() } ?: maximum
        val ratio = if (denominator > 0 && row.value.isFinite()) (row.value / denominator).coerceIn(0.0, 1.0) else 0.0
        return (0 until maxOf(labels.size, values.size)).map { index -> PdfFlowPiece(12f, buildList {
            labels.getOrNull(index)?.let { add(PdfDraw.Text(it, 0f, 8.5f, 8.5f, c.ink, true)) }
            values.getOrNull(index)?.let { add(PdfDraw.Text(it, width - measure(it, 7.5f, false), 8.5f, 7.5f, c.muted)) }
        }, keepWithNext = true) } + PdfFlowPiece(15f, buildList {
            add(PdfDraw.Rect(0f, 3f, width, 4f, c.subtle, 2f))
            if (ratio > 0) add(PdfDraw.Rect(0f, 3f, (width * ratio).toFloat(), 4f, c.ink(PdfDashboardTone.BLUE), 2f))
        })
    }

    private fun hours(block: ReportPdfBlock.Hours, width: Float): PdfFlowPiece {
        val values = List(24) { block.values.getOrElse(it) { 0.0 }.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }
        val max = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val plotLeft = 29f
        val plotWidth = width - plotLeft
        val step = plotWidth / 24
        val base = 88f
        val plotHeight = 64f
        return PdfFlowPiece(111f, buildList {
            add(PdfDraw.Text(block.unit, 0f, 8f, 7.5f, c.muted))
            listOf(0f, .5f, 1f).forEach { fraction ->
                val label = String.format(Locale.ROOT, if (max >= 100) "%.0f" else "%.1f", max * fraction)
                add(PdfDraw.Text(label, 0f, base - fraction * plotHeight + 2f, 6.5f, c.muted))
                add(PdfDraw.Line(plotLeft, base - fraction * plotHeight, width, base - fraction * plotHeight, c.line, .5f))
            }
            values.forEachIndexed { index, value ->
                val x = plotLeft + index * step + 1f
                add(PdfDraw.Rect(x, base - plotHeight, step - 2f, plotHeight, c.subtle, 1.5f))
                val h = (plotHeight * value / max).toFloat()
                if (h > 0f) add(PdfDraw.Rect(x, base - h, step - 2f, h, c.ink(PdfDashboardTone.BLUE), 1.5f))
                if (index % 6 == 0 || index == 23) add(PdfDraw.Text(index.toString().padStart(2, '0'),
                    minOf(x, width - measure("23", 7f, false)), base + 13f, 7f, c.muted))
            }
        })
    }

    private fun table(block: ReportPdfBlock.Table, width: Float): List<PdfFlowPiece> = buildList {
        val n = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
        if (n == 0) return@buildList
        val weights = block.weights.takeIf { it.size == n && it.all { w -> w.isFinite() && w > 0 } } ?: List(n) { 1f }
        val widths = weights.map { width * it / weights.sum() }
        fun row(cells: List<String>, header: Boolean, alternate: Boolean, repeat: PdfFlowPiece?): List<PdfFlowPiece> {
            val lines = (0 until n).map { wrap(cells.getOrElse(it) { "" }, widths[it] - 10f, 8f, header) }
            val count = lines.maxOf { it.size }
            return (0 until count).map { line ->
                val top = if (line == 0) 5f else 0f
                val h = 12f + top + if (line == count - 1) 5f else 0f
                PdfFlowPiece(h, buildList {
                    add(PdfDraw.Rect(0f, 0f, width, h, if (header) c.subtle else if (alternate) c.background else c.white))
                    var x = 0f
                    lines.forEachIndexed { col, values ->
                        values.getOrNull(line)?.let { add(PdfDraw.Text(it, x + 5f, top + 8f, 8f, c.secondary, header)) }
                        x += widths[col]
                    }
                }, line < count - 1 || header, repeat)
            }
        }
        val headerPieces = if (block.headers.isEmpty()) emptyList() else row(block.headers, true, false, null)
        val header = if (headerPieces.isEmpty()) null else PdfFlowPiece(headerPieces.sumOf { it.height.toDouble() }.toFloat(), buildList {
            var y = 0f
            headerPieces.forEach { piece -> addAll(piece.commands.map { it.shift(0f, y) }); y += piece.height }
        })
        addAll(headerPieces)
        block.rows.forEachIndexed { index, cells -> addAll(row(cells, false, index % 2 == 1, header)) }
    }
}

internal fun PdfDraw.shift(x: Float, y: Float): PdfDraw = when (this) {
    is PdfDraw.Text -> copy(x = this.x + x, y = this.y + y)
    is PdfDraw.Rect -> copy(x = this.x + x, y = this.y + y)
    is PdfDraw.Line -> copy(x = this.x + x, y = this.y + y, endX = endX + x, endY = endY + y)
}
