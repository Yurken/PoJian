package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisCards(
    state: AnalysisState,
    period: StatisticsPeriod,
    events: List<BrowseEvent>,
    onAnalyze: (String) -> Unit,
    onExport: (CognitiveReport, Boolean) -> Unit,
    onPurposeChange: (String) -> Unit = {},
    appNames: Map<String, String> = emptyMap()
) {
    var selectedClaims by remember { mutableStateOf<List<ClaimObservation>?>(null) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var detailHour by remember { mutableStateOf<Int?>(null) }
    var editingPurpose by remember { mutableStateOf(false) }
    var purposeDraft by remember { mutableStateOf("") }
    val report = state.report?.takeIf { it.period == period.label }
    LaunchedEffect(report) {
        selectedClaims = null
        detailHour = null
        selectedHour = report?.hourlyReviews?.maxWithOrNull(
            compareBy<HourlyReview> { it.recordCount }.thenBy { it.durationMillis }
        )?.takeIf { it.recordCount > 0 }?.hour
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WhiteCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MetadataText("浏览目的")
                    Text(state.purpose.ifBlank { "还未填写" }, color = Ink, fontSize = 13.sp,
                        maxLines = 2, lineHeight = 20.sp)
                }
                TextButton(onClick = { purposeDraft = state.purpose; editingPurpose = true },
                    modifier = Modifier.testTag("purpose-edit")) { Text("编辑") }
            }
            Button(
                onClick = { onAnalyze(state.purpose) },
                enabled = !state.running && !state.exporting && events.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(if (state.running) "正在整理…" else if (!state.active) "生成${period.label}回顾" else "更新回顾")
            }
            state.status?.let { MetadataText(it) }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.stale && report != null) MetadataText("有新记录，正在更新 · 以下为上次回顾")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }

        if (report == null) {
            WhiteCard {
                SectionTitle(if (events.isEmpty()) "还没有浏览记录" else "回顾已准备好")
                MetadataText(if (events.isEmpty()) "开启采集后，可按小时回看原文。"
                    else if (state.running) "正在整理小时索引与观点依据…"
                    else "生成回顾，查看观点与依据的变化。")
            }
        } else {
            WhiteCard {
                SectionTitle("分析覆盖", "${report.usableSnapshots} 条有效记录")
                MetadataText("云端结构结果 ${report.structuredAnalyzedRecords} 条 · 本地规则 ${report.localFallbackRecords} 条")
                MetadataText("已记录待分析 ${report.pendingAnalysisRecords} 条 · 最近未完成 ${report.failedAnalysisRecords} 条（任务状态保留 24 小时）")
                MetadataText("有效结构结果覆盖 ${ReportExporter.percent(report.structuredAnalyzedRecords, report.usableSnapshots)}，包含未提取出观点的记录；每次最多提取 3 个观点，采样不代表整条记录或全部设备浏览都已分析。")
                if (report.rejectedSemanticsRecords > 0) MetadataText("${report.rejectedSemanticsRecords} 条云端结果与当前原文不匹配或未通过校验，使用本地规则。")
                if (report.structuredAnalyzedRecords == 0) MetadataText("当前回顾没有有效云端结构结果，观点与依据来自本地规则候选。")
            }
            WhiteCard {
                SectionTitle("24 小时信息回顾",
                    if (period == StatisticsPeriod.WEEK) "最近 7 天按小时合计" else if (period == StatisticsPeriod.HOURS24) "过去24小时按钟点合计" else "今天 00:00—24:00")
                HourlyRecordHeatmapContent(
                    counts = List(24) { hour -> report.hourlyReviews.firstOrNull { it.hour == hour }?.recordCount ?: 0 },
                    selectedHour = selectedHour,
                    onSelect = { selectedHour = it; detailHour = it },
                    modifier = Modifier.testTag("hourly-hour-options"),
                    countLabel = "数字为有效记录数"
                ) { hour, records ->
                    val review = report.hourlyReviews.firstOrNull { it.hour == hour }
                    val duration = review?.durationMillis ?: 0L
                    val unknown = review?.unknownDurationRecords ?: 0
                    when {
                        records == 0 -> "${hour}时，暂无记录，点击查看详情"
                        unknown == records && duration == 0L -> "${hour}时，${records}条记录，未统计时长，点击查看详情"
                        unknown > 0 -> "${hour}时，${records}条记录，预计停留${formatDuration(duration)}，${unknown}条未统计时长，点击查看详情"
                        else -> "${hour}时，${records}条记录，预计停留${formatDuration(duration)}，点击查看详情"
                    }
                }
                selectedHour?.let { value ->
                    val review = report.hourlyReviews.firstOrNull { it.hour == value }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(hourRange(value), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${review?.recordCount ?: 0} 条可用记录 · ${hourDuration(review)}",
                                color = Muted, fontSize = 11.sp)
                            if (review != null && review.unknownDurationRecords > 0) {
                                MetadataText("另有 ${review.unknownDurationRecords} 条未统计时长")
                            }
                        }
                        TextButton(onClick = { detailHour = value }) { Text("原文 ›") }
                    }
                }
                ExpandableNote("分布说明",
                    "颜色越深，该小时有效记录越多；数字是记录数，不是浏览时长。点任意小时可以回看原文。跨时段的记录会在每个覆盖时段出现，各时段数量不能相加作为总条数。预计停留时长按时间分摊，同一条记录的时长不会重复计算。空白不代表没有浏览。")
            }

            WhiteCard {
                SectionTitle("观点依据比对", "${report.claims.size} 个观点 · ${report.groups.size} 组相似说法")
                if (report.claims.isEmpty()) {
                    MetadataText("暂未识别到明确观点，可按小时回看原文。")
                } else {
                    EvidenceRatioBar(report.claims)
                    var groupLimit by remember(report) { mutableIntStateOf(3) }
                    report.groups.take(groupLimit).forEachIndexed { index, group ->
                        GroupRow(index + 1, group) { selectedClaims = group.observations }
                    }
                    if (report.groups.size > 3) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (report.groups.size > groupLimit) TextButton(onClick = { groupLimit += 3 }) {
                                Text("再看 ${minOf(3, report.groups.size - groupLimit)} 组")
                            }
                            if (groupLimit > 3) TextButton(onClick = { groupLimit = 3 }) { Text("收起") }
                        }
                    }
                }
                ExpandableNote("依据比例与判断方法",
                    "比例分母为本卡标注的全部观点数，一条记录可能有多个观点。蓝色表示和此前同主题内容比较时依据有新增，橙色表示重复出现且依据未变；首次出现、时间或条件不同、出处或依据不足的观点单独保留，不并入新增或重复。新增片段可能是事实、条件、解释或建议，不代表学到了新东西，也未验证真伪。未识别到观点不代表没有浏览。")
            }

            if (report.topicExposure.isNotEmpty() || report.topics.isNotEmpty()) {
                WhiteCard {
                    CollapsibleReportSection("接触内容", "${report.coverageSessions} 条有效记录") {
                        report.topicExposure.forEachIndexed { index, exposure ->
                            ProportionRow(
                                label = exposure.topic,
                                value = "${exposure.recordCount} 条 · ${ReportExporter.percent(exposure.recordCount, report.coverageSessions)}",
                                fraction = fraction(exposure.recordCount, report.coverageSessions),
                                color = ChartColors[index % ChartColors.size],
                                note = if (exposure.durationMillis > 0) "停留约 ${formatDuration(exposure.durationMillis)}（估算）" else "暂无停留数据"
                            )
                        }
                        MetadataText("主题比例分母：${report.coverageSessions} 条有效记录，每条按主要内容归一类。")
                        if (report.topics.isNotEmpty()) {
                            Text("观点概括", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            report.claims.groupBy { viewpointClaim(it).key }.entries.forEachIndexed { index, entry ->
                                ProportionRow(viewpointClaim(entry.value.first()).label,
                                    "${entry.value.size} 个 · ${ReportExporter.percent(entry.value.size, report.claims.size)}",
                                    fraction(entry.value.size, report.claims.size), ChartColors[index % ChartColors.size])
                            }
                            MetadataText("观点主题分母：${report.claims.size} 个观点，一条记录可能有多个观点。")
                        }
                        ExpandableNote("比例范围",
                            "有效记录包含未分类和娱乐内容。主题比例不代表阅读时长或价值；没统计到时长不代表没看。休闲浏览也可以是你的目的。")
                    }
                }
            }

            WhiteCard {
                CollapsibleReportSection("持续观察", "${report.findings.size} 条观察") {
                    report.findings.forEach { finding ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MetadataText(finding.topic)
                            Text(finding.title, color = Ink, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                            val referenced = report.claims.filter {
                                it.eventId in finding.eventIds && (finding.topic == "全部" || it.topic == finding.topic)
                            }
                            if (referenced.isNotEmpty()) TextButton(onClick = { selectedClaims = referenced },
                                contentPadding = PaddingValues(horizontal = 0.dp)) { Text("原文依据 ›") }
                            ExpandableNote("依据与范围", finding.explanation)
                        }
                    }
                    ExpandableNote("结果说明",
                        "主题集中、观点一致或负面情绪，都不会单独得出结论。云端结果对观点结构作出估计，本地规则提供候选；两者都需要核查表达者和材料源头。结果不会推断你的态度、情绪或认知状况，也不能证明你是否处于信息茧房。")
                }
            }

            if (report.contentSignals.isNotEmpty()) {
                WhiteCard {
                    CollapsibleReportSection("文字表达线索", "${report.contentSignals.size} 条线索") {
                        MetadataText("只描述原文的措辞，不能推断你的心理状态。")
                        report.contentSignals.take(8).forEach { signal ->
                            Text(signal.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            QuoteBlock(signal.quote)
                            events.firstOrNull { it.id == signal.eventId }?.let { event ->
                                OriginalRecord(event, report.zone, appNames, "对应原文：")
                            }
                        }
                        if (report.contentSignals.size > 8) MetadataText("显示前 8 条，完整列表可导出。")
                    }
                }
            }

            WhiteCard {
                SectionTitle("保存回顾", "${report.usableSnapshots} 条有效记录 · 原文可回查")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onExport(report, true) },
                        enabled = !state.exporting && !state.running && !state.stale,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small) { Text("导出 PDF") }
                    OutlinedButton(onClick = { onExport(report, false) },
                        enabled = !state.exporting && !state.running && !state.stale,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small) { Text("导出文本") }
                }
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportMessage?.let { MetadataText(it) }
                ExpandableNote("导出说明",
                    "报告包含生成时的原文引用。PDF 附录最多 100 个观点，完整列表请导出文本。清空应用不会删除已导出的文件。")
                ExpandableNote("数据范围与方法",
                    "${report.period}，统计至 ${reportTime(report.end, report.zone)}。共 ${report.rawRecords} 条原始记录，保留 ${report.usableSnapshots} 条有效记录，合并 ${report.mergedSnapshots} 条重复内容，跳过 ${report.skippedRecords} 条无正文的记录，${report.unknownDurationRecords} 条未统计时长。历史对比使用之前的 ${report.comparisonRecords} 条记录。\n\n${report.method}。覆盖已授权应用提供的可见文字、已保存的音频转写及视觉提取文字；视觉文字可能有误。未授权的页面文字、没获取到的正文、未转写的音频和未采样的视频画面不在统计范围内。音频转写与视觉文字不增加观看时长，媒体长度不计入浏览停留。停留时长不等于阅读、理解或认同。")
            }
        }
    }

    if (editingPurpose) {
        ModalBottomSheet(onDismissRequest = { editingPurpose = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PageBackground) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("这段浏览，你想看什么", "学习、核查或休闲，都可以是目的")
                OutlinedTextField(value = purposeDraft, onValueChange = { purposeDraft = it.take(120) },
                    label = { Text("我的浏览目的（选填）") },
                    placeholder = { Text("例如：比较求职资料、专题学习、休闲") },
                    supportingText = { Text("${purposeDraft.length}/120 · 仅用于生成回顾，不影响识别结果") },
                    modifier = Modifier.fillMaxWidth().testTag("purpose-input"), minLines = 2, maxLines = 4)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("专题学习", "核查资料", "比较选择", "休闲浏览").forEach { preset ->
                        SuggestionChip(onClick = {
                            if (!purposeDraft.contains(preset)) {
                                purposeDraft = if (purposeDraft.isBlank()) preset else
                                    "$purposeDraft；$preset".take(120)
                            }
                        }, label = { Text(preset) })
                    }
                }
                MetadataText("点击标签会自动填入，不会覆盖你已写的内容。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { editingPurpose = false }, modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small) { Text("取消") }
                    Button(onClick = { onPurposeChange(purposeDraft); editingPurpose = false },
                        modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) { Text("保存目的") }
                }
            }
        }
    }

    if (report != null) {
        selectedClaims?.let { candidates ->
            ModalBottomSheet(onDismissRequest = { selectedClaims = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = PageBackground) {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.92f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    item {
                        SheetHeading("查看观点和依据", "${candidates.size} 个观点 · 以采集原文为准") {
                            selectedClaims = null
                        }
                    }
                    itemsIndexed(candidates) { _, item -> ClaimDetail(item, events, report.zone, appNames) }
                }
            }
        }
        detailHour?.let { value ->
            val review = report.hourlyReviews.firstOrNull { it.hour == value }
            val claimEventIds = review?.claimEventIds.orEmpty().toSet()
            val candidates = report.claims.filter { it.eventId in claimEventIds }
            val window = ObservationWindow(report.start, report.end, ZoneId.of(report.zone))
            val hourRecords = events.filter { event -> window.slices(event).any { it.hour == value } }
            ModalBottomSheet(onDismissRequest = { detailHour = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = PageBackground) {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("hourly-review-details"),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SheetHeading(hourRange(value),
                                if (period == StatisticsPeriod.WEEK) "最近 7 天同一小时合计" else if (period == StatisticsPeriod.HOURS24) "过去24小时该钟点合计" else "今天该时段") {
                                detailHour = null
                            }
                            Text("${review?.recordCount ?: 0} 条有效记录 · ${hourDuration(review)}",
                                color = Ink, fontWeight = FontWeight.SemiBold)
                            MetadataText("${review?.unknownDurationRecords ?: 0} 条未统计时长 · ${candidates.size} 个观点")
                            review?.topics?.forEach {
                                ProportionRow(it.topic, "${it.count} 条", fraction(it.count, review.recordCount), Accent)
                            }
                            MetadataText("主题比例的分母是 ${review?.recordCount ?: 0} 条有效记录。")
                            if (candidates.isEmpty()) Text("这个时段没有识别到明确的观点。没有识别到不代表没有浏览。",
                                color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                        }
                    }
                    itemsIndexed(candidates) { _, item ->
                        val duration = events.firstOrNull { it.id == item.eventId }
                            ?.takeIf { window.durationKnown(it) }
                            ?.let { event -> window.slices(event).filter { it.hour == value }.sumOf { it.durationMillis } }
                        ClaimDetail(item, events, report.zone, appNames,
                            durationMillis = duration, durationLabel = "本时段预计停留")
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SectionTitle("回看这个时段的采集原文", "${hourRecords.size} 条原始记录，包含被自动跳过的内容")
                            MetadataText("跨小时的记录可以在两个时段分别回看，停留时长按时段分摊。")
                        }
                    }
                    itemsIndexed(hourRecords) { _, event ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val duration = window.slices(event).filter { it.hour == value }.sumOf { it.durationMillis }
                            MetadataText(if (event.sourceType.startsWith("audio_")) "音频转写 · 未统计观看时长"
                                else if (window.durationKnown(event)) "本时段预计停留 ${formatDuration(duration)}" else "未统计时长")
                            if (event.sourceType == "accessibility") CaptureVisibilityBreakdown(event, window, value)
                            OriginalRecord(event, report.zone, appNames, "采集原文：")
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleReportSection(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.Button) { expanded = !expanded }
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            MetadataText(summary)
        }
        Text(if (expanded) "收起 −" else "展开 ＋", color = Accent, fontSize = 12.sp)
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Unclassified comparisons stay in the denominator instead of becoming "new" evidence. */
@Composable
private fun EvidenceRatioBar(observations: List<ClaimObservation>) {
    val total = observations.size
    if (total == 0) return
    val repeated = observations.count { it.increment == Increment.REPEATED }
    val added = observations.count { it.increment == Increment.NEW }
    val first = observations.count { it.increment == Increment.FIRST }
    val conditions = observations.count { it.increment == Increment.CONDITIONS }
    val unknown = observations.count { it.increment == Increment.UNKNOWN }
    val remainder = total - repeated - added
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
            .semantics(mergeDescendants = true) {
                contentDescription = "$total 个观点：重复依据 $repeated 个，新增依据 $added 个，首次出现 $first 个，条件不同 $conditions 个，无法判断 $unknown 个"
            }) {
            if (repeated > 0) Box(Modifier.weight(repeated.toFloat()).fillMaxHeight().background(Amber))
            if (added > 0) Box(Modifier.weight(added.toFloat()).fillMaxHeight().background(Accent))
            if (remainder > 0) Box(Modifier.weight(remainder.toFloat()).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("重复 $repeated · ${ReportExporter.percent(repeated, total)}", color = Amber, fontSize = 11.sp)
            Text("新增 $added · ${ReportExporter.percent(added, total)}", color = Accent, fontSize = 11.sp)
        }
        MetadataText("分母 $total 个观点" + if (remainder > 0)
            " · " + listOfNotNull(if (first > 0) "首次 $first" else null,
                if (conditions > 0) "条件不同 $conditions" else null,
                if (unknown > 0) "未知 $unknown" else null).joinToString(" · ") else "")
    }
}

@Composable
private fun ProportionRow(label: String, value: String, fraction: Float, color: Color, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f), lineHeight = 20.sp)
            Text(value, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        note?.let { MetadataText(it) }
    }
}

