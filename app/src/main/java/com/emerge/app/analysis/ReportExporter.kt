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
        appendLine("# 破茧 Emerge · 信息环境回顾报告")
        appendLine("看见信息边界，拓宽判断依据。")
        appendLine("生成时间：${time(report.generatedAt)}（${report.zone}）")
        appendLine("统计范围：${time(report.start)} 至 ${time(report.end)}（${report.period}）")
        appendLine("“今天”从当天 00:00 开始，“最近 7 天”从六天前 00:00 开始；今天还没到的时段显示为零。")
        appendLine("分析方法：${report.method}")
        appendLine("浏览目的：${report.purpose}")
        appendLine()
        appendLine("## 1. 覆盖范围与数据质量")
        appendLine("范围内共 ${report.rawRecords} 条原始记录；合并内容相同的连续记录 ${report.mergedSnapshots} 条；跳过桌面和无正文的记录 ${report.skippedRecords} 条；保留有效记录 ${report.usableSnapshots} 条。跨越范围边界的记录按重叠部分计入。")
        appendLine("有效记录共 ${report.coverageSessions} 条；范围内预计停留合计 ${duration(report.observedDurationMillis)}；${report.unknownDurationRecords} 条未统计时长。显示为零只表示还没采集到连续的检查间隔。")
        appendLine("共识别出 ${report.claims.size} 个观点，记录覆盖 ${report.observedDays} 天。一条记录里可能有多个观点，观点数量不能换算成帖子数、阅读次数或浏览时长。")
        appendLine("停留时长按相邻两次检查的间隔估算，中断超过 6 秒视为一段浏览结束。每条记录只算一次，按与统计范围、日期和小时的交叠分摊。它不等于实际阅读、理解或看视频的时间；旧版本记录不补算时长，音频转写未统计观看时长，媒体长度不计入浏览停留。")
        appendLine("数据来自本机保留的最近 5000 条记录，覆盖可见页面文字与已保存的音频转写；不包括视频画面、未转写的音频、获取不到的页面文字和已删除的历史。显示为零可能是没采集、没权限或页面限制，不代表没有浏览。")
        appendLine("范围之前还保留 ${report.comparisonRecords} 条历史记录${report.comparisonStart?.let { "，最早一条是 ${time(it)}" } ?: ""}；对比只使用这些记录，并按浏览发生的先后顺序进行。“首次出现”只是没有匹配到可比的历史，不代表你从没看过这类内容。")
        appendLine()
        appendLine("## 2. 主题分布与观点")
        report.topicExposure.forEach {
            val durationShare = if (report.observedDurationMillis > 0) percent(it.durationMillis, report.observedDurationMillis)
                else "暂不可计算"
            appendLine("${it.topic}：${it.recordCount}/${report.coverageSessions} 条（${percent(it.recordCount, report.coverageSessions)}）；预计停留 ${duration(it.durationMillis)}；时长占比 $durationShare")
        }
        appendLine("主题按每条有效记录的主要内容归类，一条只归一个主题，含未分类和娱乐。条数比例的分母是全部有效记录，时长比例的分母是范围内预计停留总量。没有时长数据不按零阅读处理。")
        appendLine("娱乐与休闲单独统计，不会因为缺少论据或反复观看而被评价为“低价值”。专题内容占比高，也可能正符合你的浏览目的。")
        report.topics.forEach { appendLine("${it.topic}：${it.count}/${report.claims.size} 个观点（${percent(it.count, report.claims.size)}）") }
        if (report.claims.isEmpty()) appendLine("没有识别到符合规则的观点，这不代表没有浏览，也不代表不存在信息茧房。")
        appendLine("观点比例的分母是识别出的观点数，不是全部内容或停留时间。主题只覆盖预设词表，内容立场没有做语义验证。")
        appendLine()
        appendLine("## 3. 信息茧房线索与内容情绪")
        report.findings.forEach {
            appendLine("${it.topic}：${it.title}")
            appendLine(it.explanation)
            appendLine("核查事件：${it.eventIds.joinToString().ifBlank { "无" }}")
        }
        appendLine("判定规则：同一主题至少 8 条不同记录、覆盖至少 3 天；最大的一组相似说法占比至少 70%，且依据重复的记录占比至少 60%。同一条记录里的多个观点不增加次数或时长权重。这是未经校准的工程阈值，不是科学诊断，也不是认知风险评分。")
        appendLine("不满足上述规则不代表没有信息茧房；应用数量、主题集中、情绪倾向和观点一致都不能单独得出结论。时长不足或缺失时，也不能凭次数判断主要精力花在了哪里。")
        val signalLimit = if (appendixLimit == Int.MAX_VALUE) Int.MAX_VALUE else 20
        report.contentSignals.take(signalLimit).forEach {
            appendLine("事件 #${it.eventId} · ${it.label}：${it.quote}")
        }
        if (report.contentSignals.isEmpty()) appendLine("没有匹配到预设的焦虑或紧迫表达词。")
        if (report.contentSignals.size > signalLimit) appendLine("PDF 只列出前 $signalLimit 条，完整内容见文本报告。")
        appendLine("上述线索只描述原文的措辞，不推断你的情绪、心理状态、立场或认知状况。即使含有焦虑表达，内容也可能有实质价值，请结合原文和依据核实。")
        appendLine()
        appendLine("## 4. 记录来源与 24 小时分布")
        report.sourceCounts.entries.sortedByDescending { it.value }.forEach { appendLine("${it.key}：${it.value} 条原始记录") }
        appendLine("记录来源可以是应用页面或音频转写，不等于作者、媒体或原始材料出处，不同应用和音频里的内容可能来自同一源头；上述计数包含未清洗的记录。")
        appendLine(if (report.period.contains("7")) "以下为 7 天内同一小时的合计。" else "以下为当天 00:00 到 23:00，共 24 个小时段。")
        (0..23).forEach { hour ->
            val review = report.hourlyReviews.firstOrNull { it.hour == hour }
            val label = "${hour.toString().padStart(2, '0')}:00–${((hour + 1) % 24).toString().padStart(2, '0')}:00"
            if (review == null) {
                appendLine("$label：${report.hourlyClaims.getOrElse(hour) { 0 }} 个观点；旧版本报告没有逐小时统计。")
            } else {
                appendLine("$label：${review.recordCount} 条记录；预计停留 ${duration(review.durationMillis)}；${review.unknownDurationRecords} 条未统计时长；主题 ${review.topics.joinToString { "${it.topic} ${it.count}条" }.ifBlank { "无" }}；相关观点见记录 ${review.claimEventIds.joinToString { "#$it" }.ifBlank { "无" }}")
            }
        }
        appendLine("一条记录如果跨越多个小时（或跨天），会在每个接触到的小时里分别列出，所以各时段的条数不能直接相加；停留时长已切分，总时长不会重复。观点可通过记录编号到附录中回看原文。")
        appendLine()
        appendLine("## 5. 建议与核查")
        appendLine("先确认引用的文字来自正文、评论还是转述，核对讨论对象、时间和适用条件，再比较原始数据、反例和行动建议。主题是否集中，请结合你这一阶段的浏览目的来看。")
        appendLine("如果同一个判断反复出现，先核实它是否引用的是同一份材料；出现了新数字、新条件或新建议，不代表材料真实，也不代表你真的学到了新东西。")
        appendLine()
        appendLine("## 6. 观点、条件与依据明细")
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
                appendLine("该记录在本次范围内的预计停留：${observation.durationMillis?.let { duration(it) } ?: "未统计"}（同一记录里的多个观点共用时长，不能相加）")
                appendLine("观点原文：${observation.quote}")
                appendLine("讨论对象：${observation.subject.ifBlank { "未识别到，暂不与历史对比" }}")
                appendLine("适用条件：${observation.conditions.ifBlank { "未识别到" }}；出处：${if (observation.uncertainAttribution) "可能引用了他人内容，待确认" else "待核实，不代表作者或你的看法"}")
                appendLine("与历史对比：${observation.increment.label}；用于对比的早期记录：${observation.previousEventId ?: "无"}")
                if (observation.evidenceDetails.isNotEmpty()) observation.evidenceDetails.forEach {
                    appendLine("${it.type.label}${if (it.isNew) "（对比历史为新增）" else ""} · 同一记录原文：${it.text}")
                } else observation.evidence.forEach { appendLine("同一记录的依据/条件片段：$it") }
                if (observation.evidence.isEmpty()) appendLine("没有识别到相关依据片段，无法判断信息量是否有变化。")
                appendLine()
            }
        }
        appendLine("## 7. 方法与局限")
        appendLine("方法：文字过滤 → 词表主题识别 → 判断句提取 → 讨论对象匹配 → 中文字符相似度对比 → 事实/条件/建议/解释片段标注 → 按浏览时间顺序与历史对比。")
        appendLine("新版本按连续可见的内容记录浏览；旧版本只合并同一应用 30 秒内内容相同的页面记录，音频转写片段不参与页面去重。相似观点的判定阈值为 0.65，依据重复的阈值为 0.85；数字不同会保留为新增片段；讨论对象或时间、地区、人群条件不同的内容单独保留，出处不明的内容不参与新旧对比。每次最多对比最近 120 个同主题观点，这些阈值还没有经过标注数据验证。")
        appendLine("同一记录出现的片段尚不能证明是对该观点的支撑；本方法没有判断支持或反驳的真实语义、信息真实性、来源是否独立，以及你是否认同。新增片段也不等同于实质的信息增量。")
        appendLine("这份回顾使用本地文字规则，没有使用多模态模型、语义编码、交叉验证、时序分析、阅读投入估计或可信执行环境等研究方法，规则匹配的结果不能替代它们。音频由本地语音模型转写后才进入文字分析。")
        appendLine("这份回顾在本机生成。语音转写在本机完成；只有主动配置并使用云端文字分析时，当前转写与少量相关历史文字才会发送到指定服务。PDF 和文本报告包含原文引用，导出后由你自行保管；清空应用记录不会删除已导出的文件。")
    }

    fun writePdf(report: CognitiveReport, output: OutputStream) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        var number = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { document.finishPage(it) }
            number++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            paint.color = Color.rgb(20, 88, 78)
            paint.textSize = 10f
            page!!.canvas.drawText("破茧 Emerge | 信息环境回顾", 44f, 30f, paint)
            page!!.canvas.drawText("$number", 540f, 817f, paint)
            y = 60f
        }
        try {
            newPage()
            text(report, appendixLimit = 100).lines().forEach { raw ->
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
            document.writeTo(output)
        } finally {
            page?.let { document.finishPage(it) }
            document.close()
        }
    }

    fun percent(part: Long, total: Long) = if (total == 0L) "0.0%" else String.format(java.util.Locale.CHINA, "%.1f%%", part * 100.0 / total)

    fun percent(part: Int, total: Int) = if (total == 0) "0.0%" else String.format(java.util.Locale.CHINA, "%.1f%%", part * 100.0 / total)
}
