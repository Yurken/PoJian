package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
            compareBy<HourlyReview> { it.durationMillis }.thenBy { it.recordCount }
        )?.takeIf { it.recordCount > 0 }?.hour
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MetadataText("这段浏览的目的")
                    Text(state.purpose.ifBlank { "还未填写" }, color = Ink, fontSize = 14.sp,
                        maxLines = 2, lineHeight = 21.sp)
                }
                TextButton(onClick = { purposeDraft = state.purpose; editingPurpose = true },
                    modifier = Modifier.testTag("purpose-edit")) {
                    Text("填写目的")
                }
            }
            Button(
                onClick = { onAnalyze(state.purpose) },
                enabled = !state.running && !state.exporting && events.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (state.running) "正在整理…" else if (!state.active) "生成${period.label}的回顾" else "更新回顾")
            }
            state.status?.let { MetadataText(it) }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.stale && report != null) MetadataText("有新的记录，正在重新整理，以下是上一次完成的回顾。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }

        if (report == null) {
            WhiteCard {
                SectionTitle(if (events.isEmpty()) "从一段浏览开始" else "准备好了，回看一下这段时间", "原文 → 观点 → 依据")
                Text(
                    if (events.isEmpty()) "选择应用并开启采集后，这里会留下可以回查的文字记录。"
                    else if (state.running) "正在根据已保存的文字整理小时索引、主题和依据……"
                    else "点下面的按钮生成一次回顾，就能按小时回查原文，看看相似的内容有没有补充新的依据。",
                    color = Muted, fontSize = 14.sp, lineHeight = 23.sp
                )
                MetadataText("首次生成后，本次打开期间会随新记录和浏览目的自动更新。")
            }
        } else {
            WhiteCard {
                SectionTitle("24 小时信息回顾",
                    if (period == StatisticsPeriod.WEEK) "最近 7 天按小时合计" else "今天 00:00—24:00")
                MetadataText("柱子高度：预计停留时长 · ○ 该小时有未统计时长的记录")
                HourlyBars(report, selectedHour, onSelect = { selectedHour = it }, onOpen = {
                    selectedHour = it
                    detailHour = it
                })
                selectedHour?.let { value ->
                    val review = report.hourlyReviews.firstOrNull { it.hour == value }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(hourRange(value), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("${review?.recordCount ?: 0} 条可用记录 · ${hourDuration(review)}",
                                color = Muted, fontSize = 12.sp)
                            if (review != null && review.unknownDurationRecords > 0) {
                                MetadataText("另有 ${review.unknownDurationRecords} 条未统计时长")
                            }
                        }
                        TextButton(onClick = { detailHour = value }) { Text("回看原文 ›") }
                    }
                }
                ExpandableNote("这张图怎么看",
                    "柱子的高度只是估算的停留时长；圆圈表示该小时还有未统计时长的记录。点柱子或下面的小时按钮，可以直接回看那个时段的原文。停留时长是按时间段分摊估算的，同一条记录不会被重复计算。空白不代表没有浏览。")
            }

            if (report.topicExposure.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("接触内容", "${report.coverageSessions} 条有效记录 · 每条按主要内容归一个主题")
                    report.topicExposure.forEachIndexed { index, exposure ->
                        ProportionRow(
                            label = exposure.topic,
                            value = "${exposure.recordCount} 条 · ${ReportExporter.percent(exposure.recordCount, report.coverageSessions)}",
                            fraction = fraction(exposure.recordCount, report.coverageSessions),
                            color = ChartColors[index % ChartColors.size],
                            note = if (exposure.durationMillis > 0) "停留约 ${formatDuration(exposure.durationMillis)}（估算）" else "暂无停留数据"
                        )
                    }
                    ExpandableNote("比例怎么理解",
                        "分母是 ${report.coverageSessions} 条有效记录，包含未分类和娱乐内容。比例高低不代表阅读时长或价值；没统计到时长不代表没看。休闲浏览也可以是你的目的。")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionTitle("观点与依据", "识别出 ${report.claims.size} 个观点 · ${report.groups.size} 组相似说法")
                if (report.claims.isEmpty()) {
                    Text("这段时间还没有识别到明确的观点，你可以点上面的时间点回看原文。",
                        color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                    MetadataText("短评、娱乐内容和界面按钮文字不在分析范围内；没有识别到不代表没有浏览。")
                } else {
                    report.topics.forEachIndexed { index, topic ->
                        ProportionRow(topic.topic,
                            "${topic.count} 个 · ${ReportExporter.percent(topic.count, report.claims.size)}",
                            fraction(topic.count, report.claims.size), ChartColors[index % ChartColors.size])
                    }
                    MetadataText("比例分母为识别出的 ${report.claims.size} 个观点，一条记录里可能有多个观点。")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("和之前比，依据有没有变化", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Increment.entries.forEach { kind ->
                            val count = report.claims.count { it.increment == kind }
                            if (count > 0) ProportionRow(kind.label, "$count 个",
                                fraction(count, report.claims.size), incrementColor(kind))
                        }
                    }
                    ExpandableNote("依据变化怎么理解",
                        "和之前保留的同主题内容按浏览顺序比较。新增片段可能是事实、条件、解释或建议，不代表真的学到了新东西；没有依据或出处不明时，会如实标注为未知。")
                    var groupLimit by remember(report) { mutableIntStateOf(6) }
                    val shownGroups = report.groups.take(groupLimit)
                    shownGroups.forEachIndexed { index, group ->
                        GroupRow(index + 1, group) { selectedClaims = group.observations }
                    }
                    if (report.groups.size > groupLimit) TextButton(onClick = { groupLimit += 6 }) {
                        Text("展开更多（${minOf(6, report.groups.size - groupLimit)} 组）")
                    }
                    if (groupLimit > 6) TextButton(onClick = { groupLimit = 6 }) {
                        Text("收起")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionTitle("持续观察", "结合历史记录和时间，看看哪些判断在反复出现")
                report.findings.forEach { finding ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetadataText(finding.topic)
                        Text(finding.title, color = Ink, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                        val referenced = report.claims.filter {
                            it.eventId in finding.eventIds && (finding.topic == "全部" || it.topic == finding.topic)
                        }
                        if (referenced.isNotEmpty()) TextButton(onClick = { selectedClaims = referenced },
                            contentPadding = PaddingValues(horizontal = 0.dp)) { Text("查看这条观察的原文依据 ›") }
                        ExpandableNote("这条观察的依据和范围", finding.explanation)
                    }
                }
                ExpandableNote("结果能说明什么",
                    "主题集中、观点一致或负面情绪，都不会单独得出结论。内容是谁写的、立场如何、材料源头在哪，目前都无法验证。结果不会推断你的态度、情绪或认知状况，也不能证明你是否处于信息茧房。")
            }

            if (report.contentSignals.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var showSignals by remember(report) { mutableStateOf(false) }
                    TextButton(onClick = { showSignals = !showSignals },
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("${if (showSignals) "收起" else "展开"}文字表达线索 · ${report.contentSignals.size} 条")
                    }
                    if (showSignals) {
                        MetadataText("只描述原文的措辞，不能推断你的心理状态。")
                        report.contentSignals.take(8).forEach { signal ->
                            Text(signal.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            QuoteBlock(signal.quote)
                            events.firstOrNull { it.id == signal.eventId }?.let { event ->
                                OriginalRecord(event, report.zone, appNames, "对应原文：")
                            }
                        }
                        if (report.contentSignals.size > 8) MetadataText("这里显示前 8 条，完整列表见导出的报告。")
                    }
                }
            }

            WhiteCard {
                SectionTitle("保存这份回顾", "原文引用、依据变化和统计范围一并留存")
                Button(onClick = { onExport(report, true) },
                    enabled = !state.exporting && !state.running && !state.stale,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("导出 PDF") }
                OutlinedButton(onClick = { onExport(report, false) },
                    enabled = !state.exporting && !state.running && !state.stale,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("导出完整文本") }
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportMessage?.let { MetadataText(it) }
                ExpandableNote("导出内容与文件管理",
                    "报告是生成时的存档，里面包含浏览原文引用。PDF 附录最多 100 个观点，完整列表请导出文本。清空应用不会删除已导出的文件。")
            }
            ExpandableNote("这份回顾的数据范围与方法",
                "${report.period}，统计至 ${reportTime(report.end, report.zone)}。共 ${report.rawRecords} 条原始记录，保留 ${report.usableSnapshots} 条有效记录，合并 ${report.mergedSnapshots} 条重复内容，跳过 ${report.skippedRecords} 条无正文的记录，${report.unknownDurationRecords} 条未统计时长。历史对比使用之前的 ${report.comparisonRecords} 条记录。\n\n${report.method}。覆盖已授权应用提供的可见文字与已保存的音频转写；未授权的页面文字、没获取到的正文、未转写的音频和视频画面不在统计范围内。音频转写未统计观看时长，媒体长度不计入浏览停留。停留时长不等于阅读、理解或认同。")
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
                        shape = RoundedCornerShape(8.dp)) { Text("取消") }
                    Button(onClick = { onPurposeChange(purposeDraft); editingPurpose = false },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("保存目的") }
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
                                if (period == StatisticsPeriod.WEEK) "最近 7 天同一小时合计" else "今天该时段") {
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
private fun HourlyBars(report: CognitiveReport, selected: Int?, onSelect: (Int) -> Unit, onOpen: (Int) -> Unit) {
    val maximum = report.hourlyReviews.maxOfOrNull { it.durationMillis }?.coerceAtLeast(1) ?: 1L
    val ink = Accent
    val amber = Amber
    val muted = Muted
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)
            .pointerInput(report) {
                detectTapGestures { offset -> onSelect((offset.x / (size.width / 24f)).toInt().coerceIn(0, 23)) }
            }
            .semantics { contentDescription = "24 小时分布图。点击柱子或下方小时按钮，回看对应时段的原文。" }) {
            val unit = size.width / 24f
            val bottom = size.height - 12.dp.toPx()
            val availableHeight = bottom - 10.dp.toPx()
            drawLine(baseline, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
            repeat(24) { hour ->
                val review = report.hourlyReviews.firstOrNull { it.hour == hour }
                val duration = review?.durationMillis ?: 0L
                val width = unit * .52f
                val x = unit * (hour + .5f)
                if (duration > 0) {
                    val height = availableHeight * (duration.toFloat() / maximum)
                    drawRect(if (selected == hour) amber else ink,
                        topLeft = Offset(x - width / 2, bottom - height), size = Size(width, height))
                }
                if ((review?.unknownDurationRecords ?: 0) > 0) {
                    drawCircle(muted, radius = 2.2.dp.toPx(), center = Offset(x, bottom + 6.dp.toPx()),
                        style = Stroke(1.dp.toPx()))
                }
                if (selected == hour) drawLine(amber,
                    Offset(x - width / 2, 2.dp.toPx()), Offset(x + width / 2, 2.dp.toPx()), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach { MetadataText(it) }
        }
        val pageBackground = MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().testTag("hourly-hour-options").horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(24) { hour ->
                    val review = report.hourlyReviews.firstOrNull { it.hour == hour }
                    val duration = review?.durationMillis ?: 0L
                    val records = review?.recordCount ?: 0
                    val unknown = review?.unknownDurationRecords ?: 0
                    val description = when {
                        records == 0 -> "${hour}时，暂无记录，点击查看详情"
                        unknown == records && duration == 0L -> "${hour}时，${records}条记录，未统计时长，点击查看详情"
                        unknown > 0 -> "${hour}时，${records}条记录，预计停留${formatDuration(duration)}，${unknown}条未统计时长，点击查看详情"
                        else -> "${hour}时，${records}条记录，预计停留${formatDuration(duration)}，点击查看详情"
                    }
                    Column(Modifier.width(48.dp).heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected == hour) Accent else Color.Transparent)
                        .clickable(role = Role.Button) { onOpen(hour) }
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(hour.toString().padStart(2, '0'), color = if (selected == hour) MaterialTheme.colorScheme.onPrimary else Ink,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("$records 条", color = if (selected == hour) MaterialTheme.colorScheme.onPrimary else Muted,
                            fontSize = 10.sp)
                    }
                }
            }
            Box(Modifier.matchParentSize()) {
                Box(Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(pageBackground, Color.Transparent))))
                Box(Modifier.align(Alignment.CenterEnd).width(14.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, pageBackground))))
            }
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
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MetadataText("${index.toString().padStart(2, '0')} / ${group.topic} · ${group.observations.size} 条相似说法")
        Text(group.quote, color = Ink, fontSize = 16.sp, lineHeight = 27.sp,
            fontWeight = FontWeight.Medium, maxLines = 4)
        val increments = group.observations.groupingBy { it.increment }.eachCount()
        Text(increments.entries.joinToString(" · ") { "${it.key.label} ${it.value}" },
            color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        Text("原文与依据 ›", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            Text("讨论对象：${item.subject.ifBlank { "未识别，请查看原文" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
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