@Composable
private fun GroupRow(index: Int, group: ClaimGroup, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${index.toString().padStart(2, '0')} · ${viewpointClaim(group.observations.first()).label}", color = Ink,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${group.observations.size} 个观点 ›", color = Muted, fontSize = 11.sp)
        }
        Text(group.quote, color = Ink, fontSize = 13.sp, lineHeight = 21.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        EvidenceRatioBar(group.observations)
        Text("原文与依据 ›", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ClaimDetail(item: ClaimObservation, events: List<BrowseEvent>, zone: String,
    appNames: Map<String, String>, durationMillis: Long? = item.durationMillis,
    durationLabel: String = "本次回顾范围内的预计停留") {
    val current = events.firstOrNull { it.id == item.eventId }
    val fromAudio = current?.sourceType?.startsWith("audio_") == true
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetadataText("记录 #${item.eventId} · ${reportTime(item.timestamp, zone)} · ${appNames[item.packageName] ?: item.packageName}")
        QuoteBlock(item.quote)
        Text(item.increment.label, color = incrementColor(item.increment), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetadataText(if (item.analysisSource == "structured_model") "观点来源：云端结构结果 · 仍需核对" else "观点来源：本地规则候选")
            Text("讨论对象：${item.subject.ifBlank { "未识别，请查看原文" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            if (item.analysisSource == "structured_model") {
                Text("判断维度：${item.dimension.ifBlank { "待确认" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
                Text("立场：${item.stance.ifBlank { "待确认" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            }
            Text("适用条件：${item.conditions.ifBlank { "未识别到适用条件" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            MetadataText("出处：${if (item.uncertainAttribution) "可能引用了他人内容，待确认" else "待核实，不代表你的看法"}")
            MetadataText(if (fromAudio) "音频转写 · 未统计观看时长，媒体长度不计入浏览停留。"
                else "$durationLabel：${durationMillis?.let(::formatDuration) ?: "未统计"} · 同一条记录里的多个观点共用时长，不能相加")
        }
        if (item.evidenceDetails.isNotEmpty()) {
            Text(if (fromAudio) "依据片段（来自音频转写）" else "依据片段（来自同一页面）", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            item.evidenceDetails.forEach { fragment ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${fragment.type.label}${if (fragment.isNew) " · 新增" else ""}",
                        color = if (fragment.isNew) Accent else Muted, fontSize = 12.sp)
                    QuoteBlock(fragment.text)
                    if (fragment.identity.actors.isNotEmpty()) MetadataText("案例当事对象：${fragment.identity.actors.joinToString("、")}")
                    if (fragment.identity.origins.isNotEmpty()) MetadataText("原材料线索：${fragment.identity.origins.joinToString("、")}")
                    if (fragment.identityUnresolved) MetadataText("案例身份或材料关系不清楚，暂不确认新增或重复。")
                }
            }
        } else if (item.evidence.isNotEmpty()) {
            Text(if (fromAudio) "依据片段（来自音频转写）" else "依据片段（来自同一页面）", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            item.evidence.forEach { QuoteBlock(it) }
        } else MetadataText("没有识别到相关依据片段。")
        if (current != null) OriginalRecord(current, zone, appNames, "本条采集原文：")
        else {
            Text("本条采集原文：", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            MetadataText("这条记录已不在本机（可能已删除或超出 5000 条上限）")
        }
        item.previousEventId?.let { previous ->
            val previousEvent = events.firstOrNull { it.id == previous }
            Text("用于对比的早期记录 #$previous", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (previousEvent != null) OriginalRecord(previousEvent, zone, appNames, "早期采集原文：")
            else MetadataText("这条记录已不在本机，无法回看原文。")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun viewpointClaim(item: ClaimObservation) = ViewpointClaim(item.quote, item.topic, item.subject,
    item.dimension, item.stance, item.conditions, item.uncertainAttribution, item.analysisSource)

@Composable
private fun OriginalRecord(event: BrowseEvent, zone: String, appNames: Map<String, String>, title: String) {
    var expanded by remember(event.id, event.visibleText) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        MetadataText("事件 #${event.id} · ${reportTime(event.timestamp, zone)} · ${appNames[event.packageName] ?: event.packageName}")
        if (event.sourceType.startsWith("audio_")) MetadataText("音频转写 · 媒体偏移 ${audioTimestamp(event.mediaStartMillis)} — ${audioTimestamp(event.mediaEndMillis)}")
        SelectionContainer {
            Text(event.visibleText, color = Ink, fontSize = 14.sp, lineHeight = 24.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3)
        }
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (expanded) "收起原文" else "展开完整原文")
        }
    }
}

@Composable
private fun SheetHeading(title: String, subtitle: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, lineHeight = 31.sp)
            MetadataText(subtitle)
        }
        TextButton(onClick = onClose) { Text("关闭") }
    }
}

@Composable
private fun incrementColor(increment: Increment): Color = when (increment) {
    Increment.NEW -> Accent
    Increment.REPEATED -> Amber
    Increment.CONDITIONS -> Blue
    Increment.FIRST, Increment.UNKNOWN -> Muted
}

private fun hourDuration(review: HourlyReview?): String = when {
    review == null || review.recordCount == 0 -> "暂无记录"
    review.durationMillis == 0L && review.unknownDurationRecords == review.recordCount -> "未统计时长"
    else -> formatDuration(review.durationMillis)
}

private fun hourRange(hour: Int): String = "${hour.toString().padStart(2, '0')}:00—${(hour + 1).toString().padStart(2, '0')}:00"
private fun fraction(count: Int, total: Int): Float = if (total > 0) count.toFloat() / total else 0f
private fun reportTime(timestamp: Long, zone: String): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
