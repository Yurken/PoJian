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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
    var showReport by rememberSaveable(period) { mutableStateOf(false) }
    val reportScroll = rememberScrollState()
    val reportState = rememberSaveableStateHolder()
    var selectedClaims by remember { mutableStateOf<List<ClaimObservation>?>(null) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var detailHour by remember { mutableStateOf<Int?>(null) }
    var editingPurpose by remember { mutableStateOf(false) }
    var purposeDraft by remember { mutableStateOf("") }
    val report = state.report?.takeIf { it.period == period.label }
    val reviewTitle = when (period) {
        StatisticsPeriod.WEEK -> "七日回顾"
        StatisticsPeriod.HOURS24 -> "24 小时回顾"
        StatisticsPeriod.DAY -> "今日回顾"
    }
    LaunchedEffect(report) {
        selectedClaims = null
        detailHour = null
        selectedHour = report?.hourlyReviews?.maxWithOrNull(
            compareBy<HourlyReview> { it.recordCount }.thenBy { it.durationMillis }
        )?.takeIf { it.recordCount > 0 }?.hour
    }

    WhiteCard {
        SectionTitle(reviewTitle, help = report?.let(::reportScope))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MetadataText("浏览目的")
                Text(state.purpose.ifBlank { "未填写" }, color = Ink, fontSize = 13.sp,
                    maxLines = 2, lineHeight = 20.sp)
            }
            IconButton(onClick = { purposeDraft = state.purpose; editingPurpose = true },
                modifier = Modifier.testTag("purpose-edit")) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑浏览目的")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PojianPrimaryButton(
                text = if (state.running) "正在整理…" else if (!state.active) "生成${period.label}回顾" else "更新回顾",
                onClick = { onAnalyze(state.purpose) },
                enabled = !state.running && !state.exporting && events.isNotEmpty(),
                modifier = Modifier.weight(1f)
            )
            if (report != null) TextButton(onClick = { showReport = true },
                modifier = Modifier.testTag("report-open")) { Text("查看回顾") }
        }
        state.status?.let { MetadataText(it) }
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.stale && report != null) MetadataText("正在更新，以下为上次结果")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (report != null) {
            if (report.pendingAnalysisRecords > 0 || report.failedAnalysisRecords > 0)
                MetadataText("待分析 ${report.pendingAnalysisRecords} 条 · 未完成 ${report.failedAnalysisRecords} 条")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onExport(report, true) },
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
        }
    }

    if (report != null && showReport && selectedClaims == null && detailHour == null) {
        ModalBottomSheet(onDismissRequest = { showReport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PageBackground) {
            reportState.SaveableStateProvider(report.generatedAt) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).verticalScroll(reportScroll)
                    .testTag("report-details").padding(horizontal = 16.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetHeading(reviewTitle, "") { showReport = false }
                    if (state.stale) MetadataText("正在更新，以下为上次结果")
                    WhiteCard {
                        HourlyRecordHeatmapContent(
                            counts = List(24) { hour -> report.hourlyReviews.firstOrNull { it.hour == hour }?.recordCount ?: 0 },
                            selectedHour = selectedHour,
                            onSelect = { selectedHour = it; detailHour = it },
                            modifier = Modifier.testTag("hourly-hour-options").semantics {
                                contentDescription = "小时记录热力图，点击小时查看详情"
                            },
                            showLegend = false
                        ) { hour, records ->
                            val review = report.hourlyReviews.firstOrNull { it.hour == hour }
                            val duration = review?.durationMillis ?: 0L
                            val unknown = review?.unknownDurationRecords ?: 0
                            when {
                                records == 0 -> "${hour}时，暂无记录"
                                unknown == records && duration == 0L -> "${hour}时，${records}条记录，时长未统计"
                                unknown > 0 -> "${hour}时，${records}条记录，预计停留${formatDuration(duration)}，${unknown}条时长未统计"
                                else -> "${hour}时，${records}条记录，预计停留${formatDuration(duration)}"
                            }
                        }
                        selectedHour?.let { value ->
                            val review = report.hourlyReviews.firstOrNull { it.hour == value }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(hourRange(value), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("${review?.recordCount ?: 0} 条有效记录 · ${hourDuration(review)}",
                                        color = Muted, fontSize = 11.sp)
                                    if (review != null && review.unknownDurationRecords > 0) {
                                        MetadataText("另有 ${review.unknownDurationRecords} 条时长未统计")
                                    }
                                }
                                PojianActionChip(text = "查看原文 ›", onClick = { detailHour = value })
                            }
                        }
                    }

                    WhiteCard {
                        SectionTitle("观点对比")
                        if (report.claims.isEmpty()) {
                            MetadataText("未识别到明确观点，可按小时查看原文。")
                        } else {
                            EvidenceRatioBar(report.claims)
                            var groupLimit by rememberSaveable(report.generatedAt) { mutableIntStateOf(3) }
                            report.groups.take(groupLimit).forEachIndexed { index, group ->
                                GroupRow(index + 1, group) { selectedClaims = group.observations }
                            }
                            if (report.groups.size > 3) {
                                TextButton(onClick = {
                                    groupLimit = if (groupLimit < report.groups.size) groupLimit + 3 else 3
                                }) {
                                    Text(if (groupLimit < report.groups.size)
                                        "再看 ${minOf(3, report.groups.size - groupLimit)} 组" else "收起")
                                }
                            }
                        }
                    }

                    if (report.topicExposure.isNotEmpty() || report.topics.isNotEmpty()) {
                        WhiteCard {
                            CollapsibleReportSection("内容分布") {
                                report.topicExposure.forEachIndexed { index, exposure ->
                                    ProportionRow(
                                        label = exposure.topic,
                                        value = "${exposure.recordCount} 条 · ${ReportExporter.percent(exposure.recordCount, report.coverageSessions)}",
                                        fraction = fraction(exposure.recordCount, report.coverageSessions),
                                        color = ChartColors[index % ChartColors.size],
                                        note = if (exposure.durationMillis > 0) "预计停留 ${formatDuration(exposure.durationMillis)}" else null
                                    )
                                }
                                if (report.topics.isNotEmpty()) {
                                    Text("观点主题", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    report.claims.groupBy { viewpointClaim(it).key }.entries.forEachIndexed { index, entry ->
                                        ProportionRow(viewpointClaim(entry.value.first()).label,
                                            "${entry.value.size} 个 · ${ReportExporter.percent(entry.value.size, report.claims.size)}",
                                            fraction(entry.value.size, report.claims.size), ChartColors[index % ChartColors.size])
                                    }
                                }
                            }
                        }
                    }

                    WhiteCard {
                        CollapsibleReportSection("长期观察") {
                            report.findings.forEach { finding ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MetadataText(finding.topic)
                                    Text(finding.title, color = Ink, fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                                    val referenced = report.claims.filter {
                                        it.eventId in finding.eventIds && (finding.topic == "全部" || it.topic == finding.topic)
                                    }
                                    if (referenced.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        PojianActionChip(text = "查看原文 ›", onClick = { selectedClaims = referenced })
                                    }
                                    MetadataText(finding.explanation)
                                }
                            }
                        }
                    }

                    if (report.contentSignals.isNotEmpty()) {
                        WhiteCard {
                            CollapsibleReportSection("表达特征") {
                                report.contentSignals.take(8).forEach { signal ->
                                    Text(signal.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    QuoteBlock(signal.quote)
                                    events.firstOrNull { it.id == signal.eventId }?.let { event ->
                                        OriginalRecord(event, report.zone, appNames, "对应原文：")
                                    }
                                }
                                if (report.contentSignals.size > 8) MetadataText("显示前 8 条，完整内容见导出报告。")
                            }
                        }
                    }
                }
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
                SectionTitle("浏览目的")
                OutlinedTextField(value = purposeDraft, onValueChange = { purposeDraft = it.take(120) },
                    label = { Text("浏览目的（选填）") },
                    placeholder = { Text("例如：比较求职资料、专题学习、休闲") },
                    supportingText = { Text("${purposeDraft.length}/120") },
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
                        SheetHeading("查看观点和依据", "${candidates.size} 个观点") {
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
                                if (period == StatisticsPeriod.WEEK) "最近 7 天同一小时合计" else if (period == StatisticsPeriod.HOURS24) "过去 24 小时该小时合计" else "今天该时段") {
                                detailHour = null
                            }
                            Text("${review?.recordCount ?: 0} 条有效记录 · ${hourDuration(review)}",
                                color = Ink, fontWeight = FontWeight.SemiBold)
                            MetadataText("${review?.unknownDurationRecords ?: 0} 条时长未统计 · ${candidates.size} 个观点")
                            review?.topics?.forEach {
                                ProportionRow(it.topic, "${it.count} 条", fraction(it.count, review.recordCount), Accent)
                            }
                            if (candidates.isEmpty()) Text("该时段暂无明确观点。",
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
                            SectionTitle("时段原文", "${hourRecords.size} 条原始记录")
                        }
                    }
                    itemsIndexed(hourRecords) { _, event ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val duration = window.slices(event).filter { it.hour == value }.sumOf { it.durationMillis }
                            MetadataText(if (event.sourceType.startsWith("audio_")) "音频转写 · 时长未统计"
                                else if (window.durationKnown(event)) "本时段预计停留 ${formatDuration(duration)}" else "时长未统计")
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
private fun CollapsibleReportSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.Button) { expanded = !expanded }
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
            .semantics(mergeDescendants = true) {
                contentDescription = "$total 个观点：重复依据 $repeated 个，新增依据 $added 个，首次出现 $first 个，条件不同 $conditions 个，无法判断 $unknown 个"
            }) {
            if (repeated > 0) Box(Modifier.weight(repeated.toFloat()).fillMaxHeight().background(Amber))
            if (added > 0) Box(Modifier.weight(added.toFloat()).fillMaxHeight().background(Accent))
            if (remainder > 0) Box(Modifier.weight(remainder.toFloat()).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("重复 $repeated", color = Amber, fontSize = 11.sp)
            Text("新增 $added", color = Accent, fontSize = 11.sp)
        }

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PojianActionChip(text = "查看原文 ›", onClick = onClick)
        }
    }
}

