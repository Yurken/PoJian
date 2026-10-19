package com.emerge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class PreparedDialogReport(val report: CognitiveReport, val progress: ReportAnalysisProgress)

@Composable
internal fun SwissReportDialog(events: List<BrowseEvent>, semantics: List<EventSemantics>, now: Long, onDismiss: () -> Unit,
    purpose: String = "", onPurposeChange: (String) -> Unit = {}, analysisStatuses: Map<Long, String> = emptyMap(),
    appNames: Map<String, String> = emptyMap()) {
    var level by rememberSaveable { mutableIntStateOf(1) }
    // Returning from the document picker refreshes the app clock; the opened report keeps its window.
    val anchorNow = rememberSaveable { now }
    val periods = listOf(StatisticsPeriod.HOUR, StatisticsPeriod.DAY, StatisticsPeriod.MONTH)
    val request = SwissReportRequest(events, semantics, periods[level], anchorNow, purpose, analysisStatuses)
    val contentRequest = request.copy(analysisStatuses = emptyMap())
    // The dialog has three periods and one anchored snapshot. Reuse only results from that snapshot.
    val reports = remember(events, semantics, anchorNow, purpose) {
        mutableMapOf<StatisticsPeriod, PreparedDialogReport>()
    }
    var retry by remember { mutableIntStateOf(0) }
    var loadError by remember(contentRequest) { mutableStateOf(false) }
    val result by produceState<Pair<SwissReportRequest, PreparedDialogReport>?>(null, contentRequest, retry) {
        value = reports[contentRequest.period]?.let { contentRequest to it }
        loadError = false
        try {
            if (value == null) {
                val report = withContext(Dispatchers.Default) {
                    val task = currentCoroutineContext()
                    val content = buildSwissReport(contentRequest) { task.ensureActive() }
                    PreparedDialogReport(content, ReportAnalysisProgress(contentRequest.events, content))
                }
                reports[contentRequest.period] = report
                value = contentRequest to report
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { loadError = true }
    }
    val prepared = result?.takeIf { it.first == contentRequest }?.second
    val report = remember(prepared, analysisStatuses) {
        prepared?.let { it.progress.applyTo(it.report, analysisStatuses) }
    }
    var selection by remember(request) { mutableStateOf<SwissArgumentSelection?>(null) }
    val context = LocalContext.current
    val exportModel: SwissReportExportViewModel = viewModel()
    val exportState = exportModel.session
    SideEffect { exportModel.updateRequest(request) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        exportModel.updateRequest(request)
        exportModel.export(uri, context.applicationContext.contentResolver, context.applicationContext.packageManager)
    }
    fun export(pdf: Boolean) {
        val snapshot = report ?: return
        exportModel.updateRequest(request)
        if (!exportState.prepare(request, snapshot, pdf)) return
        val date = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(snapshot.generatedAt))
        save.launch("破茧-${listOf("小时报告", "每日报告", "月度报告")[level]}-$date.${if (pdf) "pdf" else "json"}")
    }
    val dismiss = { exportModel.close(); onDismiss() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 720.dp).fillMaxHeight(.88f).testTag("swiss-report-dialog"),
            shape = DesignTokens.CardShape, tonalElevation = 0.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("浏览报告", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    PojianIconButton(dismiss) { Icon(Icons.Outlined.Close, "关闭") }
                }
                PojianTextField(purpose, { onPurposeChange(it.take(120)) }, label = { Text("浏览目的") },
                    modifier = Modifier.fillMaxWidth().testTag("purpose-input"), maxLines = 3)
                PojianSegmentedTabs(listOf("小时报告", "每日报告", "月度报告"), level, { level = it }, modifier = Modifier.testTag("report-levels"))
                LazyColumn(Modifier.weight(1f).padding(vertical = 12.dp).testTag("swiss-report-content"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when {
                        loadError -> item { Text("报告生成失败"); PojianTextButton({ retry++ }) { Text("重试") } }
                        report == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        else -> {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SwissCount("已记录内容", report.rawRecords.toString(), Modifier.weight(1f))
                                    SwissCount("核心观点", report.groups.size.toString(), Modifier.weight(1f))
                                    SwissCount("浏览时长", swissReportDuration(report), Modifier.weight(1f))
                                }
                                if (report.unknownDurationRecords > 0) Text("${report.unknownDurationRecords} 条时长未知",
                                    style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                            item { Text(report.period, style = MaterialTheme.typography.labelMedium, color = Muted) }
                            swissReportSections(report, events) { selection = it }
                            if (report.rawRecords == 0) item { Text("暂无记录", color = Muted) }
                        }
                    }
                }
                exportState.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PojianButton({ export(true) }, Modifier.weight(1f), enabled = report != null && !exportState.exporting && exportState.pending == null) { Text("保存 PDF") }
                    PojianSecondaryButton({ export(false) }, Modifier.weight(1f), enabled = report != null && !exportState.exporting && exportState.pending == null) { Text("导出 JSON") }
                }
            }
        }
    }
    if (report != null) selection?.let { selected ->
        SwissArgumentDetailSheet(selected, events, semantics, report, appNames, onDismiss = { selection = null })
    }
}
