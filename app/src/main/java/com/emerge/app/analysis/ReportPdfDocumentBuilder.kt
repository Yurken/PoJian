package com.emerge.app.analysis

import com.emerge.app.ui.platformName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class ReportPdfDocumentBuilder(
    private val report: CognitiveReport,
    private val appNames: Map<String, String>
) {
    private val zone = ZoneId.of(report.zone)
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val detailLimit = 100
    private val existingContent by lazy { ReportPdfContent.from(report, appNames) }
    private val existingSections by lazy { splitExporterSections() }
    private val hasEmotionStatistics get() = report.emotionExposure.totalWorks > 0 || report.workStatistics?.total == 0

    fun build(): ReportPdfDocument = ReportPdfDocument(
        title = existingContent.title,
        period = report.period,
        metadata = listOf(
            "生成时间：${time(report.generatedAt)}（${report.zone}）",
            "统计范围：${time(report.start)} 至 ${time(report.end)}（${report.period}）"
        ),
        purpose = report.purpose,
        metrics = metrics(),
        sections = buildList {
            add(PdfDashboardSection("", listOf(PdfDashboardRow(
                listOf(exposureCard(), activityCard()), listOf(1.15f, .85f)))))
            addAll(topicSections())
            add(emotionSection())
            add(PdfDashboardSection("建议与核查", emptyList(), sectionBody("7. 建议与核查")))
        },
        appendix = appendix()
    )

    private fun metrics(): List<PdfDashboardMetric> {
        val works = report.workStatistics
        val facts = newFactMetric(report.claims)
        val sources = report.independentSources()
        return listOf(
            PdfDashboardMetric("浏览作品（估算）", works?.let { "${it.total} 个" } ?: "未统计",
                works?.let { listOf("观看 ${it.visitCount} 次", "停留时长 ${duration(it.observedDurationMillis)}",
                    "未识别作品 ${it.unidentifiedWorks} 个") }.orEmpty()),
            PdfDashboardMetric("新增事实", facts.display, unknownDetail(facts)),
            PdfDashboardMetric("独立来源", sources.display, unknownDetail(sources)),
            PdfDashboardMetric("观点", "${report.claims.size} 个")
        )
    }

    private fun unknownDetail(metric: EvidenceMetric) =
        if (metric.unknown > 0) listOf("未核实 ${metric.unknown} 项") else emptyList()

    private fun exposureCard(): PdfDashboardCard {
        // The selected period and the rolling seven-day assessment have different scopes.
        val exposure = report.periodExposure
        val view = exposure?.primary ?: exposure?.viewpoints?.firstOrNull()
        val distribution = view?.distribution
        val share = view?.share?.takeIf { distribution != null && distribution.classified > 0 && it.isFinite() && it in 0.0..1.0 }
        val repeat = view?.longitudinal?.takeIf { it.known > 0 }?.let {
            "${it.repeated}/${it.known} 个作品（${percent(it.repeatedShare)}）"
        } ?: "未核实"
        val blocks = buildList {
            add(ReportPdfBlock.Metrics(listOf(
                PdfMetric("已识别立场", distribution?.let { "${it.classified}/${it.contacts} 次" } ?: "未统计"),
                PdfMetric("多样性", distribution?.takeIf { it.classified > 0 }?.let { decimal(it.diversity) } ?: "未统计"),
                PdfMetric("覆盖", distribution?.takeIf { it.contacts > 0 }?.let { "${percent(it.coverage)} · ${it.days} 天" } ?: "未统计"),
                PdfMetric("重复依据", repeat)
            )))
            if (report.topicExposure.isNotEmpty()) {
                add(ReportPdfBlock.Heading("内容分布与观点主题", 2))
                add(ReportPdfBlock.Bars(report.topicExposure.sortedByDescending { it.recordCount }.take(3).map {
                    PdfBar(it.topic, it.recordCount.toDouble(),
                        "${it.recordCount}/${report.coverageSessions} 条（${percent(it.recordCount, report.coverageSessions)}）",
                        report.coverageSessions.toDouble())
                }))
            }
        }
        return PdfDashboardCard("信息视野 · ${report.period}", blocks,
            badge = view?.title ?: "未统计", tone = PdfDashboardTone.BLUE,
            gauge = PdfDashboardGauge("主要立场占比", share?.let(::percent) ?: "未统计", share,
                view?.let { listOf(it.subject, it.dimension, it.stance).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()))
    }

    private fun activityCard(): PdfDashboardCard {
        val blocks = buildList {
            val byHour = report.hourlyReviews.associateBy { it.hour }
            when {
                (0..23).all(byHour::containsKey) -> add(ReportPdfBlock.Hours(
                    List(24) { byHour.getValue(it).durationMillis / 1000.0 }, "秒"))
                byHour.isNotEmpty() -> {
                    // A missing hourly field is not an observed zero-duration hour.
                    add(note("未统计"))
                    add(ReportPdfBlock.Table(listOf("时段", "停留时长"), report.hourlyReviews.sortedBy { it.hour }
                        .map { listOf(hourLabel(it.hour), duration(it.durationMillis)) }, listOf(1f, 1f)))
                }
                else -> add(note("旧版本报告没有逐小时统计。"))
            }
            add(ReportPdfBlock.Heading("应用", 2))
            val stats = report.workStatistics
            if (stats == null) add(note("未统计"))
            else if (stats.apps.isNotEmpty()) {
                add(ReportPdfBlock.Bars(stats.apps.map {
                    PdfBar(appName(it.packageName), it.count.toDouble(),
                        "${it.count}/${stats.total} 个作品 · ${duration(it.durationMillis)}", stats.total.toDouble())
                }))
            }
        }
        return PdfDashboardCard("24 小时分布", blocks, tone = PdfDashboardTone.TEAL)
    }

    private fun topicSections(): List<PdfDashboardSection> = buildList {
        val notices = buildList {
            if (report.topicDigests.sumOf { it.viewpoints.size } > detailLimit)
                add(note("主题观点仅列出前 $detailLimit 项，完整内容见文本报告。"))
            if (report.topicDigests.sumOf { it.viewpoints.sumOf { view -> view.evidence.size } } > detailLimit)
                add(note("主题依据仅列出前 $detailLimit 项，完整内容见文本报告。"))
            if (report.topicDigests.none { it.viewpoints.isNotEmpty() }) add(note("未统计"))
        }
        add(PdfDashboardSection("主题观点", emptyList(), notices))
        var viewpointsRemaining = detailLimit
        var evidenceRemaining = detailLimit
        report.topicDigests.forEach { topic ->
            if (viewpointsRemaining <= 0) return@forEach
            val shown = topic.viewpoints.take(viewpointsRemaining)
            viewpointsRemaining -= shown.size
            val cards = shown.map { view ->
                val shownEvidence = view.evidence.take(evidenceRemaining)
                evidenceRemaining -= shownEvidence.size
                viewpointCard(view, shownEvidence)
            }
            if (cards.isNotEmpty()) add(PdfDashboardSection(
                listOf(topic.topic, topic.subject, "${topic.workCount} 个作品").filter(String::isNotBlank).joinToString(" · "),
                cards.chunked(2).map { PdfDashboardRow(it) }))
        }
    }

    private fun viewpointCard(view: DigestViewpoint, evidence: List<DigestEvidence>): PdfDashboardCard {
        val blocks = buildList {
            if (view.conditions.isNotBlank()) add(ReportPdfBlock.Paragraph("适用条件：${view.conditions}", keepWithNext = true))
            val quotations = view.observations.groupBy { it.quote }.ifEmpty { mapOf(view.quote to emptyList()) }
            quotations.forEach { (quote, observations) ->
                add(ReportPdfBlock.Paragraph(quote, ReportPdfBlock.Kind.QUOTE, keepWithNext = true))
                if (observations.isNotEmpty())
                    add(source("对应记录：${observations.map { it.eventId }.distinct().joinToString { "#$it" }}"))
            }
            evidence.forEach { item ->
                val histories = item.historyMatches.groupBy { it.quote }
                val sameEvidence = histories[item.text].orEmpty()
                add(ReportPdfBlock.Heading(item.type.label, 3))
                add(ReportPdfBlock.Paragraph(item.text, ReportPdfBlock.Kind.EVIDENCE, keepWithNext = true))
                add(source("对应记录：${item.eventIds.joinToString { "#$it" }}", keepWithNext = sameEvidence.isNotEmpty() || item.hasNew))
                if (item.hasNew) add(ReportPdfBlock.Paragraph(Increment.NEW.label, ReportPdfBlock.Kind.NOTE, keepWithNext = sameEvidence.isNotEmpty()))
                if (sameEvidence.isNotEmpty()) add(source("历史依据 ${sameEvidence.map { it.eventId }.distinct().joinToString("、") { "#$it" }}"))
                histories.filterKeys { it != item.text }.forEach { (text, matches) ->
                    add(source("历史依据 ${matches.map { it.eventId }.distinct().joinToString("、") { "#$it" }}", keepWithNext = true))
                    add(ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.EVIDENCE))
                }
                if (item.unresolved) add(note("案例身份或材料关系不清楚，暂不确认新增或重复。"))
            }
        }
        val increment = view.observations.map { it.increment }.distinct().singleOrNull()
        return PdfDashboardCard(listOf(view.dimension, view.stance, "${view.workCount} 个作品")
            .filter(String::isNotBlank).joinToString(" · "), blocks,
            badge = increment?.label.orEmpty(), tone = PdfDashboardTone.BLUE)
    }

    private fun emotionSection(): PdfDashboardSection {
        val emotion = report.emotionExposure
        val evidence = emotion.works.flatMap { it.emotions }.distinct()
        val intro = buildList {
            add(if (hasEmotionStatistics) ReportPdfBlock.Paragraph(
                "已分析 ${emotion.knownWorks} 个作品；未分析 ${emotion.unknownWorks} 个作品") else note("未统计"))
            if (emotion.counts.isNotEmpty()) add(ReportPdfBlock.Bars(emotion.counts.map {
                // Categories can overlap; each keeps the same known-work denominator.
                PdfBar(it.category.label, it.works.toDouble(), "${it.works}/${emotion.knownWorks} 个作品", emotion.knownWorks.toDouble())
            }))
            if (evidence.size > detailLimit) add(note("情绪原文仅列出前 $detailLimit 项，完整内容见文本报告。"))
        }
        val cards = evidence.take(detailLimit).map { item ->
            PdfDashboardCard("${item.category.label} · ${item.level.label}", buildList {
                add(source("记录 #${item.eventId} · ${item.category.label} · ${item.level.label}", keepWithNext = true))
                add(ReportPdfBlock.Paragraph(item.evidence, ReportPdfBlock.Kind.QUOTE))
                item.trigger?.let { add(ReportPdfBlock.Paragraph("诱因：$it")) }
            }, tone = PdfDashboardTone.ROSE)
        }
        return PdfDashboardSection("内容情绪", cards.chunked(2).map { PdfDashboardRow(it) }, intro)
    }

    private fun appendix(): ReportPdfContent {
        val blocks = buildList {
            // Only the exporter-owned top-level sections are selected. No user text is parsed.
            addAll(existingSections["1. 记录范围与数据情况"].orEmpty().filterNot {
                it is ReportPdfBlock.Metrics || it is ReportPdfBlock.Bars
            })
            addAll(existingSections["2. 内容分布与观点主题"].orEmpty())
            addAll(existingSections["3. 近7天观察与措辞特征"].orEmpty())
            val emotionTables = sectionBody("5. 内容情绪").filterIsInstance<ReportPdfBlock.Table>()
            if (emotionTables.isNotEmpty()) {
                add(ReportPdfBlock.Heading("5. 内容情绪"))
                addAll(emotionTables)
            }
            addAll(existingSections["6. 记录来源与 24 小时分布"].orEmpty().filterNot { it is ReportPdfBlock.Hours })
            addAll(existingSections["8. 观点、条件与依据"].orEmpty())
            addAll(existingSections["9. 方法与局限"].orEmpty())
        }
        return ReportPdfContent("附录", report.period, blocks)
    }

    private fun sectionBody(title: String): List<ReportPdfBlock> = existingSections[title].orEmpty().drop(1)

    private fun splitExporterSections(): Map<String, List<ReportPdfBlock>> {
        val sectionNames = setOf("1. 记录范围与数据情况", "2. 内容分布与观点主题", "3. 近7天观察与措辞特征",
            "4. 主题观点", "5. 内容情绪", "6. 记录来源与 24 小时分布", "7. 建议与核查", "8. 观点、条件与依据", "9. 方法与局限")
        val result = linkedMapOf<String, MutableList<ReportPdfBlock>>()
        var current: MutableList<ReportPdfBlock>? = null
        existingContent.blocks.forEach { block ->
            if (block is ReportPdfBlock.Heading && block.level == 1 && block.text in sectionNames) {
                current = mutableListOf()
                result[block.text] = current!!
            }
            current?.add(block)
        }
        return result
    }

    private fun time(value: Long) = Instant.ofEpochMilli(value).atZone(zone).format(timeFormat)
    private fun appName(packageName: String) = appNames[packageName]?.takeIf(String::isNotBlank) ?: platformName(packageName)
    private fun duration(value: Long) = String.format(Locale.CHINA, "%.1f 秒", value / 1000.0)
    private fun decimal(value: Double) = String.format(Locale.CHINA, "%.2f", value)
    private fun percent(value: Double) = String.format(Locale.CHINA, "%.1f%%", value * 100)
    private fun percent(part: Int, total: Int) = if (total > 0) percent(part.toDouble() / total) else "暂不可计算"
    private fun hourLabel(hour: Int) = "${hour.toString().padStart(2, '0')}:00–${((hour + 1) % 24).toString().padStart(2, '0')}:00"
    private fun note(text: String) = ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.NOTE)
    private fun source(text: String, keepWithNext: Boolean = false) =
        ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.SOURCE, keepWithNext)
}
