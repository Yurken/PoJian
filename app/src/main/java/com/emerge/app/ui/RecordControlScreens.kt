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
    onDelete: (BrowseEvent) -> Unit = {}, onSettings: () -> Unit = {}) {
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
            MetadataText("本地保存 $count 条 · 按浏览发生时间回看", Modifier.padding(top = 6.dp))
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
        if (filtered.isEmpty()) item {
            WhiteCard {
                SectionTitle(if (hasFilter) "没有匹配的记录" else "等待第一条信息足迹")
                Text(if (hasFilter) "换一个关键词或应用，继续查找本地原文。"
                    else "选择要记录的应用，授权并开启采集后，这里会留下实际保存的可见文字。",
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(timeFormat.format(Instant.ofEpochMilli(event.timestamp).atZone(zone)), color = Muted,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 44.dp).padding(top = 3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appNames[event.packageName] ?: event.packageName, color = Ink,
                            style = MaterialTheme.typography.titleSmall)
                        MetadataText(if (event.sampleCount > 0) "可见停留约 ${formatDuration(event.durationMillis)}"
                            else "旧版快照 · 停留时长未知", Modifier.padding(top = 4.dp, bottom = 10.dp))
                        if (expanded) {
                            QuoteBlock(event.visibleText)
                            MetadataText("事件 #${event.id} · ${event.packageName}", Modifier.padding(top = 8.dp))
                            if (event.sampleCount > 0) MetadataText("${event.sampleCount} 次有效检查 · 可见停留不等于真实阅读")
                        } else Text(event.visibleText, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = Ink, style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
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
    diagnostics: CaptureStatus = CaptureStatus(), selectedApps: List<CaptureApp> = emptyList(), onChooseApps: () -> Unit = {}) {
    val captureState = when {
        selectedApps.isEmpty() -> "尚未选择采集范围"
        !enabled -> "已暂停采集"
        !permission -> "已开启 · 等待系统授权"
        !diagnostics.connected -> "已开启 · 等待服务连接"
        else -> "已开启 · 正在观察所选应用"
    }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Text("采集与数据", style = MaterialTheme.typography.headlineMedium, color = Ink)
            MetadataText("你决定哪些内容被记录，以及何时停止。", Modifier.padding(top = 6.dp))
        }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("采集总控", color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium)
                            Text(captureState, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                        Switch(checked = enabled, onCheckedChange = onCapture, enabled = selectedApps.isNotEmpty(),
                            modifier = Modifier.testTag("capture-toggle").semantics { contentDescription = "允许采集"; stateDescription = captureState })
                    }
                    Text(if (selectedApps.isEmpty()) "先选择至少一个应用，再授权并开启采集。"
                        else "仅观察选中应用的可见文字，可随时关闭。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    message?.let { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("采集范围", "默认不采集任何应用，由你主动选择。")
                Text(if (selectedApps.isEmpty()) "尚未选择应用" else "已选择 ${selectedApps.size} 个应用", color = Ink,
                    style = MaterialTheme.typography.titleMedium)
                if (selectedApps.isNotEmpty()) Text(selectedApps.joinToString("、") { it.label }, color = Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onChooseApps, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("选择应用与管理记录（${selectedApps.size}）")
                }
                MetadataText("保存新范围会暂停采集，历史记录仍可回看。")
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
                MetadataText("在系统中启用「破茧浏览采集」，返回后打开采集总控。")
                ExpandableNote("采集诊断", "本次进程收到事件 ${diagnostics.eventsReceived} 次\n" +
                    "最近应用：${diagnostics.lastPackage}\n" +
                    "最近检查：${diagnosticTime(diagnostics.lastCheck)}\n" +
                    "最近保存：${diagnosticTime(diagnostics.lastSaved)}\n" +
                    "最近结果：${diagnostics.result}\n\n" +
                    "若已授权但未连接，可在系统中关闭再开启辅助服务。部分应用不会暴露正文；当前不能理解视频画面和音频。")
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("本地存储")
                Text("$count 条已保存记录", color = Ink, style = MaterialTheme.typography.titleLarge)
                Text("数据保存在此设备，不上传。最多保留 5000 条，超出后删除最早记录。数据库尚未额外加密。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onClear, enabled = count > 0, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("暂停并清空全部记录")
                }
                MetadataText("删除会更新本机分析；已导出的文件需要自行删除。")
                ExpandableNote("浏览目的与报告如何保存", "浏览目的会保留。首次主动分析后，记录变化会更新当前观察。报告暂不持久保存，重启应用后需要重新生成。导出的 PDF 或文本是生成时的快照。")
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("隐私与观察边界")
                Text("辅助功能读取所选应用当前窗口提供的可见文字、应用名称与时间，用于在本机回顾信息接触。可见正文仍可能包含个人信息，请按需开启，结束后暂停。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                ExpandableNote("查看采集说明与观察边界", "采集跳过密码和可编辑输入框，过滤本应用、系统设置及部分键盘，但无法保证排除正文中的所有个人信息。\n\n" +
                    "可见停留不代表真实阅读、理解或认同。未记录的内容保留为未知，内容中的焦虑表达也不代表你的心理状态。")
                ExpandableNote("关于${stringResource(R.string.brand_name)}", stringResource(R.string.brand_tagline) +
                    "\n\n个人信息环境自查 · 离线文字规则原型。\n\n" +
                    "回顾接触的观点、比较依据变化，再自主决定是否补充材料。当前尚未接入多模态、语义模型、时序图学习或可信执行环境。观点和依据候选需要回到原文核查。")
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
                        Text("选择采集应用", color = Ink, style = MaterialTheme.typography.headlineSmall)
                        MetadataText("已选 ${draft.size} 个 · 保存后会暂停采集，需重新开启。", Modifier.padding(top = 6.dp))
                        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth()
                            .padding(vertical = if (compactHeight) 4.dp else 12.dp),
                            singleLine = true, label = { Text("搜索应用") }, shape = RoundedCornerShape(10.dp),
                            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除") } })
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (filtered.isEmpty() && !loading) item {
                    Text("没有匹配的应用。可启动应用、已选应用及有历史记录的应用会出现在这里。", color = Muted,
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
