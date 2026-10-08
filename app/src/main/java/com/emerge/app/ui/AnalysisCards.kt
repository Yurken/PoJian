package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AnalysisCards(state: AnalysisState, period: StatisticsPeriod, events: List<BrowseEvent>,
    onAnalyze: (String) -> Unit, onExport: (CognitiveReport, Boolean) -> Unit,
    onPurposeChange: (String) -> Unit = {}) {
    var selected by remember { mutableStateOf<List<ClaimObservation>?>(null) }
    var hour by remember { mutableStateOf<Int?>(null) }
    val report = state.report?.takeIf { it.period == period.label }
    LaunchedEffect(report) { selected = null; hour = null }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        WhiteCard {
            SectionTitle("回顾我的信息环境", "完全离线 · 先看证据，再作判断")
            OutlinedTextField(value = state.purpose, onValueChange = onPurposeChange,
                label = { Text("我这段时间的浏览目的（选填）") }, placeholder = { Text("例如：比较求职资料、专题学习、休闲") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                supportingText = { Text("${state.purpose.length}/120 · 用于解释集中阅读，不改变观点规则") })
            Button(onClick = { onAnalyze(state.purpose) }, enabled = !state.running && !state.exporting && events.isNotEmpty()) {
                Text(if (state.running) "正在分析…" else if (!state.active) "开始分析${period.label}" else "立即更新报告")
            }
            state.status?.let { Text(it, color = Accent, fontSize = 12.sp) }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.active) Text("开始后，新增记录、浏览目的和区间变化会自动更新报告。报告在本次打开应用时生成，浏览记录与目的保存在本机。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            if (events.isEmpty()) Text("先选择应用并采集文字内容，再开始分析。", color = Muted, fontSize = 13.sp)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (report == null) {
            WhiteCard {
                SectionTitle("观点与依据观察")
                Text(if (state.running) "正在整理已保存的文字" else "当前区间尚无报告", fontSize = 22.sp)
                Text("报告会展示观点引文、主题、依据变化与可核查的时间索引。短期集中阅读、观点一致或负面表达不会直接被判为信息茧房。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
            }
        } else {
            WhiteCard {
                SectionTitle("这份报告看到了多少", "分析至 ${reportTime(report.end, report.zone)} · ${report.period}")
                if (state.stale) Text("有新记录待处理，以下是上一次完成的快照；更新完成后可导出。", color = Accent, fontSize = 12.sp)
                Text("${report.rawRecords} 条窗口记录", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
                Text("估计可见停留 ${formatDuration(report.observedDurationMillis)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("${report.coverageSessions} 条可用接触记录 · ${report.unknownDurationRecords} 条时长未知", color = Muted, fontSize = 13.sp)
                Text("保留 ${report.usableSnapshots} 个可用快照；合并 ${report.mergedSnapshots} 条连续重复画面；跳过 ${report.skippedRecords} 条界面或无正文记录。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                Text("这是已保留的授权应用可见文字，无法计算全设备采集覆盖率。未暴露正文、未授权应用、视频和音频均未知。停留不等于实际阅读、理解或认同。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            WhiteCard {
                SectionTitle("24 小时信息回顾", if (period == StatisticsPeriod.WEEK) "最近 7 天按钟点合计 · 左右滑动并点击小时条" else "今天 00—23 时 · 左右滑动并点击小时条")
                HourlyBars(report, hour) { hour = it }
                Text("柱高表示该钟点的估计可见停留，条数单独标注。小时之间按记录分摊时间，不按观点个数累加；空白不代表没有浏览。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            if (report.topicExposure.isNotEmpty()) WhiteCard {
                SectionTitle("各类内容的接触时间", "按窗口记录的主主题归类，不将同屏多个观点重复计时")
                report.topicExposure.forEach { exposure ->
                    Text(exposure.topic, fontWeight = FontWeight.Medium)
                    Text("${exposure.recordCount} 条 · ${formatDuration(exposure.durationMillis)}", color = Muted, fontSize = 13.sp)
                }
                Text("时长只包含有估计值的记录；未识别主题与娱乐内容仍保留。条目比例与时长比例不混用，也不能推断阅读价值。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            WhiteCard {
                SectionTitle("我接触了哪些观点", "${report.claims.size} 个规则候选 · 点击引文核查")
                if (report.claims.isEmpty()) Text("未提取到明确观点候选。短评、娱乐和界面标签可能无法分析；这不代表没有浏览，也不能说明不存在信息茧房。", color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                else {
                    val shares = report.topics.take(5).map { AppShare(it.topic, it.count) } +
                        if (report.topics.size > 5) listOf(AppShare("其他主题", report.topics.drop(5).sumOf { it.count })) else emptyList()
                    DonutChart(shares, report.claims.size, "个观点候选")
                    shares.forEachIndexed { index, share ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(ChartColors[index]))
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(share.packageName, fontSize = 13.sp)
                                Text("${share.count} 个 · ${ReportExporter.percent(share.count, report.claims.size)}", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                    Text("分母是提取到的观点候选，同屏可有多个候选。此占比不代表帖子占比或阅读时长。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                    report.groups.take(10).forEach { group ->
                        HorizontalDivider(color = PageBackground)
                        Column(Modifier.fillMaxWidth().clickable { selected = group.observations }.padding(vertical = 8.dp)) {
                            Text(group.quote, fontSize = 14.sp, lineHeight = 22.sp)
                            Text("${group.topic} · ${group.observations.size} 个近似措辞候选 · 查看原文与依据 ›", color = Blue, fontSize = 12.sp)
                        }
                    }
                    if (report.groups.size > 10) Text("此处展示前 10 组；完整候选索引见文本报告。", color = Muted, fontSize = 12.sp)
                }
            }
            WhiteCard {
                SectionTitle("有没有持续重复的线索", "结合历史依据和时间观察，不给出认知风险分数")
                report.findings.forEach { finding ->
                    Text(finding.topic, fontWeight = FontWeight.SemiBold)
                    Text(finding.title, color = Accent, fontSize = 16.sp)
                    Text(finding.explanation, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                    val referenced = report.claims.filter { it.eventId in finding.eventIds }
                    if (referenced.isNotEmpty()) TextButton(onClick = { selected = referenced }) { Text("核查这条观察的引文 ›") }
                }
                Text("尚未验证表达者、真实立场和完整材料源头。结果只描述已观察到的接触结构，不推断你的认同、情绪或认知受损。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            WhiteCard {
                SectionTitle("后续材料增加了什么", "相对于已保留的同主题浏览历史")
                Increment.entries.forEach { kind ->
                    val count = report.claims.count { it.increment == kind }
                    Text("${kind.label}：$count 个", fontSize = 13.sp)
                }
                Text("新片段可能是事实、条件、解释或建议；同屏片段未必支撑该观点。未提取到依据时保留未知，新增片段也不等于新事实或知识增长。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            if (report.contentSignals.isNotEmpty()) WhiteCard {
                SectionTitle("内容中的表达线索", "只描述文本，不推断你的心理状态")
                report.contentSignals.take(8).forEach { signal ->
                    Text(signal.label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(signal.quote, fontSize = 13.sp, lineHeight = 21.sp)
                    events.firstOrNull { it.id == signal.eventId }?.let { event ->
                        var showOriginal by remember(signal.eventId, signal.quote) { mutableStateOf(false) }
                        Text("对应事件 #${event.id} · ${reportTime(event.timestamp, report.zone)}", color = Muted, fontSize = 12.sp)
                        TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "收起原文" else "查看对应采集原文 ›") }
                        if (showOriginal) Text(event.visibleText, fontSize = 12.sp, lineHeight = 21.sp)
                    }
                }
                Text("表达线索与信息增量需要结合核查，不以负面情绪判断内容有害，也不以缺少知识增量评价娱乐内容。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            WhiteCard {
                SectionTitle("保存这次观察", "包含观点、依据、小时索引、观察范围与方法限制")
                Button(onClick = { onExport(report, true) }, enabled = !state.exporting && !state.running && !state.stale,
                    modifier = Modifier.fillMaxWidth()) { Text("保存 PDF 报告") }
                OutlinedButton(onClick = { onExport(report, false) }, enabled = !state.exporting && !state.running && !state.stale,
                    modifier = Modifier.fillMaxWidth()) { Text("保存完整文本报告") }
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportMessage?.let { Text(it, color = Accent, fontSize = 12.sp) }
                Text("报告含浏览引文。PDF 附录最多 100 个候选，完整索引请保存文本。清空破茧不会删除已导出的文件。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                Text("先检查观点的对象、时间和适用条件，再按自己的浏览目的补充原始数据、反例或行动建议。是否调整信息选择，由你决定。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
    }
    if (report != null) {
        selected?.let { candidates ->
            AlertDialog(onDismissRequest = { selected = null }, title = { Text("核查观点与依据") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(candidates) { _, item -> ClaimDetail(item, events, report.zone) }
                    }
                }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
        }
        hour?.let { value ->
            val review = report.hourlyReviews.firstOrNull { it.hour == value }
            val claimEventIds = review?.claimEventIds.orEmpty().toSet()
            val candidates = report.claims.filter { it.eventId in claimEventIds }
            val window = ObservationWindow(report.start, report.end, ZoneId.of(report.zone))
            val hourRecords = events.filter { event -> window.slices(event).any { it.hour == value } }
            AlertDialog(onDismissRequest = { hour = null }, title = { Text("${value.toString().padStart(2, '0')}:00—${(value + 1).toString().padStart(2, '0')}:00") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 480.dp).testTag("hourly-review-details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text(if (period == StatisticsPeriod.WEEK) "最近 7 天同钟点合计" else "今天该时段", color = Muted, fontSize = 12.sp)
                            Text("${review?.recordCount ?: 0} 条可用接触记录 · ${formatDuration(review?.durationMillis ?: 0)}", fontWeight = FontWeight.SemiBold)
                            Text("${review?.unknownDurationRecords ?: 0} 条时长未知 · ${candidates.size} 个观点候选", color = Muted, fontSize = 12.sp)
                            review?.topics?.forEach { Text("${it.topic}：${it.count} 条接触记录（分母 ${review.recordCount} 条）", fontSize = 12.sp) }
                            if (candidates.isEmpty()) Text("此时段没有识别出的观点候选。没有观点或记录不代表没有浏览。", color = Muted)
                        }
                        itemsIndexed(candidates) { _, item -> ClaimDetail(item, events, report.zone) }
                        item {
                            HorizontalDivider(color = PageBackground)
                            Text("回查这个时段的采集原文", fontWeight = FontWeight.SemiBold)
                            Text("${hourRecords.size} 条原始记录，包含规则跳过的内容。跨小时记录可在两个时段回查，停留按各时段分摊。", color = Muted, fontSize = 12.sp)
                        }
                        itemsIndexed(hourRecords) { _, event ->
                            var expanded by remember(event.id) { mutableStateOf(false) }
                            val duration = window.slices(event).filter { it.hour == value }.sumOf { it.durationMillis }
                            Text("事件 #${event.id} · ${reportTime(event.timestamp, report.zone)}", color = Accent, fontSize = 12.sp)
                            Text(event.packageName, color = Muted, fontSize = 11.sp)
                            Text(if (window.durationKnown(event)) "本时段估计可见停留 ${formatDuration(duration)}" else "时长未知", color = Muted, fontSize = 12.sp)
                            Text(event.visibleText, maxLines = if (expanded) Int.MAX_VALUE else 4, fontSize = 12.sp, lineHeight = 21.sp)
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看完整原文") }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { hour = null }) { Text("关闭") } })
        }
    }
}

@Composable
private fun HourlyBars(report: CognitiveReport, selected: Int?, onSelect: (Int) -> Unit) {
    val maximum = report.hourlyReviews.maxOfOrNull { it.durationMillis }?.coerceAtLeast(1) ?: 1L
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(24) { hour ->
            val review = report.hourlyReviews.firstOrNull { it.hour == hour }
            val duration = review?.durationMillis ?: 0L
            val records = review?.recordCount ?: 0
            Column(Modifier.width(48.dp).clip(RoundedCornerShape(12.dp))
                .clickable { onSelect(hour) }.padding(4.dp)
                .semantics { contentDescription = "${hour}时，${records}条记录，估计可见停留${formatDuration(duration)}，点击查看详情" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(PageBackground), contentAlignment = Alignment.BottomCenter) {
                    if (duration > 0) Box(Modifier.fillMaxWidth().fillMaxHeight((duration.toFloat() / maximum).coerceIn(.04f, 1f))
                        .clip(RoundedCornerShape(8.dp)).background(if (selected == hour) Amber else Accent))
                }
                Text(hour.toString().padStart(2, '0'), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("$records 条", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ClaimDetail(item: ClaimObservation, events: List<BrowseEvent>, zone: String) {
    Text("事件 #${item.eventId} · ${reportTime(item.timestamp, zone)}", color = Accent, fontSize = 12.sp)
    Text(item.packageName, color = Muted, fontSize = 11.sp)
    Text(item.quote, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
    Text("讨论对象：${item.subject.ifBlank { "未知，需核查" }}", fontSize = 13.sp)
    Text("适用条件：${item.conditions.ifBlank { "未提取到明确条件" }}", fontSize = 13.sp)
    Text("表达归属：${if (item.uncertainAttribution) "含引用或转折线索，未知" else "待核查，不代表用户认同"}", color = Muted, fontSize = 12.sp)
    Text("估计可见停留：${item.durationMillis?.let(::formatDuration) ?: "旧记录未知"}（同屏候选共享，不能累加）", color = Muted, fontSize = 12.sp)
    Text("历史比较：${item.increment.label}", color = Accent, fontSize = 13.sp)
    if (item.evidenceDetails.isNotEmpty()) item.evidenceDetails.forEach { fragment ->
        Text("${fragment.type.label}候选：${fragment.text}", fontSize = 12.sp, lineHeight = 21.sp)
    } else item.evidence.forEach { Text("同屏依据候选：$it", fontSize = 12.sp, lineHeight = 21.sp) }
    if (item.evidence.isEmpty()) Text("没有提取到依据片段，信息增量未知。", color = Muted, fontSize = 12.sp)
    val current = events.firstOrNull { it.id == item.eventId }
    Text("当前采集原文：\n${current?.visibleText ?: "记录已不在本地保留范围"}", fontSize = 12.sp, lineHeight = 21.sp)
    item.previousEventId?.let { previous ->
        val previousEvent = events.firstOrNull { it.id == previous }
        Text("比较的先前事件 #$previous", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        if (previousEvent != null) Text("${reportTime(previousEvent.timestamp, zone)} · ${previousEvent.packageName}\n${previousEvent.visibleText}", fontSize = 12.sp, lineHeight = 21.sp)
        else Text("该事件已不在本地保留范围，无法回查原文；不能补造其依据。", color = Muted, fontSize = 12.sp)
    }
    HorizontalDivider(color = PageBackground)
}

private fun reportTime(timestamp: Long, zone: String): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
