package com.emerge.app.analysis

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.emerge.app.ui.platformName

internal data class PdfMetric(val label: String, val value: String)
internal data class PdfBar(val label: String, val value: Double, val display: String, val total: Double? = null)

internal sealed interface ReportPdfBlock {
    data class Heading(val text: String, val level: Int = 1) : ReportPdfBlock
    data class Paragraph(val text: String, val kind: Kind = Kind.BODY) : ReportPdfBlock
    data class Metrics(val items: List<PdfMetric>) : ReportPdfBlock
    data class Bars(val rows: List<PdfBar>) : ReportPdfBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>,
        val weights: List<Float> = emptyList()) : ReportPdfBlock
    data class Hours(val values: List<Double>, val unit: String) : ReportPdfBlock
    data object PageBreak : ReportPdfBlock
    enum class Kind { BODY, NOTE, QUOTE, EVIDENCE, SOURCE }
}

internal data class ReportPdfContent(val title: String, val period: String, val blocks: List<ReportPdfBlock>) {
    companion object {
        fun from(report: CognitiveReport, appNames: Map<String, String> = emptyMap()): ReportPdfContent =
            PdfContentBuilder(report, appNames).build()
    }
}

private class PdfContentBuilder(private val report: CognitiveReport, private val appNames: Map<String, String>) {
    private val blocks = mutableListOf<ReportPdfBlock>()
    private val zone = ZoneId.of(report.zone)
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val appendixLimit = 100
    private val signalLimit = 20

    // Only exporter-owned explanation paragraphs are read from this projection.
    // Quotations and free-form purpose/method fields never enter its line parser.
    private val explanationLines by lazy {
        ReportExporter.text(report.copy(purpose = "", method = "", groups = emptyList(),
            topics = emptyList(), findings = emptyList(), topicExposure = emptyList(),
            topicDigests = emptyList(), contentSignals = emptyList(), exposure = null,
            emotionExposure = EmotionExposureSummary(), sourceCounts = emptyMap(),
            hourlyReviews = emptyList(), hourlyClaims = emptyList(),
            workStatistics = report.workStatistics?.copy(apps = emptyList())), appendixLimit).lines()
    }

    fun build(): ReportPdfContent {
        note("生成时间：${time(report.generatedAt)}（${report.zone}）")
        note("统计范围：${time(report.start)} 至 ${time(report.end)}（${report.period}）")
        paragraph("浏览目的：${report.purpose}")
        overview()
        topics()
        findings()
        digests()
        emotions()
        sourcesAndHours()
        heading("7. 建议与核查")
        exporterSection("## 7. 建议与核查", "## 8. 观点、条件与依据").forEach(::paragraph)
        appendix()
        methods()
        return ReportPdfContent("破茧 · 浏览回顾报告", report.period, blocks.toList())
    }

    private fun overview() {
        heading("1. 记录范围与数据情况")
        val stats = report.workStatistics
        blocks += ReportPdfBlock.Metrics(listOf(
            PdfMetric("浏览作品（估算）", stats?.let { "${it.total} 个" } ?: "未统计"),
            PdfMetric("观看", stats?.let { "${it.visitCount} 次" } ?: "未统计"),
            PdfMetric("停留时长", duration(stats?.observedDurationMillis ?: report.observedDurationMillis)),
            PdfMetric("观点", "${report.claims.size} 个")
        ))
        if (stats != null) {
            note("${stats.unidentifiedWorks} 个作品未识别。")
            if (stats.apps.isNotEmpty()) {
                heading("应用", 2)
                bars(stats.apps.map { PdfBar(appName(it.packageName), it.count.toDouble(), "${it.count}/${stats.total} 个作品", stats.total.toDouble()) })
                table(listOf("应用", "观看", "停留时长", "未识别作品", "时长未统计"),
                    stats.apps.map { listOf(appName(it.packageName), "${it.visitCount} 次", duration(it.durationMillis),
                        "${it.unidentifiedWorks} 个", "${it.unknownDurationRecords} 次") },
                    listOf(2.5f, 0.8f, 1.1f, 1.1f, 1.1f))
            }
        }
        table(listOf("记录范围", "记录数"), listOf(
            listOf("原始记录", "${report.rawRecords} 条原始记录"),
            listOf("合并内容相同的连续记录", "${report.mergedSnapshots} 条"),
            listOf("跳过桌面和无正文的记录", "${report.skippedRecords} 条"),
            listOf("保留有效记录", "${report.usableSnapshots} 条")
        ), listOf(3f, 2f))
        table(listOf("分析进度", "记录数"), listOf(
            listOf("通过原文校验的云端分析", "${report.structuredAnalyzedRecords}/${report.usableSnapshots} 条（${percent(report.structuredAnalyzedRecords, report.usableSnapshots)}）"),
            listOf("本地识别", "${report.localFallbackRecords} 条"),
            listOf("云端分析未通过校验", "${report.rejectedSemanticsRecords} 条"),
            listOf("等待或正在分析", "${report.pendingAnalysisRecords} 条"),
            listOf("最近未完成", "${report.failedAnalysisRecords} 条")
        ), listOf(3f, 2f))
        note("有效记录共 ${report.coverageSessions} 条；范围内停留时长合计 ${duration(report.observedDurationMillis)}；${report.unknownDurationRecords} 条时长未统计。")
        note("记录覆盖 ${report.observedDays} 天。")
    }

