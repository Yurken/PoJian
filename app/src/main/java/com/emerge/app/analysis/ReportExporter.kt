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
        appendLine("# 破茧 Emerge · 个人信息环境观察报告")
        appendLine("看见信息边界，拓宽判断依据。")
        appendLine("生成时间：${time(report.generatedAt)}；时区：${report.zone}")
        appendLine("观察区间：${time(report.start)} 至 ${time(report.end)}（${report.period}）")
        appendLine("今天从当地 00:00 开始，七天从六天前当地 00:00 开始；今天尚未发生的时段显示零记录。")
        appendLine("方法：${report.method}")
        appendLine("浏览目的：${report.purpose}")
        appendLine()
        appendLine("## 1. 覆盖范围与数据质量")
        appendLine("窗口内原始接触记录 ${report.rawRecords} 条；合并连续相同画面 ${report.mergedSnapshots} 条；跳过系统桌面/无正文 ${report.skippedRecords} 条；保留可用接触条目 ${report.usableSnapshots} 条。跨窗口边界的已测量会话按交集纳入。")
        appendLine("可用接触条目分母 ${report.coverageSessions} 条；窗口内估计可见停留合计 ${duration(report.observedDurationMillis)}；${report.unknownDurationRecords} 条时长未知。已知为零仅表示尚未测得连续检查间隔。")
        appendLine("提取观点候选 ${report.claims.size} 个，接触记录覆盖 ${report.observedDays} 个自然日。同一条目可以包含多个观点候选，观点数不能换算成独立帖子、阅读次数或浏览时长。")
        appendLine("停留时长按相邻检查的单调时钟间隔估计；超过 6 秒的检查断档结束会话。每条会话只计一次，按与观察窗口、自然日及整点小时的交集分配。不等于实际阅读、理解或视频观看时长；旧快照不补造时长。")
        appendLine("数据来自当前保留的最多 5000 条采集记录，不覆盖视频画面、音频、未暴露节点的正文或已删除历史。零记录可能来自未采集、权限或节点限制，不能解释为未浏览。")
        appendLine("窗口前保留历史 ${report.comparisonRecords} 条${report.comparisonStart?.let { "，最早 ${time(it)}" } ?: ""}；历史比较仅使用这些保留记录与按浏览发生时间排列的窗口记录。“首次观察”仅指没有匹配到可比历史，不是从未接触过该内容。")
        appendLine()
        appendLine("## 2. 主题接触与观点分布")
        report.topicExposure.forEach {
            val durationShare = if (report.observedDurationMillis > 0) percent(it.durationMillis, report.observedDurationMillis)
                else "暂不可计算"
            appendLine("${it.topic}：${it.recordCount}/${report.coverageSessions} 条（${percent(it.recordCount, report.coverageSessions)}）；估计可见停留 ${duration(it.durationMillis)}；时长占比 $durationShare")
        }
        appendLine("接触主题按每条可用记录的主要词表主题归类，一条仅归入一个主题；含未分类与娱乐。条目比例分母为全部可用接触条目，时长比例分母为窗口内测得的估计可见停留总量。未知时长不按零阅读解释。")
        appendLine("娱乐与休闲单独统计，不因缺少论据或重复观看而评价为低信息增量。专题阅读的集中也可能符合当前浏览目的。")
        report.topics.forEach { appendLine("${it.topic}：${it.count}/${report.claims.size} 个观点候选（${percent(it.count, report.claims.size)}）") }
        if (report.claims.isEmpty()) appendLine("没有匹配规则的观点候选。不要将其解释为没有浏览或没有信息茧房。")
        appendLine("观点比例分母为提取到的候选，不是全部内容或停留时间。主题仅覆盖预设词表；立场未作语义验证。")
        appendLine()
        appendLine("## 3. 信息茧房观察与内容线索")
        report.findings.forEach {
            appendLine("${it.topic}：${it.title}")
            appendLine(it.explanation)
            appendLine("核查事件：${it.eventIds.joinToString().ifBlank { "无" }}")
        }
        appendLine("线索规则：同主题至少 8 个不同事件、覆盖至少 3 个自然日；最大近似措辞组覆盖的事件占比至少 70%，且有依据重复的事件占比至少 60%。同条目的多个观点不会增加接触次数或时长权重。这是未校准的工程阈值，不是科学诊断或认知风险评分。")
        appendLine("未匹配到上述规则不代表没有信息茧房；应用数、主题集中、情绪和观点一致均不单独决定结论。时长不足或未知时，不能据次数判断主要阅读投入。")
        val signalLimit = if (appendixLimit == Int.MAX_VALUE) Int.MAX_VALUE else 20
        report.contentSignals.take(signalLimit).forEach {
            appendLine("事件 #${it.eventId} · ${it.label}：${it.quote}")
        }
        if (report.contentSignals.isEmpty()) appendLine("未匹配到预设的焦虑/紧迫表达词。")
        if (report.contentSignals.size > signalLimit) appendLine("PDF 仅列前 $signalLimit 条内容表达线索；完整索引见文本报告。")
        appendLine("上述线索仅描述可核查的内容措辞，不推断用户情绪、心理状态、认同或认知受损。含有焦虑表达也可能提供实质信息，需结合原文与依据核查。")
        appendLine()
        appendLine("## 4. 来源与 24 小时复盘")
        report.sourceCounts.entries.sortedByDescending { it.value }.forEach { appendLine("${it.key}：${it.value} 条原始记录") }
        appendLine("应用来源不是作者、媒体或原始材料源头。跨应用内容可能同源；上述计数含尚未清洗的记录。")
        appendLine(if (report.period.contains("7")) "以下为七天中相同当地钟点的合计。" else "以下从当地 00:00 到 23:00，覆盖全天 24 个整点时段。")
        (0..23).forEach { hour ->
            val review = report.hourlyReviews.firstOrNull { it.hour == hour }
            val label = "${hour.toString().padStart(2, '0')}:00–${((hour + 1) % 24).toString().padStart(2, '0')}:00"
            if (review == null) {
                appendLine("$label：${report.hourlyClaims.getOrElse(hour) { 0 }} 个观点候选；旧版报告未记录条目与时长复盘。")
            } else {
                appendLine("$label：${review.recordCount} 条接触；估计可见停留 ${duration(review.durationMillis)}；${review.unknownDurationRecords} 条时长未知；主题 ${review.topics.joinToString { "${it.topic} ${it.count}条" }.ifBlank { "无" }}；观点证据事件 ${review.claimEventIds.joinToString { "#$it" }.ifBlank { "无" }}")
            }
        }
        appendLine("跨小时的同一会话在接触到的每个小时列出，跨日会话同理，因此各时段条目数不应直接相加为独立条目；估计停留已切分，总时长不会重复累计。观点引文通过事件编号回到附录核查。")
        appendLine()
        appendLine("## 5. 建议与核查")
        appendLine("先检查下方引文是否属于正文、评论或引用，核对讨论对象、时间和适用条件，再比较原始数据、反例及行动建议。根据你的阶段性目的解释集中阅读。")
        appendLine("如果同一判断反复出现，优先核查它是否引用同一材料；看到新数字、条件或建议不代表该材料真实，也不代表已增加个人知识。")
        appendLine()
        appendLine("## 6. 观点、条件与依据索引")
        var remaining = appendixLimit.coerceAtLeast(0)
        if (report.claims.size > remaining) appendLine("PDF 附录仅列前 $remaining 个候选以控制文件大小；全部候选请导出文本报告。统计与观察仍使用完整分析快照。")
        report.groups.forEachIndexed { index, group ->
            if (remaining <= 0) return@forEachIndexed
            appendLine("### 组 ${index + 1} · ${group.topic} · ${group.observations.size} 个候选")
            appendLine("代表原文：${group.quote}")
            val shown = group.observations.take(remaining)
            remaining -= shown.size
            shown.forEach { observation ->
                appendLine("事件 #${observation.eventId} | 会话开始 ${time(observation.timestamp)} | ${observation.packageName}")
                appendLine("该条目在本观察窗口内估计可见停留：${observation.durationMillis?.let { duration(it) } ?: "未知"}（同一条目多观点共享时长，不可累加）")
                appendLine("观点候选原文：${observation.quote}")
                appendLine("讨论对象片段：${observation.subject.ifBlank { "规则未确定，暂不与历史关联" }}")
                appendLine("条件：${observation.conditions.ifBlank { "规则未提取到明确条件" }}；归属：${if (observation.uncertainAttribution) "含引用/转折线索，未知" else "待核查，不代表作者或用户认同"}")
                appendLine("历史比较：${observation.increment.label}；关联先前事件：${observation.previousEventId ?: "无"}")
                if (observation.evidenceDetails.isNotEmpty()) observation.evidenceDetails.forEach {
                    appendLine("${it.type.label}${if (it.isNew) "（相比关联历史新增）" else ""} · 同屏候选原文：${it.text}")
                } else observation.evidence.forEach { appendLine("同屏依据/条件候选：$it") }
                if (observation.evidence.isEmpty()) appendLine("没有提取到依据片段；不能判定实质低增量。")
                appendLine()
            }
        }
        appendLine("## 7. 方法与限制")
        appendLine("文本过滤 + 词表主题识别 + 判断句候选 + 保守的讨论对象片段匹配 + 中文字符二元组相似度 + 事实/条件/建议/解释片段标记 + 按浏览时间排序的历史比较。")
        appendLine("新版按连续可见内容记录会话；旧快照仅合并同应用相邻 30 秒内稳定正文相同的记录。近似观点阈值 0.65，依据重复阈值 0.85，数字不同时保留为新增片段；讨论对象、时间/地区/人群条件不同的材料分别保留，归属未知的材料不建立增量比较。比较最近最多 120 个同主题候选，阈值待标注数据验证。")
        appendLine("同屏片段尚未证明是对该观点的支撑；未判断支持/反驳的真实语义、信息真实性、来源独立性或用户认同。新增片段不等同于实质信息增量。")
        appendLine("未实现多模态模型、双路语义编码、交叉编码器、时序图神经网络、阅读投入估计、TEE 或远程证明。规则关系索引不能替代这些研究方法。")
        appendLine("完全离线，无网络上传。PDF 和文本报告包含浏览引文，导出后由你自行管理；清空应用记录不会删除已导出的文件。")
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
            page!!.canvas.drawText("破茧 Emerge | 个人信息环境观察", 44f, 30f, paint)
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
