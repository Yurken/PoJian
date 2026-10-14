package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.capture.VisibilityRegion
import com.emerge.app.analysis.ObservationWindow
import com.emerge.app.R
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.visualSourceLabel
import com.emerge.app.data.contentText
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun RecordsScreen(events: List<BrowseEvent>, count: Int, appNames: Map<String, String>, modifier: Modifier = Modifier,
    onDelete: (BrowseEvent) -> Unit = {}, onSettings: () -> Unit = {},
    audioContent: (@Composable () -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showAppFilter by rememberSaveable { mutableStateOf(false) }
    var expandedPackages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val stackedRecordHeading = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.2f
    val sequence = remember(events) { events.sortedWith(compareBy<BrowseEvent> { it.timestamp }.thenBy { it.id })
        .mapIndexed { index, event -> event.id to index + 1 }.toMap() }
    val packageCounts = remember(events) { events.groupingBy { it.packageName }.eachCount() }
    val packages = remember(packageCounts, appNames) {
        packageCounts.keys.sortedBy { appNames[it] ?: it }
    }
    val filtered = remember(events, query, selectedPackage, appNames) {
        val search = query.trim()
        events.asSequence().filter { event ->
            (selectedPackage == null || event.packageName == selectedPackage) &&
                (search.isEmpty() || event.contentText.contains(search, ignoreCase = true) ||
                    event.packageName.contains(search, ignoreCase = true) ||
                    appNames[event.packageName].orEmpty().contains(search, ignoreCase = true))
        }.sortedByDescending { it.timestamp }.toList()
    }
    val zone = ZoneId.systemDefault()
    val grouped = remember(filtered, zone) {
        filtered.groupBy { it.packageName }
    }
    val timeFormat = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", Locale.CHINA) }
    val hasFilter = query.isNotBlank() || selectedPackage != null
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item {
            SectionTitle("浏览记录", "本机保存 $count 条")
            PojianTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text("搜索原文或应用") }, singleLine = true, shape = MaterialTheme.shapes.small,
                trailingIcon = {
                    if (query.isNotEmpty()) PojianTextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") }
                })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PojianSecondaryButton(onClick = { showAppFilter = true }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag("record-app-filter").heightIn(min = 48.dp).weight(1f, fill = false)) {
                    SourceAppText(selectedPackage, selectedPackage?.let { appNames[it] ?: it } ?: "全部应用",
                        style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" ▾")
                }
                MetadataText("${filtered.size} 条${if (hasFilter) "匹配" else "记录"}")
            }
        }
        audioContent?.let { content -> item(key = "audio-record-entry") { content() } }
        if (filtered.isEmpty()) item {
            WhiteCard {
                SectionTitle(if (hasFilter) "无匹配记录" else "暂无记录")
                if (hasFilter) PojianTextButton(onClick = { query = ""; selectedPackage = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看全部记录")
                } else PojianButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("设置采集范围")
                }
            }
        }
        grouped.forEach { (packageName, records) ->
            item(key = "app-$packageName") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceAppText(packageName, appNames[packageName] ?: packageName, color = Ink, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    MetadataText("${records.size} 条")
                    PojianTextButton(onClick = {
                        expandedPackages = if (packageName in expandedPackages) expandedPackages - packageName else expandedPackages + packageName
                    }, modifier = Modifier.testTag("record-group-$packageName")) {
                        Text(if (packageName in expandedPackages) "收起 −" else "展开 ＋")
                    }
                }
            }
            if (packageName in expandedPackages || hasFilter) items(records, key = { "event-${it.id}" }) { event ->
                var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
                val fromAudio = event.sourceType.startsWith("audio_")
                val durationKnown = ObservationWindow(0, Long.MAX_VALUE, zone).durationKnown(event)
                WhiteCard(Modifier.padding(bottom = 12.dp)) {
                    val recordedAt = timeFormat.format(Instant.ofEpochMilli(event.timestamp).atZone(zone))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SourceAppText(event.packageName.takeUnless { event.sourceType == "audio_import" },
                                "#${sequence[event.id]} · ${appNames[event.packageName] ?: event.packageName}", color = Ink,
                                appName = appNames[event.packageName] ?: event.packageName,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                                maxLines = if (stackedRecordHeading) 2 else 1, overflow = TextOverflow.Ellipsis)
                            if (!stackedRecordHeading) Text(recordedAt, color = Muted, style = MaterialTheme.typography.labelMedium)
                        }
                        if (stackedRecordHeading) Text(recordedAt, color = Muted, style = MaterialTheme.typography.labelMedium)
                    }
                    event.visualSourceLabel?.let { MetadataText(it) }
                    if (fromAudio) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MetadataText("音频转写")
                            MetadataText("音频位置 ${audioTimestamp(event.mediaStartMillis)} — ${audioTimestamp(event.mediaEndMillis)}")
                        }
                    } else MetadataText(if (durationKnown) "停留时长 ${formatDuration(event.durationMillis)} · ${if (event.identityMethod == "author_title") "作者及标题匹配" else if (event.identityMethod == "platform_id") "平台标识" else "作品未识别"}"
                        else "时长未统计")
                    if (expanded) {
                        QuoteBlock(rememberOriginalText(event), fromAudio = fromAudio)
                        MetadataText("记录 #${event.id} · ${event.packageName}")
                        if (!fromAudio) {
                            CaptureVisibilityBreakdown(event, ObservationWindow(0, Long.MAX_VALUE, zone))
                        }
                    } else AudioSourceContent(fromAudio) {
                        Text(rememberOriginalText(event), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = Ink, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PojianActionChip(
                            text = if (expanded) "收起原文" else "查看原文 ›",
                            onClick = { expanded = !expanded }
                        )
                        PojianTextButton(onClick = { onDelete(event) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (showAppFilter) RecordAppFilterSheet(packages, packageCounts, appNames, selectedPackage,
        onSelect = { selectedPackage = it; showAppFilter = false }, onDismiss = { showAppFilter = false })
}

@Composable
internal fun CaptureVisibilityBreakdown(event: BrowseEvent, window: ObservationWindow, hour: Int? = null) {
    if (event.visibilityIntervals == null) {
        if (event.sampleCount > 0) {
            HelpLink("时长统计口径", "仅统计已记录的可见区间，暂停和缺失窗口不计时。未定位时段单独统计，评论区时长未分配到单条评论。旧记录仅有整次访问的时长估计。")
        }
        return
    }
    if (!window.durationKnown(event)) {
        MetadataText("停留时间待确认")
        HelpLink("时长统计口径", "仅统计已记录的可见区间，暂停和缺失窗口不计时。未定位时段单独统计，评论区时长未分配到单条评论。旧记录仅有整次访问的时长估计。")
        return
    }
    val slices = window.slices(event).filter { hour == null || it.hour == hour }
    Column(Modifier.testTag("capture-region-times-${event.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VisibilityRegion.entries.forEach { region ->
            val duration = slices.filter { it.region == region }.sumOf { it.durationMillis }
            MetadataText("${region.label}：${formatDuration(duration)}")
        }
        HelpLink("时长统计口径", "仅统计已记录的可见区间，暂停和缺失窗口不计时。未定位时段单独统计，评论区时长未分配到单条评论。旧记录仅有整次访问的时长估计。")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordAppFilterSheet(packages: List<String>, counts: Map<String, Int>, appNames: Map<String, String>,
    selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.testTag("record-app-options").weight(1f)) {
                item(key = "filter-heading") {
                    Column {
                        SectionTitle("按应用筛选")
                    }
                }
                item {
                    ApplicationFilterRow("全部应用", "${counts.values.sum()} 条记录", selected == null) { onSelect(null) }
                }
                items(packages, key = { it }) { packageName ->
                    ApplicationFilterRow(appNames[packageName] ?: packageName, "${counts[packageName] ?: 0} 条 · $packageName",
                        selected == packageName, packageName) { onSelect(packageName) }
                }
            }
            PojianTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("取消") }
        }
    }
}

@Composable
private fun ApplicationFilterRow(label: String, detail: String, selected: Boolean, packageName: String? = null,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
        .heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            SourceAppText(packageName, label, style = MaterialTheme.typography.titleSmall, color = Ink)
            MetadataText(detail, Modifier.padding(top = 4.dp))
        }
        PojianRadioButton(selected = selected, onClick = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(permission: Boolean, enabled: Boolean, count: Int, onCapture: (Boolean) -> Unit,
    onOpenSettings: () -> Unit, onClear: () -> Unit, message: String?, modifier: Modifier = Modifier,
    diagnostics: CaptureStatus = CaptureStatus(), selectedApps: List<CaptureApp> = emptyList(), onChooseApps: () -> Unit = {},
    audioContent: (@Composable () -> Unit)? = null, updateContent: (@Composable () -> Unit)? = null,
    transcriptContent: (@Composable () -> Unit)? = null,
    storageUsage: com.emerge.app.data.LocalStorageUsage = com.emerge.app.data.LocalStorageUsage(),
    onSaveStorageLimit: suspend (Long) -> Unit = {}) {
    val captureState = when {
        selectedApps.isEmpty() -> "尚未选择要记录的应用"
        !enabled -> "采集已暂停"
        !permission -> "已开启 · 等待系统授权"
        !diagnostics.connected -> "已开启 · 等待服务连接"
        else -> "运行中 · 正在记录所选应用"
    }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle("采集与数据")
        }
        item {
            WhiteCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (selectedApps.isNotEmpty() && enabled && permission && diagnostics.connected) {
                                        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF6EE7B7) else Color(0xFF15803D)
                                    } else Muted)
                            )
                            Text("采集开关", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(captureState, color = Muted,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    PojianSwitch(checked = enabled, onCheckedChange = onCapture, enabled = selectedApps.isNotEmpty(),
                        modifier = Modifier.testTag("capture-toggle").semantics { contentDescription = "开启采集"; stateDescription = captureState })
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        item {
            WhiteCard {
                SectionTitle("采集范围", help = "仅记录所选应用的可见文字。调整范围后需重新开启采集，历史记录保留。")
                MetadataText(if (selectedApps.isEmpty()) "尚未选择应用" else "已选择 ${selectedApps.size} 个应用")
                if (selectedApps.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    selectedApps.forEach { application ->
                        SourceAppText(application.packageName, application.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                PojianSecondaryButton(onClick = onChooseApps, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("选择应用")
                }
            }
        }
        item(key = "system-authorization") {
            if (permission && diagnostics.connected) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClickLabel = "查看系统辅助功能授权", onClick = onOpenSettings)
                    .testTag("system-authorization-summary")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("系统授权", Modifier.weight(1f), color = Muted,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("已启用", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                        tint = Muted, modifier = Modifier.size(18.dp))
                }
            } else WhiteCard {
                SectionTitle("系统授权", help = "在系统设置中开启「破茧浏览采集」，然后打开采集开关。\n\n" +
                    "本次运行已接收事件 ${diagnostics.eventsReceived} 次\n" +
                    "最近应用：${diagnostics.lastPackage}\n" +
                    "最近检查：${diagnosticTime(diagnostics.lastCheck)}\n" +
                    "最近保存：${diagnosticTime(diagnostics.lastSaved)}\n" +
                    "最近结果：${diagnostics.result}\n\n" +
                    "已授权但未连接时，可关闭再开启系统辅助服务。部分应用不提供正文；" +
                    (if (audioContent != null) "录音请从录制页开始，画面采样需另行开启。" else "目前也无法识别视频画面和音频内容。"))
                ControlStatusLine("辅助功能授权", if (permission) "已启用" else "未检测到")
                ControlStatusLine("辅助服务连接", if (diagnostics.connected) "已连接" else "未连接")
                PojianSecondaryButton(onClick = onOpenSettings, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (permission) "查看系统辅助功能授权" else "前往系统授权")
                }
            }
        }
        transcriptContent?.let { content -> item(key = "audio-transcript-records") { content() } }
        item(key = "local-thumbnails") { LocalThumbnailControl() }
        audioContent?.let { content -> item(key = "audio-settings") { content() } }
        item(key = "local-storage") {
            LocalStorageSettingsCard(storageUsage, canClear = count > 0, onClear, onSaveStorageLimit)
        }
        item(key = "synthetic-demo") { SyntheticDemoEntry() }
        updateContent?.let { content -> item(key = "update-settings") { content() } }
        item {
            WhiteCard {
                SectionTitle("关于")
                HelpLink("采集与隐私", "开启后，破茧会读取所选应用当前页面上的文字、应用名称和时间，用于回顾浏览内容。页面文字可能包含个人信息，请按需开启。\n\n" +
                    "自动跳过密码框、输入框、系统设置与输入法。正文仍可能包含个人信息，可随时暂停或删除。\n\n" +
                    "开启云端分析后发送文字与相关历史材料；开启画面分析后发送采样画面。音频只发送转写。\n\n" +
                    "ML Kit 端侧识别会向 Google 发送运行指标，不含画面和识别文字。")
                HelpLink("关于${stringResource(R.string.brand_name)}", stringResource(R.string.brand_tagline) +
                    if (audioContent != null) "\n\n帮你回顾接触到的文字与声音，比较观点与依据的变化，再自主决定是否补充材料。"
                    else "\n\n帮你回顾接触到的信息，比较观点与依据的变化，再自主决定是否补充材料。")
            }
        }
    }
}