    private fun topics() {
        heading("2. 内容分布与观点主题")
        bars(report.topicExposure.map {
            PdfBar(it.topic, it.recordCount.toDouble(),
                "${it.recordCount}/${report.coverageSessions} 条（${percent(it.recordCount, report.coverageSessions)}）", report.coverageSessions.toDouble())
        })
        val exposures = report.topicExposure.associateBy { it.topic }
        val claims = report.topics.associateBy { it.topic }
        table(listOf("主题", "停留时长", "时长占比", "观点", "观点比例"),
            (exposures.keys + claims.keys).map { topic ->
                val exposure = exposures[topic]
                val count = claims[topic]
                listOf(topic, exposure?.let { duration(it.durationMillis) } ?: "未统计",
                    exposure?.let { percent(it.durationMillis, report.observedDurationMillis) } ?: "暂不可计算",
                    count?.let { "${it.count}/${report.claims.size} 个" } ?: "未统计",
                    count?.let { percent(it.count, report.claims.size) } ?: "暂不可计算")
            }, listOf(1.7f, 1.2f, 1f, 1.1f, 1f))
        if (report.claims.isEmpty()) note("未识别到可回查的观点，无法据此判断是否存在信息同质化。")
    }

    private fun findings() {
        heading("3. 近7天观察与措辞特征")
        report.findings.forEach { finding ->
            heading("${finding.topic}：${finding.title}", 2)
            paragraph(finding.explanation)
            source("相关记录：${finding.eventIds.joinToString().ifBlank { "无" }}")
        }
        val ids = report.findings.flatMap { it.eventIds }.toSet()
        report.exposure?.claims.orEmpty().filter { it.eventId in ids }
            .groupBy { it.quote }.forEach { (text, observations) ->
                quote(text)
                observations.distinctBy { Triple(it.eventId, it.timestamp, it.packageName) }.forEach {
                    source("记录 #${it.eventId} | ${time(it.timestamp)} | ${appName(it.packageName)}")
                }
            }
        report.contentSignals.take(signalLimit).forEach {
            source("记录 #${it.eventId} · ${it.label}")
            quote(it.quote)
        }
        if (report.contentSignals.isEmpty()) note("没有匹配到预设的焦虑或紧迫表达词。")
        if (report.contentSignals.size > signalLimit) note("PDF 只列出前 $signalLimit 条，完整内容见文本报告。")
    }