@Composable
private fun ClaimDetail(item: ClaimObservation, events: List<BrowseEvent>, zone: String,
    appNames: Map<String, String>, durationMillis: Long? = item.durationMillis,
    durationLabel: String = "本次预计停留") {
    val current = events.firstOrNull { it.id == item.eventId }
    val fromAudio = current?.sourceType?.startsWith("audio_") == true
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetadataText("记录 #${item.eventId} · ${reportTime(item.timestamp, zone)} · ${appNames[item.packageName] ?: item.packageName}")
        QuoteBlock(item.quote)
        Text(item.increment.label, color = incrementColor(item.increment), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetadataText(if (item.analysisSource == "structured_model") "云端分析" else "本地识别")
            Text("讨论对象：${item.subject.ifBlank { "未识别" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            if (item.analysisSource == "structured_model") {
                Text("判断维度：${item.dimension.ifBlank { "待确认" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
                Text("立场：${item.stance.ifBlank { "待确认" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            }
            Text("适用条件：${item.conditions.ifBlank { "未识别" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            MetadataText("出处：${if (item.uncertainAttribution) "可能引用他人" else "待核实"}")
            MetadataText(if (fromAudio) "音频转写"
                else "$durationLabel：${durationMillis?.let(::formatDuration) ?: "未统计"}")
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
                    if (fragment.identityUnresolved) MetadataText("材料关系待确认")
                }
            }
        } else if (item.evidence.isNotEmpty()) {
            Text(if (fromAudio) "依据片段（来自音频转写）" else "依据片段（来自同一页面）", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            item.evidence.forEach { QuoteBlock(it) }
        } else MetadataText("未识别到相关依据。")
        if (current != null) OriginalRecord(current, zone, appNames, "本条采集原文：")
        else {
            Text("本条采集原文：", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            MetadataText("记录已删除或超出保存上限")
        }
        item.previousEventId?.let { previous ->
            val previousEvent = events.firstOrNull { it.id == previous }
            Text("用于对比的早期记录 #$previous", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (previousEvent != null) OriginalRecord(previousEvent, zone, appNames, "早期采集原文：")
            else MetadataText("记录已不在本机，无法查看原文。")
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
        if (event.sourceType.startsWith("audio_")) MetadataText("音频转写 · 音频位置 ${audioTimestamp(event.mediaStartMillis)} — ${audioTimestamp(event.mediaEndMillis)}")
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
            if (subtitle.isNotBlank()) MetadataText(subtitle)
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
    review.durationMillis == 0L && review.unknownDurationRecords == review.recordCount -> "时长未统计"
    else -> formatDuration(review.durationMillis)
}

private fun hourRange(hour: Int): String = "${hour.toString().padStart(2, '0')}:00—${(hour + 1).toString().padStart(2, '0')}:00"
private fun fraction(count: Int, total: Int): Float = if (total > 0) count.toFloat() / total else 0f
private fun reportTime(timestamp: Long, zone: String): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

private fun reportScope(report: CognitiveReport): String =
    "${report.period}，统计至 ${reportTime(report.end, report.zone)}。共 ${report.rawRecords} 条原始记录，保留 ${report.usableSnapshots} 条有效记录，合并 ${report.mergedSnapshots} 条重复内容，跳过 ${report.skippedRecords} 条无正文的记录，${report.unknownDurationRecords} 条时长未统计。历史对比使用之前的 ${report.comparisonRecords} 条记录。\n\n" +
        "云端分析 ${report.structuredAnalyzedRecords} 条，本地识别 ${report.localFallbackRecords} 条，待分析 ${report.pendingAnalysisRecords} 条，未完成 ${report.failedAnalysisRecords} 条。云端仅分析采样内容。跨时段记录会重复出现，数量不能相加；时长按时段分摊。\n\n" +
        "蓝色为新增依据，橙色为重复依据；首次出现、条件不同或依据不足单独统计。一条记录可含多个观点。对比仅针对已保存片段，不验证真伪。内容分布统计范围：${report.coverageSessions} 条有效记录，每条按主要内容归一类。观点主题统计范围：${report.claims.size} 个观点。有效记录包含未分类和娱乐内容。没统计到时长不代表没看。\n\n" +
        "主题集中、观点一致或负面情绪，都不会单独得出结论。云端结果对观点结构作出估计，本地识别提供候选；两者都需要核查表达者和材料源头。\n\n" +
        "${report.method}。覆盖已授权应用提供的可见文字、已保存的音频转写及视觉提取文字；视觉文字可能有误。未授权的页面文字、未获取的正文、未转写的音频和未采样的画面不在统计范围内。音频转写与视觉文字不增加作品数或停留时长。停留时长为估算，不代表阅读或认同。\n\n" +
        "导出包含原文引用。PDF 附录最多 100 个观点，完整内容请导出文本。已导出文件需自行删除。"
