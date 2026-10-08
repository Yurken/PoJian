package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.sp
import com.emerge.app.capture.CaptureStatus
import com.emerge.app.R
import com.emerge.app.data.BrowseEvent
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
    val packageCounts = remember(events) { events.groupingBy { it.packageName }.eachCount() }
    val packages = remember(packageCounts, appNames) {
        packageCounts.keys.sortedBy { appNames[it] ?: it }
    }
    val filtered = remember(events, query, selectedPackage, appNames) {
        val search = query.trim()
        events.asSequence().filter { event ->
            (selectedPackage == null || event.packageName == selectedPackage) &&
                (search.isEmpty() || event.visibleText.contains(search, ignoreCase = true) ||
                    event.packageName.contains(search, ignoreCase = true) ||
                    appNames[event.packageName].orEmpty().contains(search, ignoreCase = true))
        }.sortedByDescending { it.timestamp }.toList()
    }
    val zone = ZoneId.systemDefault()
    val grouped = remember(filtered, zone) {
        filtered.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy年M月d日 · EEEE", Locale.CHINA) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA) }
    val hasFilter = query.isNotBlank() || selectedPackage != null
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
        item {
            Text("信息足迹", style = MaterialTheme.typography.headlineMedium, color = Ink)
            MetadataText("本机已保存 $count 条 · 按浏览时间回看", Modifier.padding(top = 6.dp))
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                label = { Text("搜索原文或应用") }, singleLine = true, shape = RoundedCornerShape(10.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") }
                })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showAppFilter = true }, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("record-app-filter").heightIn(min = 48.dp).weight(1f, fill = false)) {
                    Text(selectedPackage?.let { appNames[it] ?: it } ?: "全部应用",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(" ▾")
                }
                MetadataText("${filtered.size} 条${if (hasFilter) "匹配" else "记录"}")
            }
        }
        audioContent?.let { content -> item(key = "audio-record-entry") { content() } }
        if (filtered.isEmpty()) item {
            WhiteCard {
                SectionTitle(if (hasFilter) "没有找到相关记录" else "还没有任何记录")
                Text(if (hasFilter) "换个关键词或应用名再试试。"
                    else if (audioContent != null) "可以导入音频生成转写，也可以选择要记录的应用，保存浏览时看到的文字。"
                    else "选择要记录的应用并完成授权后，这里会保存你浏览时看到的文字。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (hasFilter) TextButton(onClick = { query = ""; selectedPackage = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看全部记录")
                } else Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("去设置采集")
                }
            }
        }
        grouped.forEach { (date, records) ->
            item(key = "date-$date") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateFormat.format(date), color = Ink, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    MetadataText("${records.size} 条")
                }
            }
            items(records, key = { "event-${it.id}" }) { event ->
                var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
                val fromAudio = event.sourceType.startsWith("audio_")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeFormat.format(Instant.ofEpochMilli(event.timestamp).atZone(zone)), color = Muted,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 44.dp).padding(top = 3.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(appNames[event.packageName] ?: event.packageName, color = Ink,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            MetadataText(if (fromAudio) "音频转写"
                                else if (event.sampleCount > 0) "停留约 ${formatDuration(event.durationMillis)}"
                                else "旧版本", Modifier.padding(start = 8.dp))
                        }
                        if (fromAudio) MetadataText("媒体偏移 ${audioTimestamp(event.mediaStartMillis)} — ${audioTimestamp(event.mediaEndMillis)}",
                            Modifier.padding(top = 4.dp))
                        if (expanded) {
                            QuoteBlock(event.visibleText)
                            MetadataText("记录 #${event.id} · ${event.packageName}", Modifier.padding(top = 8.dp))
                            if (fromAudio) MetadataText("媒体偏移用于定位转写，不计入浏览停留时长。")
                            else if (event.sampleCount > 0) MetadataText("已检查 ${event.sampleCount} 次 · 停留时长不等于实际阅读时间")
                        } else Text(event.visibleText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = Ink, style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp,
                            modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp)) {
                                Text(if (expanded) "收起原文" else "查看原文 ›")
                            }
                            TextButton(onClick = { onDelete(event) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
    if (showAppFilter) RecordAppFilterSheet(packages, packageCounts, appNames, selectedPackage,
        onSelect = { selectedPackage = it; showAppFilter = false }, onDismiss = { showAppFilter = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordAppFilterSheet(packages: List<String>, counts: Map<String, Int>, appNames: Map<String, String>,
    selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp)) {
            LazyColumn(Modifier.testTag("record-app-options").weight(1f)) {
                item(key = "filter-heading") {
                    Column {
                        Text("按应用回看", style = MaterialTheme.typography.headlineSmall, color = Ink)
                        MetadataText("在全部保留记录中筛选；搜索关键词会继续生效。", Modifier.padding(top = 6.dp, bottom = 16.dp))
                    }
                }
                item {
                    ApplicationFilterRow("全部应用", "${counts.values.sum()} 条记录", selected == null) { onSelect(null) }
                }
                items(packages, key = { it }) { packageName ->
                    ApplicationFilterRow(appNames[packageName] ?: packageName, "${counts[packageName] ?: 0} 条 · $packageName",
                        selected == packageName) { onSelect(packageName) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("取消") }
        }
    }
}

@Composable
private fun ApplicationFilterRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
        .heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Ink)
            MetadataText(detail, Modifier.padding(top = 4.dp))
        }
        RadioButton(selected = selected, onClick = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SettingsScreen(permission: Boolean, enabled: Boolean, count: Int, onCapture: (Boolean) -> Unit,
    onOpenSettings: () -> Unit, onClear: () -> Unit, message: String?, modifier: Modifier = Modifier,
    diagnostics: CaptureStatus = CaptureStatus(), selectedApps: List<CaptureApp> = emptyList(), onChooseApps: () -> Unit = {},
    audioContent: (@Composable () -> Unit)? = null) {
    val captureState = when {
        selectedApps.isEmpty() -> "尚未选择要记录的应用"
        !enabled -> "采集已暂停"
        !permission -> "已开启 · 等待系统授权"
        !diagnostics.connected -> "已开启 · 等待服务连接"
        else -> "运行中 · 正在记录所选应用"
    }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Text("采集与数据", style = MaterialTheme.typography.headlineMedium, color = Ink)
            MetadataText("你决定哪些内容被记录，以及何时停止。", Modifier.padding(top = 6.dp))
        }
        audioContent?.let { content -> item(key = "audio-settings") { content() } }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("采集开关", color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium)
                            Text(captureState, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                        Switch(checked = enabled, onCheckedChange = onCapture, enabled = selectedApps.isNotEmpty(),
                            modifier = Modifier.testTag("capture-toggle").semantics { contentDescription = "开启采集"; stateDescription = captureState })
                    }
                    Text(if (selectedApps.isEmpty()) "请先选择至少一个应用，再授权并开启采集。"
                        else "只记录所选应用页面上可见的文字，可随时关闭。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    message?.let { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("采集范围", "默认不记录任何应用，由你主动选择。")
                Text(if (selectedApps.isEmpty()) "尚未选择应用" else "已选择 ${selectedApps.size} 个应用", color = Ink,
                    style = MaterialTheme.typography.titleMedium)
                if (selectedApps.isNotEmpty()) Text(selectedApps.joinToString("、") { it.label }, color = Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onChooseApps, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("选择应用（已选 ${selectedApps.size} 个）")
                }
                MetadataText("调整应用后采集会暂时停止，历史记录不受影响。")
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("系统授权")
                ControlStatusLine("辅助功能授权", if (permission) "已启用" else "未检测到")
                ControlStatusLine("辅助服务连接", if (diagnostics.connected) "已连接" else "未连接")
                OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (permission) "查看系统辅助功能授权" else "前往系统授权")
                }
                MetadataText("请在系统设置中开启「破茧浏览采集」，然后回到这里打开采集开关。")
                ExpandableNote("采集诊断", "本次运行已接收事件 ${diagnostics.eventsReceived} 次\n" +
                    "最近应用：${diagnostics.lastPackage}\n" +
                    "最近检查：${diagnosticTime(diagnostics.lastCheck)}\n" +
                    "最近保存：${diagnosticTime(diagnostics.lastSaved)}\n" +
                    "最近结果：${diagnostics.result}\n\n" +
                    "如果已授权但仍未连接，可以试试在系统中关闭再重新开启辅助服务。部分应用不会提供正文；" +
                    if (audioContent != null) "记录声音请从概览页的语音入口开始；视频画面暂不分析。" else "目前也无法识别视频画面和音频内容。")
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("本地存储")
                Text("$count 条已保存记录", color = Ink, style = MaterialTheme.typography.titleLarge)
                Text((if (audioContent != null) "数据只保存在这台设备上。只有主动配置并使用云端文字分析时，当前转写和少量相关历史文字才会发送到你指定的服务。"
                    else "数据只保存在这台设备上，不会上传。") +
                    "最多保留 5000 条，超出后自动删除最早的记录。目前数据库未做额外加密。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onClear, enabled = count > 0,
                    modifier = Modifier.heightIn(min = 48.dp).align(Alignment.Start)) {
                    Text("暂停并清空全部记录", color = MaterialTheme.colorScheme.error)
                }
                MetadataText("删除后分析结果会同步更新；已导出的文件需要自行删除。")
                ExpandableNote("浏览目的与报告的保存方式", if (audioContent != null)
                    "浏览目的会一直保留。概览页的回顾不会长期保存，重启应用后需要重新生成；音频记录里的回顾随转写保存在本机，修订文字后需要重新分析。导出的 PDF 或文本是当时内容的存档。"
                    else "浏览目的会一直保留。报告不会长期保存，重启应用后需要重新生成；导出的 PDF 或文本是当时内容的存档。")
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("隐私说明")
                Text("开启后，破茧会读取所选应用当前页面上的文字、应用名称和时间，帮你回顾自己的浏览内容。页面文字可能包含个人信息，请按需开启，不用时记得暂停。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                ExpandableNote("查看采集说明与边界", "采集会自动跳过密码框和输入框，也不会记录破茧本身、系统设置和输入法，但无法保证排除正文中的所有个人信息。\n\n" +
                    "停留时长不代表真实阅读、理解或认同。没有记录到的内容就是未知，内容中的情绪表达也不代表你的心理状态。")
                ExpandableNote("关于${stringResource(R.string.brand_name)}", stringResource(R.string.brand_tagline) +
                    if (audioContent != null) "\n\n帮你回顾接触到的文字与声音，比较观点和依据的变化，再自己决定要不要补充材料。语音转写在本机完成，文字分析可以用本地规则，也可以连接自行配置的云端服务；识别结果请回到原内容核实。"
                    else "\n\n帮你回顾接触到的信息，拓宽判断依据 · 离线文字分析原型。\n\n" +
                        "回顾接触到的观点，比较依据的变化，再自己决定要不要补充材料。目前仅支持本地文字分析，识别出的观点和依据请务必回到原文核实。")
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
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600 && LocalDensity.current.fontScale > 1.3f
    val filtered = remember(apps, query) {
        apps.filter { it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { if (!compactHeight) BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (compactHeight) 1f else 0.9f)
            .then(if (compactHeight) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 20.dp)) {
            LazyColumn(Modifier.testTag("capture-app-options").weight(1f)) {
                item(key = "capture-heading") {
                    Column {
                        Text("选择要采集的应用", color = Ink, style = MaterialTheme.typography.headlineSmall)
                        MetadataText("已选 ${draft.size} 个 · 保存后需重新开启采集。", Modifier.padding(top = 6.dp))
                        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth()
                            .padding(vertical = if (compactHeight) 4.dp else 12.dp),
                            singleLine = true, label = { Text("搜索应用") }, shape = RoundedCornerShape(10.dp),
                            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") } })
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (filtered.isEmpty() && !loading) item {
                    Text("没有找到应用。已安装的可启动应用、已选应用和有历史记录的应用会显示在这里。", color = Muted,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp))
                }
                items(filtered, key = { it.packageName }) { application ->
                    val checked = application.packageName in draft
                    val records = recordCounts[application.packageName] ?: 0
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { value ->
                            draft = if (value) draft + application.packageName else draft - application.packageName
                        }).heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(application.label, color = Ink, style = MaterialTheme.typography.titleSmall)
                                Text(application.packageName, color = Muted, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (records > 0) TextButton(onClick = { onDeletePackage(application) },
                            modifier = Modifier.padding(start = 36.dp).heightIn(min = 48.dp)) {
                            Text("删除此应用的 $records 条记录", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = if (compactHeight) 6.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("取消")
                }
                Button(onClick = { onApply(draft.toSet()) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
                    Text(if (compactHeight) "保存" else "保存范围")
                }
            }
        }
    }
}

private fun diagnosticTime(timestamp: Long): String = if (timestamp == 0L) "尚无"
    else SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