    private fun digests() {
        heading("4. 主题观点")
        var remaining = appendixLimit
        var evidenceRemaining = appendixLimit
        if (report.topicDigests.sumOf { it.viewpoints.size } > remaining)
            note("主题观点仅列出前 $remaining 项，完整内容见文本报告。")
        if (report.topicDigests.sumOf { it.viewpoints.sumOf { view -> view.evidence.size } } > evidenceRemaining)
            note("主题依据仅列出前 $evidenceRemaining 项，完整内容见文本报告。")
        report.topicDigests.forEach { topic ->
            if (remaining <= 0) return@forEach
            heading("${topic.topic} · ${topic.subject} · ${topic.workCount} 个作品", 2)
            val shown = topic.viewpoints.take(remaining)
            remaining -= shown.size
            shown.forEach { view ->
                heading("${view.dimension} · ${view.stance} · ${view.workCount} 个作品", 3)
                quote(view.quote)
                if (view.conditions.isNotBlank()) paragraph("适用条件：${view.conditions}")
                source("对应记录：${view.observations.map { it.eventId }.distinct().joinToString { "#$it" }}")
                val evidence = view.evidence.take(evidenceRemaining)
                evidenceRemaining -= evidence.size
                evidence.forEach { item ->
                    heading(item.type.label, 3)
                    evidence(item.text)
                    source("对应记录：${item.eventIds.joinToString { "#$it" }}")
                    item.historyMatches.groupBy { it.quote }.forEach { (text, matches) ->
                        matches.forEach { source("历史依据 #${it.eventId}") }
                        evidence(text)
                    }
                }
            }
        }
    }

    private fun emotions() {
        heading("5. 内容情绪")
        val emotion = report.emotionExposure
        paragraph("已分析 ${emotion.knownWorks} 个作品；未分析 ${emotion.unknownWorks} 个作品")
        // Categories may overlap; use independent known-work counts, never shares summing to 100%.
        bars(emotion.counts.map {
            PdfBar(it.category.label, it.works.toDouble(), "${it.works}/${emotion.knownWorks} 个作品", emotion.knownWorks.toDouble())
        })
        table(listOf("主题", "已分析", "未分析", "内容情绪"), emotion.byTopic.map {
            listOf(it.topic, "${it.knownWorks} 个作品", "${it.unknownWorks} 个作品",
                it.counts.joinToString("；") { count -> "${count.category.label} ${count.works}/${it.knownWorks} 个作品" })
        }, listOf(1.4f, 1f, 1f, 2.5f))
        table(listOf("日期", "已分析", "未分析", "内容情绪"), emotion.byDate.map {
            listOf(it.date, "${it.knownWorks} 个作品", "${it.unknownWorks} 个作品",
                it.counts.joinToString("；") { count -> "${count.category.label} ${count.works}/${it.knownWorks} 个作品" })
        }, listOf(1.4f, 1f, 1f, 2.5f))
        val evidence = emotion.works.flatMap { it.emotions }.distinct()
        if (evidence.size > appendixLimit)
            note("情绪原文仅列出前 $appendixLimit 项，完整内容见文本报告。")
        evidence.take(appendixLimit).forEach {
            source("记录 #${it.eventId} · ${it.category.label} · ${it.level.label}")
            quote(it.evidence)
            it.trigger?.let { trigger -> paragraph("诱因：$trigger") }
        }
    }

    private fun sourcesAndHours() {
        heading("6. 记录来源与 24 小时分布")
        table(listOf("记录来源", "原始记录"), report.sourceCounts.entries.sortedByDescending { it.value }
            .map { listOf(appName(it.key), "${it.value} 条原始记录") }, listOf(3f, 2f))
        paragraph("以下为${report.period}范围内同一小时的合计。")
        val byHour = report.hourlyReviews.associateBy { it.hour }
        if (report.hourlyReviews.isNotEmpty()) {
            blocks += ReportPdfBlock.Hours(List(24) { (byHour[it]?.durationMillis ?: 0L) / 1000.0 }, "秒")
            table(listOf("时段", "记录", "停留时长", "时长未统计", "主题", "相关观点见记录"),
                (0..23).map { hour ->
                    val review = byHour[hour]
                    if (review == null) listOf(hourLabel(hour), "未统计", "未统计", "未统计", "无",
                        "${report.hourlyClaims.getOrElse(hour) { 0 }} 个观点；旧版本报告没有逐小时统计。")
                    else listOf(hourLabel(hour), "${review.recordCount} 条", duration(review.durationMillis),
                        "${review.unknownDurationRecords} 条", review.topics.joinToString { "${it.topic} ${it.count}条" }.ifBlank { "无" },
                        review.claimEventIds.joinToString { "#$it" }.ifBlank { "无" })
                }, listOf(1.4f, 0.65f, 1f, 0.85f, 1.8f, 1.7f))
        } else {
            blocks += ReportPdfBlock.Hours(List(24) { report.hourlyClaims.getOrElse(it) { 0 }.toDouble() }, "个观点")
            note("旧版本报告没有逐小时统计。")
            table(listOf("时段", "观点"), (0..23).map {
                listOf(hourLabel(it), "${report.hourlyClaims.getOrElse(it) { 0 }} 个观点")
            })
        }
    }

    private fun appendix() {
        heading("8. 观点、条件与依据")
        var remaining = appendixLimit
        if (report.claims.size > remaining)
            note("PDF 附录最多列出前 $remaining 个观点以控制文件大小；完整列表请导出文本报告。统计和结论仍基于全部分析结果。")
        report.groups.forEachIndexed { index, group ->
            if (remaining <= 0) return@forEachIndexed
            heading("第 ${index + 1} 组 · ${group.topic} · ${group.observations.size} 条相似说法", 2)
            quote(group.quote)
            val shown = group.observations.take(remaining)
            remaining -= shown.size
            val byQuote = shown.groupBy { it.quote }
            // Preserve the group's representative first, then keep each other exact
            // quotation adjacent to all its records, regardless of input interleaving.
            val quoteOrder = listOf(group.quote) + byQuote.keys.filter { it != group.quote }
            quoteOrder.forEach { text ->
                val observations = byQuote[text].orEmpty()
                if (text != group.quote) quote(text)
                observations.forEach { observation ->
                    source("记录 #${observation.eventId} | 开始时间 ${time(observation.timestamp)} | ${appName(observation.packageName)}")
                    note("该记录在本次范围内的停留时长：${observation.durationMillis?.let(::duration) ?: "时长未统计"}（同一记录里的多个观点共用时长，不能相加）")
                    val details = mutableListOf(
                        listOf("观点来源", if (observation.analysisSource == "structured_model") "云端分析（需核对）" else "本地识别"),
                        listOf("对象", observation.subject.ifBlank { "未识别，暂不与历史对比" })
                    )
                    if (observation.analysisSource == "structured_model") {
                        details += listOf("角度", observation.dimension.ifBlank { "未识别" })
                        details += listOf("立场", observation.stance.ifBlank { "未识别" })
                    }
                    details += listOf("适用条件", observation.conditions.ifBlank { "未识别" })
                    details += listOf("出处", if (observation.uncertainAttribution) "可能引用了他人内容，待确认" else "待核实，不代表作者或你的看法")
                    details += listOf("观点对比", observation.increment.label)
                    details += listOf("较早的对比记录", observation.previousEventId?.toString() ?: "无")
                    table(emptyList(), details, listOf(1.2f, 4f))
                    if (observation.evidenceDetails.isNotEmpty()) {
                        observation.evidenceDetails.forEach { evidence ->
                            heading("${evidence.type.label}${if (evidence.isNew) "（对比历史为新增）" else ""} · 同一记录原文", 3)
                            evidence(evidence.text)
                            if (evidence.identity.actors.isNotEmpty())
                                paragraph("涉及对象：${evidence.identity.actors.joinToString("、")}")
                            if (evidence.identity.origins.isNotEmpty())
                                paragraph("材料来源：${evidence.identity.origins.joinToString("、")}")
                            evidence.historyMatches.groupBy { it.quote }.forEach { (text, matches) ->
                                matches.forEach { source("历史依据记录 #${it.eventId} · 原句") }
                                evidence(text)
                            }
                            if (evidence.identityUnresolved) note("案例身份或材料关系不清楚，暂不确认新增或重复。")
                        }
                    } else observation.evidence.forEach {
                        note("同一记录的依据/条件片段")
                        evidence(it)
                    }
                    if (observation.evidence.isEmpty()) note("没有识别到相关依据片段，无法判断信息量是否有变化。")
                }
            }
        }
    }

    private fun methods() {
        heading("9. 方法与局限")
        paragraph("分析方法：${report.method}")
        val prefixes = listOf("基于保留的浏览记录", "“今天”从当天", "作品身份按平台", "范围内共",
            "分析进度：", "处理状态：", "当前没有通过校验的云端分析", "有效记录共", "共识别出",
            "停留时长按相邻", "数据来自本机", "范围之前还保留", "每条有效记录按主要内容",
            "娱乐与休闲单独统计", "观点比例的统计范围", "判定规则：", "不满足上述规则",
            "措辞特征只描述", "记录来源可以是", "一条记录如果跨越")
        explanationLines.filter { line -> prefixes.any(line::startsWith) }.forEach(::paragraph)
        exporterSection("## 9. 方法与局限").forEach(::paragraph)
    }

    private fun exporterSection(start: String, end: String? = null): List<String> {
        val from = explanationLines.indexOf(start)
        if (from < 0) return emptyList()
        val to = end?.let { explanationLines.indexOf(it).takeIf { index -> index > from } }
            ?: explanationLines.size
        return explanationLines.subList(from + 1, to).filter(String::isNotBlank)
    }

    private fun time(value: Long) = Instant.ofEpochMilli(value).atZone(zone).format(timeFormat)
    private fun appName(packageName: String) = appNames[packageName]?.takeIf { it.isNotBlank() } ?: platformName(packageName)
    private fun duration(value: Long) = String.format(Locale.CHINA, "%.1f 秒", value / 1000.0)
    private fun percent(part: Int, total: Int) = percent(part.toLong(), total.toLong())
    private fun percent(part: Long, total: Long) = if (total <= 0) "暂不可计算"
        else String.format(Locale.CHINA, "%.1f%%", part * 100.0 / total)
    private fun hourLabel(hour: Int) = "${hour.toString().padStart(2, '0')}:00–${((hour + 1) % 24).toString().padStart(2, '0')}:00"
    private fun heading(text: String, level: Int = 1) { blocks += ReportPdfBlock.Heading(text, level) }
    private fun paragraph(text: String) { blocks += ReportPdfBlock.Paragraph(text) }
    private fun note(text: String) { blocks += ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.NOTE) }
    private fun quote(text: String) { blocks += ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.QUOTE) }
    private fun evidence(text: String) { blocks += ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.EVIDENCE) }
    private fun source(text: String) { blocks += ReportPdfBlock.Paragraph(text, ReportPdfBlock.Kind.SOURCE) }
    private fun bars(rows: List<PdfBar>) { if (rows.isNotEmpty()) blocks += ReportPdfBlock.Bars(rows) }
    private fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float> = emptyList()) {
        if (rows.isNotEmpty()) blocks += ReportPdfBlock.Table(headers, rows, weights)
    }
}
