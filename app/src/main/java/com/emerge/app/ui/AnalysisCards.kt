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
                    MetadataText("这段时间的浏览目的")
                    Text(state.purpose.ifBlank { "还未填写" }, color = Ink, fontSize = 14.sp,
                        maxLines = 2, lineHeight = 21.sp)
                }
                TextButton(onClick = { purposeDraft = state.purpose; editingPurpose = true },
                    modifier = Modifier.testTag("purpose-edit")) {
                    Text("编辑目的")
                }
            }
            Button(
                onClick = { onAnalyze(state.purpose) },
                enabled = !state.running && !state.exporting && events.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (state.running) "正在整理…" else if (!state.active) "生成${period.label}的观察" else "更新观察")
            }
            state.status?.let { MetadataText(it) }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.stale && report != null) MetadataText("新记录正在整理，以下是上一次完成的观察。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }

        if (report == null) {
            WhiteCard {
                SectionTitle(if (events.isEmpty()) "从一段浏览开始" else "准备好回看这段时间", "原文 → 观点 → 依据")
                Text(
                    if (events.isEmpty()) "选择应用并开启采集后，这里会留下可以回查的文字记录。"
                    else if (state.running) "正在根据已保存的文字整理小时索引、主题与依据。"
                    else "生成一次观察，即可按小时回查原文，看看相似判断是否补充了依据。",
                    color = Muted, fontSize = 14.sp, lineHeight = 23.sp
                )
                MetadataText("首次生成后，本次打开期间会随新记录、浏览目的和区间自动更新。")
            }
        } else {
            WhiteCard {
                SectionTitle("24 小时信息回顾",
                    if (period == StatisticsPeriod.WEEK) "最近 7 天按钟点合计" else "今天 00:00—24:00")
                MetadataText("柱高：估计可见停留 · ○ 时长未知")
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
                                MetadataText("另有 ${review.unknownDurationRecords} 条时长未知")
                            }
                        }
                        TextButton(onClick = { detailHour = value }) { Text("核查原文 ›") }
                    }
                }
                ExpandableNote("时间带怎么看",
                    "柱高只表示已测得的估计可见停留；空心点表示该小时还有时长未知的记录。点击时间带选择小时，或滑动下方小时按钮直接核查。跨小时记录分摊停留时间，同屏多个观点不会重复计时。空白不代表没有浏览。")
            }

            if (report.topicExposure.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("接触内容", "${report.coverageSessions} 条可用记录 · 每条按主主题计一次")
                    report.topicExposure.forEachIndexed { index, exposure ->
                        ProportionRow(
                            label = exposure.topic,
                            value = "${exposure.recordCount} 条 · ${ReportExporter.percent(exposure.recordCount, report.coverageSessions)}",
                            fraction = fraction(exposure.recordCount, report.coverageSessions),
                            color = ChartColors[index % ChartColors.size],
                            note = if (exposure.durationMillis > 0) "已测得停留 ${formatDuration(exposure.durationMillis)}" else "暂无已测得停留"
                        )
                    }
                    ExpandableNote("接触比例的范围",
                        "分母是 ${report.coverageSessions} 条可用接触记录，包含未分类与娱乐内容。条目比例不等于阅读时长或阅读价值；时长未知的记录仍计入条目。休闲浏览也可以是你的目的。")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionTitle("观点与依据", "${report.claims.size} 个规则候选 · ${report.groups.size} 组近似措辞")
                if (report.claims.isEmpty()) {
                    Text("这段文字中暂未提取到明确观点。你仍然可以在小时索引里核查原文。",
                        color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                    MetadataText("短评、娱乐与界面标签可能无法分析；没有候选不代表没有浏览。")
                } else {
                    report.topics.forEachIndexed { index, topic ->
                        ProportionRow(topic.topic,
                            "${topic.count} 个 · ${ReportExporter.percent(topic.count, report.claims.size)}",
                            fraction(topic.count, report.claims.size), ChartColors[index % ChartColors.size])
                    }
                    MetadataText("比例分母为 ${report.claims.size} 个观点候选，同一条记录可包含多个候选。")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("后续依据的变化", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Increment.entries.forEach { kind ->
                            val count = report.claims.count { it.increment == kind }
                            if (count > 0) ProportionRow(kind.label, "$count 个",
                                fraction(count, report.claims.size), incrementColor(kind))
                        }
                    }
                    ExpandableNote("依据变化如何理解",
                        "相对于已保留的同主题历史进行规则比较。新增片段可能是事实、条件、解释或建议，不代表真实的新证据或知识增长；没有依据或归属不明时保留未知。")
                    var groupLimit by remember(report) { mutableIntStateOf(6) }
                    val shownGroups = report.groups.take(groupLimit)
                    shownGroups.forEachIndexed { index, group ->
                        GroupRow(index + 1, group) { selectedClaims = group.observations }
                    }
                    if (report.groups.size > groupLimit) TextButton(onClick = { groupLimit += 6 }) {
                        Text("再显示 ${minOf(6, report.groups.size - groupLimit)} 组候选")
                    }
                    if (groupLimit > 6) TextButton(onClick = { groupLimit = 6 }) {
                        Text("收起候选组")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionTitle("持续观察", "结合保留历史与发生时间，回查重复的判断")
                report.findings.forEach { finding ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetadataText(finding.topic)
                        Text(finding.title, color = Ink, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                        val referenced = report.claims.filter {
                            it.eventId in finding.eventIds && (finding.topic == "全部" || it.topic == finding.topic)
                        }
                        if (referenced.isNotEmpty()) TextButton(onClick = { selectedClaims = referenced },
                            contentPadding = PaddingValues(horizontal = 0.dp)) { Text("核查这条观察的引文 ›") }
                        ExpandableNote("观察依据与范围", finding.explanation)
                    }
                }
                ExpandableNote("观察能说明什么",
                    "主题集中、观点一致与负面表达不会单独决定结论。表达者、真实立场和材料源头尚未验证；结果不推断你的认同、情绪或认知受损，也不证明你是否陷入信息茧房。")
            }

            if (report.contentSignals.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var showSignals by remember(report) { mutableStateOf(false) }
                    TextButton(onClick = { showSignals = !showSignals },
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("${if (showSignals) "收起" else "展开"}文字表达线索 · ${report.contentSignals.size} 条")
                    }
                    if (showSignals) {
                        MetadataText("只描述原文措辞，不能推断你的心理状态。")
                        report.contentSignals.take(8).forEach { signal ->
                            Text(signal.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            QuoteBlock(signal.quote)
                            events.firstOrNull { it.id == signal.eventId }?.let { event ->
                                OriginalRecord(event, report.zone, appNames, "对应采集原文：")
                            }
                        }
                        if (report.contentSignals.size > 8) MetadataText("此处展示前 8 条，完整内容见报告。")
                    }
                }
            }

            WhiteCard {
                SectionTitle("保存这次观察", "原文引用、依据变化与观察范围一并留存")
                Button(onClick = { onExport(report, true) },
                    enabled = !state.exporting && !state.running && !state.stale,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("保存 PDF") }
                OutlinedButton(onClick = { onExport(report, false) },
                    enabled = !state.exporting && !state.running && !state.stale,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text("保存完整文本") }
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportMessage?.let { MetadataText(it) }
                ExpandableNote("导出内容与文件管理",
                    "报告是生成时的快照并包含浏览引文。PDF 附录最多 100 个候选，完整索引请保存文本。清空破茧不会删除已导出的文件。")
            }
            ExpandableNote("这份观察的数据范围与方法",
                "${report.period}，分析至 ${reportTime(report.end, report.zone)}。窗口内 ${report.rawRecords} 条原始记录，保留 ${report.usableSnapshots} 个可用快照，合并 ${report.mergedSnapshots} 条连续重复画面，跳过 ${report.skippedRecords} 条界面或无正文记录。${report.unknownDurationRecords} 条时长未知。历史比较使用窗口前保留的 ${report.comparisonRecords} 条记录。\n\n${report.method}。仅覆盖已授权应用提供的可见文字；全设备覆盖率、未授权应用、未暴露正文、视频和音频均未知。停留不等于阅读、理解或认同。")
        }
    }

    if (editingPurpose) {
        ModalBottomSheet(onDismissRequest = { editingPurpose = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PageBackground) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("这段时间，想看些什么", "学习、核查或休闲，都可以是浏览目的")
                OutlinedTextField(value = purposeDraft, onValueChange = { purposeDraft = it.take(120) },
                    label = { Text("我的浏览目的（选填）") },
                    placeholder = { Text("例如：比较求职资料、专题学习、休闲") },
                    supportingText = { Text("${purposeDraft.length}/120 · 用于解释观察，不改变观点规则") },
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
                MetadataText("点选用途会追加到草稿，保留你已填写的内容。")
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
                        SheetHeading("核查观点与依据", "${candidates.size} 个规则候选 · 以采集原文为准") {
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
                                if (period == StatisticsPeriod.WEEK) "最近 7 天同钟点合计" else "今天该时段") {
                                detailHour = null
                            }
                            Text("${review?.recordCount ?: 0} 条可用接触记录 · ${hourDuration(review)}",
                                color = Ink, fontWeight = FontWeight.SemiBold)
                            MetadataText("${review?.unknownDurationRecords ?: 0} 条时长未知 · ${candidates.size} 个观点候选")
                            review?.topics?.forEach {
                                ProportionRow(it.topic, "${it.count} 条", fraction(it.count, review.recordCount), Accent)
                            }
                            MetadataText("主题分母为 ${review?.recordCount ?: 0} 条可用接触记录。")
                            if (candidates.isEmpty()) Text("此时段没有识别出的观点候选。没有观点或记录不代表没有浏览。",
                                color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                        }
                    }
                    itemsIndexed(candidates) { _, item ->
                        val duration = events.firstOrNull { it.id == item.eventId }
                            ?.takeIf { window.durationKnown(it) }
                            ?.let { event -> window.slices(event).filter { it.hour == value }.sumOf { it.durationMillis } }
                        ClaimDetail(item, events, report.zone, appNames,
                            durationMillis = duration, durationLabel = "本时段估计可见停留")
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SectionTitle("回查这个时段的采集原文", "${hourRecords.size} 条原始记录，包含规则跳过的内容")
                            MetadataText("跨小时记录可在两个时段回查，停留按时段分摊。")
                        }
                    }
                    itemsIndexed(hourRecords) { _, event ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val duration = window.slices(event).filter { it.hour == value }.sumOf { it.durationMillis }
                            MetadataText(if (window.durationKnown(event)) "本时段估计可见停留 ${formatDuration(duration)}" else "时长未知")
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
            .semantics { contentDescription = "完整 24 小时时间带。通过下方小时按钮选择并核查原文。" }) {
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(24) { hour ->
                val review = report.hourlyReviews.firstOrNull { it.hour == hour }
                val duration = review?.durationMillis ?: 0L
                val records = review?.recordCount ?: 0
                val unknown = review?.unknownDurationRecords ?: 0
                val description = when {
                    records == 0 -> "${hour}时，暂无可用记录，点击查看详情"
                    unknown == records && duration == 0L -> "${hour}时，${records}条记录，时长未知，点击查看详情"
                    unknown > 0 -> "${hour}时，${records}条记录，已测得停留${formatDuration(duration)}，${unknown}条时长未知，点击查看详情"
                    else -> "${hour}时，${records}条记录，估计可见停留${formatDuration(duration)}，点击查看详情"
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
        MetadataText("${index.toString().padStart(2, '0')} / ${group.topic} · ${group.observations.size} 个近似措辞候选")
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
    durationLabel: String = "窗口内估计可见停留") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetadataText("事件 #${item.eventId} · ${reportTime(item.timestamp, zone)} · ${appNames[item.packageName] ?: item.packageName}")
        QuoteBlock(item.quote)
        Text(item.increment.label, color = incrementColor(item.increment), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("讨论对象：${item.subject.ifBlank { "未知，需核查" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            Text("适用条件：${item.conditions.ifBlank { "未提取到明确条件" }}", color = Ink, fontSize = 13.sp, lineHeight = 22.sp)
            MetadataText("表达归属：${if (item.uncertainAttribution) "含引用或转折线索，未知" else "待核查，不代表用户认同"}")
            MetadataText("$durationLabel：${durationMillis?.let(::formatDuration) ?: "时长未知"} · 同屏候选共享，不能累加")
        }
        if (item.evidenceDetails.isNotEmpty()) {
            Text("同屏依据候选", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            item.evidenceDetails.forEach { fragment ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${fragment.type.label}${if (fragment.isNew) " · 新增片段" else ""}",
                        color = if (fragment.isNew) Accent else Muted, fontSize = 12.sp)
                    QuoteBlock(fragment.text)
                }
            }
        } else if (item.evidence.isNotEmpty()) {
            Text("同屏依据候选", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            item.evidence.forEach { QuoteBlock(it) }
        } else MetadataText("没有提取到依据片段，信息增量未知。")
        val current = events.firstOrNull { it.id == item.eventId }
        if (current != null) OriginalRecord(current, zone, appNames, "当前采集原文：")
        else {
            Text("当前采集原文：", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            MetadataText("记录已不在本地保留范围")
        }
        item.previousEventId?.let { previous ->
            val previousEvent = events.firstOrNull { it.id == previous }
            Text("比较的先前事件 #$previous", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (previousEvent != null) OriginalRecord(previousEvent, zone, appNames, "先前采集原文：")
            else MetadataText("该事件已不在本地保留范围，无法回查原文；不能补造其依据。")
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
    review == null || review.recordCount == 0 -> "暂无可用记录"
    review.durationMillis == 0L && review.unknownDurationRecords == review.recordCount -> "时长未知"
    else -> formatDuration(review.durationMillis)
}

private fun hourRange(hour: Int): String = "${hour.toString().padStart(2, '0')}:00—${(hour + 1).toString().padStart(2, '0')}:00"
private fun fraction(count: Int, total: Int): Float = if (total > 0) count.toFloat() / total else 0f
private fun reportTime(timestamp: Long, zone: String): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
