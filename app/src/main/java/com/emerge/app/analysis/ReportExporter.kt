package com.emerge.app.analysis

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ReportExporter {
    fun text(report: CognitiveReport, appendixLimit: Int = Int.MAX_VALUE): String = buildString {
        val zone = ZoneId.of(report.zone)
        fun time(value: Long) = Instant.ofEpochMilli(value).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        fun duration(value: Long) = String.format(java.util.Locale.CHINA, "%.1f 秒", value / 1000.0)
        appendLine("# 破茧 · 浏览回顾报告")
        appendLine("基于保留的浏览记录，回顾内容与依据。")
        appendLine("生成时间：${time(report.generatedAt)}（${report.zone}）")
        appendLine("统计范围：${time(report.start)} 至 ${time(report.end)}（${report.period}）")
        appendLine("“今天”从当天 00:00 开始；“过去 1 小时”“过去 24 小时”按生成时刻回溯；“最近 7 天”“最近 30 天”分别从六天前、二十九天前 00:00 开始。")
        appendLine("分析方法：${report.method}")
        appendLine("浏览目的：${report.purpose}")
        appendLine()
        appendLine("## 1. 记录范围与数据情况")
        report.workStatistics?.let { stats ->
            appendLine("浏览作品（估算）：${stats.total} 个；观看 ${stats.visitCount} 次；停留时长 ${duration(stats.observedDurationMillis)}。${stats.unidentifiedWorks} 个作品未识别。")
            stats.apps.forEach { app -> appendLine("平台 ${app.packageName}：${app.count} 个作品，${app.visitCount} 次观看，停留时长 ${duration(app.durationMillis)}；${app.unidentifiedWorks} 个作品未识别，${app.unknownDurationRecords} 次时长未统计。") }
            appendLine("作品身份按平台提供的标识或作者与标题估计，无法确认时按连续观看计数。再次打开已识别作品增加观看次数，音频片段和视觉文字不单独计为作品。下文的分析记录数和观点数不是作品数。")
        }
        appendLine("范围内共 ${report.rawRecords} 条原始记录；合并内容相同的连续记录 ${report.mergedSnapshots} 条；跳过桌面和无正文的记录 ${report.skippedRecords} 条；保留有效记录 ${report.usableSnapshots} 条。跨越范围边界的记录按重叠部分计入。")
        appendLine("分析进度：${report.structuredAnalyzedRecords}/${report.usableSnapshots} 条有效记录有通过原文校验的云端分析（${percent(report.structuredAnalyzedRecords, report.usableSnapshots)}）；${report.localFallbackRecords} 条使用本地识别；${report.rejectedSemanticsRecords} 条云端分析未通过校验，已回退到本地识别。覆盖包括未提取到观点的记录，每次最多提取 3 个观点，不能代表整条记录或全部浏览内容。")
        appendLine("处理状态：${report.pendingAnalysisRecords} 条记录已保存、等待或正在分析；${report.failedAnalysisRecords} 条最近未完成。任务状态保留 24 小时，原文独立保留；本地识别不表示云端分析已完成。")
        if (report.structuredAnalyzedRecords == 0) appendLine("当前没有通过校验的云端分析；观点和依据均来自本地识别，需核对原文。")
        appendLine("有效记录共 ${report.coverageSessions} 条；范围内停留时长合计 ${duration(report.observedDurationMillis)}；${report.unknownDurationRecords} 条时长未统计。时长为零表示尚未采集到连续的检查间隔。")
        appendLine("共识别出 ${report.claims.size} 个观点，记录覆盖 ${report.observedDays} 天。一条记录里可能有多个观点，观点数量不能换算成帖子数、阅读次数或浏览时长。")
        appendLine("停留时长按相邻两次检查的间隔估算，中断超过 6 秒结束一段浏览。每条记录只计一次，按统计范围、日期和小时分摊。停留时长不能代表实际阅读、理解或观看时间；旧版本记录不补算时长，音频转写和媒体长度不计入观看时长。")
        appendLine("数据来自本机按存储容量上限保留的记录，包括可见页面文字、已保存的音频转写及视觉提取文字，后者可能有误。未获取的页面文字、未转写的音频、完整视频画面和已删除历史均不在范围内。记录为零可能源于未采集、权限或页面限制。")
        appendLine("范围之前还保留 ${report.comparisonRecords} 条历史记录${report.comparisonStart?.let { "，最早一条是 ${time(it)}" } ?: ""}；对比按浏览先后顺序，仅使用这些记录。“首次出现”表示未匹配到可比历史。")
        appendLine()
        appendLine("## 2. 内容分布与观点主题")
        report.topicExposure.forEach {
            val durationShare = if (report.observedDurationMillis > 0) percent(it.durationMillis, report.observedDurationMillis)
                else "暂不可计算"
            appendLine("${it.topic}：${it.recordCount}/${report.coverageSessions} 条（${percent(it.recordCount, report.coverageSessions)}）；停留时长 ${duration(it.durationMillis)}；时长占比 $durationShare")
        }
        appendLine("每条有效记录按主要内容归入一个主题，包括未分类和娱乐。条数比例的统计范围为全部 ${report.coverageSessions} 条有效记录；时长比例的统计范围为停留时长合计 ${duration(report.observedDurationMillis)}。时长未统计不表示没有阅读。")
        appendLine("娱乐与休闲单独统计，不会因为缺少论据或反复观看而被评价为“低价值”。专题内容占比高，也可能正符合你的浏览目的。")
        report.topics.forEach { appendLine("${it.topic}：${it.count}/${report.claims.size} 个观点（${percent(it.count, report.claims.size)}）") }
        if (report.claims.isEmpty()) appendLine("未识别到可回查的观点，无法据此判断是否存在信息同质化。")
        appendLine("观点比例的统计范围为识别出的 ${report.claims.size} 个观点，不代表全部内容或停留时间。云端分析的主题和立场需核对；本地识别只覆盖预设词表，立场未经语义模型确认。")
        appendLine()
        appendLine("## 3. 近7天观察与措辞特征")
        report.findings.forEach {
            appendLine("${it.topic}：${it.title}")
            appendLine(it.explanation)
            appendLine("相关记录：${it.eventIds.joinToString().ifBlank { "无" }}")
            val ids = it.eventIds.toSet()
            report.exposure?.claims.orEmpty().filter { claim -> claim.eventId in ids }.forEach { claim ->
                appendLine("记录 #${claim.eventId} | ${time(claim.timestamp)} | ${claim.packageName} | 原句：${claim.quote}")
            }
        }
        appendLine("判定规则：回查近7天，同一议题、对象、评价维度和条件至少30次已识别接触，总体及比较范围内的明确立场识别覆盖率均至少60%，同一立场占比至少75%。一天内样本充足也可判断集中；重复提醒另须覆盖至少3天、8个已知论据作品、论据已知覆盖至少60%、重复论据作品占比至少60%，当前内容亦须有可回查的重复依据。同次接触的多种立场平分权重。与前7天比较时，两期均须满足样本门槛、应用集合相同，识别覆盖率及各应用份额变化不超过20个百分点；同向占比上升至少15个百分点且多样性指数下降至少0.1才标为收窄线索。多样性为1减各立场份额平方和。跨应用要求至少2个应用各有10次已识别接触、覆盖率至少60%、同向占比至少75%。这些是未经校准的工程阈值，不是科学诊断或认知风险评分。")
        appendLine("不满足上述规则不代表没有信息同质化；应用数量、主题集中、情绪倾向和观点一致都不能单独得出结论。时长不足或缺失时，也不能凭次数判断主要精力花在了哪里。")
        val signalLimit = if (appendixLimit == Int.MAX_VALUE) Int.MAX_VALUE else 20
        report.contentSignals.take(signalLimit).forEach {
            appendLine("记录 #${it.eventId} · ${it.label}：${it.quote}")
        }
        if (report.contentSignals.isEmpty()) appendLine("没有匹配到预设的焦虑或紧迫表达词。")
        if (report.contentSignals.size > signalLimit) appendLine("PDF 只列出前 $signalLimit 条，完整内容见文本报告。")
        appendLine("措辞特征只描述原文措辞，不推断你的情绪、心理状态、立场或认知状况。内容价值需结合原文和依据判断。")
        appendLine()
        appendLine("## 4. 主题观点")
        var digestRemaining = appendixLimit.coerceAtLeast(0)
        var evidenceRemaining = appendixLimit.coerceAtLeast(0)
        if (report.topicDigests.sumOf { it.viewpoints.size } > digestRemaining)
            appendLine("主题观点仅列出前 $digestRemaining 项，完整内容见文本报告。")
        if (report.topicDigests.sumOf { it.viewpoints.sumOf { view -> view.evidence.size } } > evidenceRemaining)
            appendLine("主题论据仅列出前 $evidenceRemaining 项，完整内容见文本报告。")
        report.topicDigests.forEach { topic ->
            if (digestRemaining <= 0) return@forEach
            appendLine("${topic.topic} · ${topic.subject} · ${topic.workCount} 个作品")
            val shown = topic.viewpoints.take(digestRemaining)
            digestRemaining -= shown.size
            shown.forEach { viewpoint ->
                appendLine("${viewpoint.dimension} · ${viewpoint.stance} · ${viewpoint.workCount} 个作品")
                appendLine("观点原文：${viewpoint.quote}")
                if (viewpoint.conditions.isNotBlank()) appendLine("适用条件：${viewpoint.conditions}")
                appendLine("对应记录：${viewpoint.observations.map { it.eventId }.distinct().joinToString { "#$it" }}")
                val shownEvidence = viewpoint.evidence.take(evidenceRemaining)
                evidenceRemaining -= shownEvidence.size
                shownEvidence.forEach { evidence ->
                    appendLine("${evidence.type.label}：${evidence.text}")
                    appendLine("对应记录：${evidence.eventIds.joinToString { "#$it" }}")
                    evidence.historyMatches.forEach { appendLine("历史依据 #${it.eventId}：${it.quote}") }
                }
            }
        }
        appendLine()
        appendLine("## 5. 内容情绪")
        val emotion = report.emotionExposure
        appendLine("已分析 ${emotion.knownWorks} 个作品；未分析 ${emotion.unknownWorks} 个作品")
        emotion.counts.forEach { appendLine("${it.category.label}：${it.works}/${emotion.knownWorks} 个作品") }
        emotion.byTopic.forEach { topic ->
            appendLine("${topic.topic}：已分析 ${topic.knownWorks}；未分析 ${topic.unknownWorks}；" +
                topic.counts.joinToString("，") { "${it.category.label} ${it.works}" })
        }
        emotion.byDate.forEach { day ->
            appendLine("${day.date}：已分析 ${day.knownWorks}；未分析 ${day.unknownWorks}；" +
                day.counts.joinToString("，") { "${it.category.label} ${it.works}" })
        }
        val emotionEvidence = emotion.works.flatMap { it.emotions }.distinct()
        if (emotionEvidence.size > appendixLimit.coerceAtLeast(0))
            appendLine("情绪原文仅列出前 ${appendixLimit.coerceAtLeast(0)} 项，完整内容见文本报告。")
        emotionEvidence.take(appendixLimit.coerceAtLeast(0)).forEach {
            appendLine("记录 #${it.eventId} · ${it.category.label} · ${it.level.label}：${it.evidence}")
            it.trigger?.let { trigger -> appendLine("诱因：$trigger") }
        }
        appendLine()
        appendLine("## 6. 记录来源与 24 小时分布")
        report.sourceCounts.entries.sortedByDescending { it.value }.forEach { appendLine("${it.key}：${it.value} 条原始记录") }
        appendLine("记录来源可以是应用页面或音频转写，不等于作者、媒体或原始材料出处，不同应用和音频里的内容可能来自同一源头；上述计数包含未清洗的记录。")
        appendLine("以下为${report.period}范围内同一小时的合计。")
        (0..23).forEach { hour ->
            val review = report.hourlyReviews.firstOrNull { it.hour == hour }
            val label = "${hour.toString().padStart(2, '0')}:00–${((hour + 1) % 24).toString().padStart(2, '0')}:00"
            if (review == null) {
                appendLine("$label：${report.hourlyClaims.getOrElse(hour) { 0 }} 个观点；旧版本报告没有逐小时统计。")
            } else {
                appendLine("$label：${review.recordCount} 条记录；停留时长 ${duration(review.durationMillis)}；${review.unknownDurationRecords} 条时长未统计；主题 ${review.topics.joinToString { "${it.topic} ${it.count}条" }.ifBlank { "无" }}；相关观点见记录 ${review.claimEventIds.joinToString { "#$it" }.ifBlank { "无" }}")
            }
        }
        appendLine("一条记录如果跨越多个小时（或跨天），会在每个接触到的小时里分别列出，所以各时段的条数不能直接相加；停留时长已切分，总时长不会重复。观点可通过记录编号到附录中回看原文。")
        appendLine()
        appendLine("## 7. 建议与核查")
        appendLine("先确认引用的文字来自正文、评论还是转述，核对对象、时间和适用条件，再比较原始数据、反例和行动建议。主题是否集中，请结合你这一阶段的浏览目的来看。")
        appendLine("如果同一个判断反复出现，先核实它是否引用的是同一份材料；出现了新数字、新条件或新建议，不代表材料真实，也不代表你真的学到了新东西。")
        appendLine()
        appendLine("## 8. 观点、条件与依据")
        var remaining = appendixLimit.coerceAtLeast(0)
        if (report.claims.size > remaining) appendLine("PDF 附录最多列出前 $remaining 个观点以控制文件大小；完整列表请导出文本报告。统计和结论仍基于全部分析结果。")
        report.groups.forEachIndexed { index, group ->
            if (remaining <= 0) return@forEachIndexed
            appendLine("### 第 ${index + 1} 组 · ${group.topic} · ${group.observations.size} 条相似说法")
            appendLine("代表原文：${group.quote}")
            val shown = group.observations.take(remaining)
            remaining -= shown.size
            shown.forEach { observation ->
                appendLine("记录 #${observation.eventId} | 开始时间 ${time(observation.timestamp)} | ${observation.packageName}")
                appendLine("该记录在本次范围内的停留时长：${observation.durationMillis?.let { duration(it) } ?: "时长未统计"}（同一记录里的多个观点共用时长，不能相加）")
                appendLine("观点原文：${observation.quote}")
                appendLine("观点来源：${if (observation.analysisSource == "structured_model") "云端分析（需核对）" else "本地识别"}")
                appendLine("对象：${observation.subject.ifBlank { "未识别，暂不与历史对比" }}")
                if (observation.analysisSource == "structured_model") appendLine("角度：${observation.dimension.ifBlank { "未识别" }}；立场：${observation.stance.ifBlank { "未识别" }}")
                appendLine("适用条件：${observation.conditions.ifBlank { "未识别" }}；出处：${if (observation.uncertainAttribution) "可能引用了他人内容，待确认" else "待核实，不代表作者或你的看法"}")
                appendLine("观点对比：${observation.increment.label}；较早的对比记录：${observation.previousEventId ?: "无"}")
                if (observation.evidenceDetails.isNotEmpty()) observation.evidenceDetails.forEach {
                    appendLine("${it.type.label}${if (it.isNew) "（对比历史为新增）" else ""} · 同一记录原文：${it.text}")
                    if (it.identity.actors.isNotEmpty()) appendLine("涉及对象：${it.identity.actors.joinToString("、")}")
                    if (it.identity.origins.isNotEmpty()) appendLine("材料来源：${it.identity.origins.joinToString("、")}")
                    it.historyMatches.forEach { match -> appendLine("历史依据记录 #${match.eventId} · 原句：${match.quote}") }
                    if (it.identityUnresolved) appendLine("案例身份或材料关系不清楚，暂不确认新增或重复。")
                } else observation.evidence.forEach { appendLine("同一记录的依据/条件片段：$it") }
                if (observation.evidence.isEmpty()) appendLine("没有识别到相关依据片段，无法判断信息量是否有变化。")
                appendLine()
            }
        }
        appendLine("## 9. 方法与局限")
        appendLine("方法：通过事件编号、完整原文和引用片段校验的云端分析优先用于主题、观点、对象、角度、立场、条件与依据；无有效结果时使用本地识别，包括文字过滤、词表主题和判断句规则。历史比较与时序汇总在本机完成。")
        appendLine("新版本按连续可见的内容记录浏览；旧版本只合并同一应用 30 秒内内容相同的页面记录，音频转写片段不参与页面去重。本地相似观点阈值为 0.65；论据先核对当事对象、材料、数量单位、否定及已识别范围，再使用有限同义规范化与 0.85 字符相似度。明确独立的数量事实至多拆成 8 个原文片段，逐项检索历史并要求规范化内容一致；未拆出的条件、引用或未知语法仍按整段保留。相对月、季、年按观察时间对齐。每次最多对比最近 120 个同范围候选，未确认身份或超出比较预算的未覆盖片段保留待定。以上规则及阈值未经独立人工标注数据校准。")
        appendLine("同一记录出现的片段尚不能证明是对该观点的支撑；本方法没有判断支持或反驳的真实语义、信息真实性、来源是否独立，以及你是否认同。新增片段也不等同于实质的信息增量。")
        appendLine("云端分析仍可能有误，原文校验不能确认观点归属或推理正确。历史依据比较使用本地识别规则，尚未实现语义编码、时序图学习、阅读投入估计或可信执行环境。音频转写和视觉提取文字可参与分析，不单独增加作品接触数或观看时长。")
        appendLine("这份回顾在本机生成。语音转写在本机完成；配置云端并启用相应分析、实时核查或关键画面开关后，必要文字或采样画面会发送到指定服务。PDF 和文本报告包含原文引用，导出后由你自行保管；清空应用记录不会删除已导出的文件。")
    }

    fun writePdf(report: CognitiveReport, output: OutputStream, checkCancellation: () -> Unit = {}) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        var number = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            checkCancellation()
            page?.let { document.finishPage(it) }
            number++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            paint.color = Color.rgb(20, 88, 78)
            paint.textSize = 10f
            page!!.canvas.drawText("破茧 | 浏览回顾", 44f, 30f, paint)
            page!!.canvas.drawText("$number", 540f, 817f, paint)
            y = 60f
        }
        try {
            newPage()
            text(report, appendixLimit = 100).lines().forEach { raw ->
                checkCancellation()
                val heading = raw.startsWith("#")
                val text = raw.removePrefix("### ").removePrefix("## ").removePrefix("# ")
                val fontSize = if (raw.startsWith("# ")) 20f else if (heading) 14f else 11f
                val height = if (heading) 24f else 18f
                if (heading && y > 740f) newPage()
                paint.textSize = fontSize
                paint.color = if (heading) Color.rgb(20, 88, 78) else Color.rgb(35, 45, 55)
                if (text.isEmpty()) { y += 8f; return@forEach }
                var offset = 0
                while (offset < text.length) {
                    checkCancellation()
                    if (y + height > 785f) {
                        newPage()
                        paint.textSize = fontSize
                        paint.color = if (heading) Color.rgb(20, 88, 78) else Color.rgb(35, 45, 55)
                    }
                    var count = paint.breakText(text, offset, text.length, true, 507f, null).coerceAtLeast(1)
                    // Keep short numbers and English words together when a line boundary cuts through them.
                    fun asciiToken(character: Char) = character in '0'..'9' || character in 'A'..'Z' ||
                        character in 'a'..'z' || character in ".%_/-"
                    if (offset + count < text.length && asciiToken(text[offset + count - 1]) && asciiToken(text[offset + count])) {
                        var boundary = offset + count
                        while (boundary > offset && asciiToken(text[boundary - 1])) boundary--
                        if (boundary > offset) count = boundary - offset
                    }
                    page!!.canvas.drawText(text.substring(offset, offset + count), 44f, y, paint)
                    offset += count
                    y += height
                }
            }
            page?.let { document.finishPage(it) }
            page = null
            checkCancellation()
            document.writeTo(output)
        } finally {
            page?.let { document.finishPage(it) }
            document.close()
        }
    }

    fun percent(part: Long, total: Long) = if (total == 0L) "0.0%" else String.format(java.util.Locale.CHINA, "%.1f%%", part * 100.0 / total)

    fun percent(part: Int, total: Int) = if (total == 0) "0.0%" else String.format(java.util.Locale.CHINA, "%.1f%%", part * 100.0 / total)
}