@Composable
private fun ControlStatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Ink, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(apps: List<CaptureApp>, selected: Set<String>, loading: Boolean,
    recordCounts: Map<String, Int>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit,
    onDeletePackage: (CaptureApp) -> Unit) {
    var draft by remember(selected) { mutableStateOf(selected.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600 && LocalDensity.current.fontScale > 1.3f
    val stackedHeading = LocalConfiguration.current.screenWidthDp < 360 && LocalDensity.current.fontScale > 1.3f
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLazyListState()
    // Keep row positions stable during selection; prioritize the saved scope when the picker opens.
    val ordered = remember(apps, selected, recordCounts) {
        val collator = java.text.Collator.getInstance(Locale.CHINA)
        apps.sortedWith(compareByDescending<CaptureApp> { it.packageName in selected }
            .thenByDescending { (recordCounts[it.packageName] ?: 0) > 0 }
            .thenComparator { a, b -> collator.compare(a.label, b.label) }
            .thenBy { it.packageName })
    }
    val filtered = remember(ordered, query, selectedOnly, draft) {
        ordered.filter { (!selectedOnly || it.packageName in draft) &&
            (it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true)) }
    }
    LaunchedEffect(query, selectedOnly) { listState.scrollToItem(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { if (!compactHeight) BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (compactHeight) 1f else 0.9f)
            .then(if (compactHeight) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 16.dp)) {
            if (!keyboardVisible) {
                val title: @Composable () -> Unit = {
                    Text("选择要采集的应用", color = Ink,
                        style = if (compactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                }
                val selectionCount: @Composable () -> Unit = { MetadataText("已选 ${draft.size} 个") }
                if (stackedHeading) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { title(); selectionCount() }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { title() }
                        Box(Modifier.padding(start = 8.dp)) { selectionCount() }
                    }
                }
            }
            PojianTextField(query, { query = it }, modifier = Modifier.fillMaxWidth()
                .padding(vertical = if (compactHeight) 4.dp else 8.dp),
                singleLine = true, label = { Text("搜索应用") }, shape = MaterialTheme.shapes.small,
                trailingIcon = { if (query.isNotEmpty()) PojianTextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") } })
            if (!keyboardVisible) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PojianFilterChip(selected = !selectedOnly, onClick = { selectedOnly = false }, label = { Text("全部应用") })
                PojianFilterChip(selected = selectedOnly, onClick = { selectedOnly = true }, label = { Text("已选") })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.testTag("capture-app-options").weight(1f), state = listState) {
                if (filtered.isEmpty() && !loading) item {
                    Text("未找到应用", color = Muted,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp))
                }
                items(filtered, key = { it.packageName }) { application ->
                    val checked = application.packageName in draft
                    val records = recordCounts[application.packageName] ?: 0
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { value ->
                            draft = if (value) draft + application.packageName else draft - application.packageName
                        }).heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SourceAppText(application.packageName, application.label, color = Ink,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                style = MaterialTheme.typography.titleSmall)
                            PojianCheckbox(checked = checked, onCheckedChange = null)
                        }
                        if (records > 0) PojianTextButton(onClick = { onDeletePackage(application) },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("删除此应用的 $records 条记录", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = if (compactHeight) 6.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PojianSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text("取消")
                }
                PojianButton(onClick = { onApply(draft.toSet()) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text(if (compactHeight) "保存" else "保存范围")
                }
            }
        }
    }
}

private fun diagnosticTime(timestamp: Long): String = if (timestamp == 0L) "尚无"
    else SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
