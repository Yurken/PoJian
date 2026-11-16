package com.emerge.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emerge.app.data.BrowseEvent
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.audio.AudioRuntimeState
import com.emerge.app.monitoring.*
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SupplementSelection(val request: SupplementRequest, val report: CognitiveReport,
    val authorization: String)

class MainActivity : ComponentActivity() {
    private var discoveryRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("monitor_discover", false)) discoveryRequest++
        setContent { EmergeTheme { EmergeScreen(discoveryRequest) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }; com.emerge.app.ui.SecureChannelAlert() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("monitor_discover", false)) discoveryRequest++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmergeScreen(discoveryRequest: Int, onOpenSettings: () -> Unit) {
    com.emerge.app.update.AppUpdateHost()
    val app = LocalContext.current.applicationContext as EmergeApplication
    val model: BrowseViewModel = viewModel()
    val audioModel: AudioViewModel = viewModel()
    val monitor by app.monitor.state.collectAsStateWithLifecycle()
    val monitorConfig by app.monitor.config.collectAsStateWithLifecycle()
    val audioConfig by audioModel.config.collectAsStateWithLifecycle()
    val audioAlert by audioModel.alert.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    LaunchedEffect(audioAlert) { audioAlert?.let { snackbar.showSnackbar(it) } }
    val all by model.allEvents.collectAsStateWithLifecycle()
    val semantics by model.semantics.collectAsStateWithLifecycle()
    val analysisStatuses by model.analysisStatuses.collectAsStateWithLifecycle()
    val count by model.count.collectAsStateWithLifecycle()
    val storageUsage by app.localStorage.usage.collectAsStateWithLifecycle()
    val storageLimit by app.localStorageSettings.limitBytes.collectAsStateWithLifecycle()
    val storageRetention by app.localStorageSettings.retentionDays.collectAsStateWithLifecycle()
    val enabled by model.captureEnabled.collectAsStateWithLifecycle()
    val allowed by model.allowedPackages.collectAsStateWithLifecycle()
    val availableApps by model.availableApps.collectAsStateWithLifecycle()
    val appsLoading by model.appsLoading.collectAsStateWithLifecycle()
    val permission by model.serviceEnabled.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val analysis by model.analysis.collectAsStateWithLifecycle()
    val reportPreview by model.reportPreview.collectAsStateWithLifecycle()
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.exportReport(uri, true) else model.cancelExport()
    }
    val textSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.exportReport(uri, false) else model.cancelExport()
    }
    var tab by rememberSaveable { mutableIntStateOf(2) }
    LaunchedEffect(tab) { if (tab == 4) app.localStorage.refresh() }
    var keyword by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var showReports by rememberSaveable { mutableStateOf(false) }
    var showFindings by rememberSaveable { mutableStateOf(false) }
    var period by rememberSaveable {
        mutableStateOf(if (BuildConfig.BUNDLED_DEMO_DATA) StatisticsPeriod.MONTH else StatisticsPeriod.HOURS24)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmClear by remember { mutableStateOf(false) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    var showCaptureTools by rememberSaveable { mutableStateOf(false) }
    var showAudioRecords by rememberSaveable { mutableStateOf(false) }
    var audioDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    val needsAudioContent = tab == 3 || tab == 4 || showAudioRecords || audioDetailId != null || showCaptureTools
    val audioSessions = if (tab == 3 || showAudioRecords || audioDetailId != null) {
        val rows by audioModel.sessions.collectAsStateWithLifecycle()
        rows
    } else emptyList()
    val audioState = if (needsAudioContent) {
        val state by audioModel.ui.collectAsStateWithLifecycle()
        state
    } else AudioUiState()
    val audioRuntime = if (needsAudioContent) {
        val state by audioModel.runtime.state.collectAsStateWithLifecycle()
        state
    } else AudioRuntimeState()
    // 网关与模型配置统一在服务端管理台维护，App 不再提供设置入口（保留声明以便恢复）。
    // var showCloud by rememberSaveable { mutableStateOf(false) }
    val showCloud = false
    var deleteEvent by remember { mutableStateOf<BrowseEvent?>(null) }
    var deletePackage by remember { mutableStateOf<CaptureApp?>(null) }
    var supplement by remember { mutableStateOf<SupplementSelection?>(null) }
    fun supplementAuthorization(): String = MonitorPolicy.hash(
        "${app.repository.captureGeneration}|${app.captureSettings.authorizationRevision}|" +
            "${app.audioCoordinator.settings.authorizationRevision}")
    LaunchedEffect(discoveryRequest) {
        if (discoveryRequest > 0) { tab = 0; showFindings = true; showRecords = false; showCaptureTools = false; showAudioRecords = false }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                app.localStorage.refresh()
                model.refreshPermission(); now = System.currentTimeMillis(); model.refreshReportWindow(now)
            }
        }
        owner.lifecycle.addObserver(observer)
        model.refreshPermission()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(period) {
        model.setPeriod(period)
        if (!model.analysis.value.active) model.analyze(period)
    }
    LaunchedEffect(all, period) {
        while (true) { now = System.currentTimeMillis(); model.refreshReportWindow(now); delay(60_000) }
    }
    val statisticsKey = remember(all, period, now) { Any() }
    val statisticsVisible = tab == 2 && !showAudioRecords
    val preparedStatistics by produceState<Pair<Any, BrowseStatistics>?>(null, statisticsKey, statisticsVisible) {
        if (statisticsVisible && value?.first !== statisticsKey) {
            value = statisticsKey to withContext(Dispatchers.Default) { calculateStatistics(all, period, now) }
        }
    }
    val emptyStatistics = remember(period, now) { calculateStatistics(emptyList(), period, now) }
    val statistics = preparedStatistics?.takeIf { it.first === statisticsKey }?.second ?: emptyStatistics
    val appNames = remember(availableApps) { availableApps.associate { it.packageName to it.label } }
    val selectedApps = remember(allowed, appNames) { allowed.sorted().map { CaptureApp(it, appNames[it] ?: it) } }
    val audioActions = rememberAudioActions(audioModel, selectedApps) { tab = 4; showCaptureTools = false; showApps = true }
    BackHandler(showRecords || showCaptureTools || showAudioRecords) {
        when { showAudioRecords -> showAudioRecords = false; showCaptureTools -> showCaptureTools = false; else -> showRecords = false }
    }
    val pauseAudio: (() -> Unit)? = if (audioRuntime.isCapturing) ({
        if (audioRuntime.isPaused) audioModel.runtime.resumePlayback() else audioModel.runtime.pausePlayback()
    }) else null
    fun requestSupplement(request: SupplementRequest) {
        if (!audioConfig.enabled) screenScope.launch { snackbar.showSnackbar("请先开启云端分析") }
        else analysis.report?.takeIf { !analysis.stale && !analysis.running }?.let { report ->
            supplement = SupplementSelection(request, report, supplementAuthorization())
        }
    }
    fun setRecording(value: Boolean) {
        if (!value) app.audioCoordinator.stopAll()
        model.setCapture(value)
    }
    val currentReport = analysis.report?.takeIf { !analysis.stale && it.period == period.label }
    val active = enabled && permission && diagnostics.connected && allowed.isNotEmpty()
    val status = when { !enabled -> "已暂停"; allowed.isEmpty() -> "未选应用"; !permission -> "待授权"; !diagnostics.connected -> "待连接"; else -> "记录中" }
    val titles = listOf("观点", "历史", "概览", "记录", "设置")
    val pageState = rememberSaveableStateHolder()
    val pageTitles = listOf("观点分布", "浏览历史", "信息概览", "实时记录", "系统设置")
    val icons = listOf(R.drawable.ic_viewpoints, R.drawable.ic_records, R.drawable.ic_overview,
        R.drawable.ic_recording, R.drawable.ic_profile)
    Scaffold(containerColor = PageBackground, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SwissAppHeader(pageTitles[tab], period, { period = it; model.setPeriod(it) }, active, status,
                onRecording = {
                    when {
                        allowed.isEmpty() -> tab = 3
                        !permission && !active -> onOpenSettings()
                        else -> setRecording(!enabled)
                    }
                }, onFactCheck = if (tab == 0) ({ showFindings = true }) else null,
                findingCount = monitor.findings.size, showRecording = tab != 0, showPeriods = tab != 4)
        }, bottomBar = {
        PojianNavigationBar(titles, selectedIndex = tab, onSelect = { index ->
            tab = index; showRecords = false; showCaptureTools = false; showAudioRecords = false
        }) { index -> Icon(painterResource(icons[index]), contentDescription = null, modifier = Modifier.size(23.dp)) }
    }) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val contentModifier = Modifier.padding(padding).widthIn(max = 760.dp).fillMaxWidth()
            pageState.SaveableStateProvider(if (showAudioRecords) "audio-records" else "tab-$tab") {
            when {
                showAudioRecords -> AudioRecordsScreen(audioSessions, onSession = { audioDetailId = it.id },
                    onImport = audioActions.onImport, onBack = { showAudioRecords = false }, modifier = contentModifier,
                    canImport = audioState.modelState.status == AudioModelStatus.READY && audioState.task == null)
                tab == 0 -> SwissViewpointsScreen(all, semantics, currentReport, period, now, appNames,
                    selectedTopic, { selectedTopic = it }, ::requestSupplement, contentModifier,
                    onFactCheck = { showFindings = true }, findingCount = monitor.findings.size)
                tab == 1 -> SwissHistoryScreen(all, semantics, currentReport, appNames, keyword,
                    { keyword = null }, { deleteEvent = it }, contentModifier)
                tab == 2 -> OverviewScreen(statistics, period, { period = it; model.setPeriod(it) }, active, status,
                    appNames, onRecords = { tab = 1 }, onSettings = { tab = 3 }, modifier = contentModifier,
                    analysis = analysis, rawEvents = all,
                    onAnalyze = { purpose -> model.updatePurpose(purpose); model.analyze(period) },
                    onPurposeChange = model::updatePurpose, selectedAppCount = allowed.size, now = now,
                    semantics = semantics, onPreviewReport = model::previewReport, onSupplement = ::requestSupplement,
                    onKeyword = { keyword = it; tab = 1 }, onTopic = { selectedTopic = it; tab = 0 },
                    onReports = { showReports = true })
                tab == 3 -> SwissRecordingScreen(permission, enabled, diagnostics, availableApps, allowed, all,
                    currentReport, audioState, audioSessions, ::setRecording, onOpenSettings, model::setAllowedPackages,
                    { showApps = true }, audioActions.onImport, audioActions.onCapture, audioModel::downloadModel,
                    audioModel::stopTask, { showAudioRecords = true }, contentModifier,
                    onCancelDownload = audioModel.runtime::cancelDownload, onPauseResume = pauseAudio,
                    isAudioPaused = audioRuntime.isPaused, message = message, onSettings = { tab = 4 })
                else -> SwissSettingsScreen(storageUsage.copy(limitBytes = storageLimit, retentionDays = storageRetention), count > 0,
                    { confirmClear = true }, { bytes -> withContext(Dispatchers.IO) { app.localStorageSettings.setLimitBytes(bytes) } },
                    audioConfig, audioModel::saveConfig, monitorConfig, app.monitor::saveConfig,
                    app.audioCoordinator.settings.requestsToday(), app.monitor.frameCount(), { tab = 3 },
                    { showReports = true }, {
                        app.captureSettings.setLocalThumbnails(false)
                        withContext(Dispatchers.IO) { app.workThumbnails.maintain() }
                    }, contentModifier, audioSettingsContent = {
                        SwissSpeechModelSettings(audioState, audioModel::downloadModel, audioModel.runtime::cancelDownload)
                    }, transcriptContent = {
                        val transcripts by audioModel.sessions.collectAsStateWithLifecycle()
                        AudioTranscriptSettings(transcripts, onOpenRecords = { showAudioRecords = true },
                            onSession = { audioDetailId = it.id }, embedded = true)
                    }, updateContent = { com.emerge.app.update.UpdateSettingsSection(embedded = true) },
                    onSaveStorageRetention = { days ->
                        withContext(Dispatchers.IO) { app.localStorageSettings.setRetentionDays(days) }
                    }, captureSettingsContent = {
                        SwissCaptureSettings(permission, enabled, diagnostics, allowed.size, audioState, audioRuntime.isPaused,
                            monitorConfig.bubble, onOpenSettings, ::setRecording, { showApps = true },
                            { app.monitor.saveConfig(monitorConfig.copy(bubble = it)) }, audioActions.onCapture, audioModel::stopTask, message)
                    })
            }
            }
        }
    }
    if (showReports) SwissReportDialog(all, semantics, now, onDismiss = { showReports = false },
        purpose = analysis.purpose, onPurposeChange = model::updatePurpose, analysisStatuses = analysisStatuses, appNames = appNames)
    if (showFindings) ModalBottomSheet(onDismissRequest = { showFindings = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PageBackground) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(5.dp), color = DesignTokens.Ink,
                contentColor = Color.White) {
                Text("VERIFY", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("事实核查与发现", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            PojianIconButton({ showFindings = false }) { Icon(Icons.Outlined.Close, "关闭") }
        }
        MonitorDiscoverScreen(monitor, appNames, Modifier.fillMaxHeight(.88f),
            onRecords = { showFindings = false; tab = 1 }, events = all, semantics = semantics)
    }
    reportPreview?.let { ReportFilePreview(it, model::dismissReportPreview) }
    supplement?.let { selection ->
        InformationSupplementPanel(selection.request, audioConfig,
            authorizationKey = supplementAuthorization() + "|${analysis.stale}|${analysis.report?.generatedAt}|${period.name}",
            allowLocalHttp = BuildConfig.DEBUG, isCurrent = {
                val current = model.analysis.value
                current.report === selection.report && !current.stale && !current.running &&
                    current.report?.period == period.label && selection.authorization == supplementAuthorization() &&
                    app.audioCoordinator.settings.config.value.enabled
            }, onRequest = { app.audioCoordinator.settings.recordRequest() }, onDismiss = { supplement = null })
    }
    audioDetailId?.let { id -> audioSessions.firstOrNull { it.id == id }?.let { session ->
        AudioSessionDetailSheet(session, onDismiss = { audioDetailId = null }, onSaveSegment = audioModel::editSegment,
            onDelete = { app.monitor.invalidate(); audioModel.deleteSession(id); audioDetailId = null },
            onAnalyze = { audioModel.analyze(id) }, message = audioAlert)
    } }
    // 网关配置已移至服务端管理台，App 使用内置服务端地址与令牌，不再弹出设置对话框。
    // if (showCloud) MonitorGatewayDialog(audioConfig, onDismiss = { showCloud = false }, onSave = audioModel::saveConfig)
    if (showApps) AppPickerDialog(availableApps, allowed, appsLoading,
        remember(all) { all.groupingBy { it.packageName }.eachCount() }, onDismiss = { showApps = false },
        onApply = { model.setAllowedPackages(it); showApps = false }, onDeletePackage = { deletePackage = it })
    if (confirmClear) PojianDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清空本地记录？", fontWeight = FontWeight.SemiBold) },
        text = {
            Surface(shape = DesignTokens.InnerShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))) {
                Text("暂停采集并删除全部记录与分析，无法撤销。已导出文件保留。",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        },
        confirmButton = { PojianButton(onClick = { app.monitor.invalidate(); model.clearHistory(); confirmClear = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("清空全部") } },
        dismissButton = { PojianTextButton(onClick = { confirmClear = false }) { Text("取消") } })
    deleteEvent?.let { event -> PojianDialog(onDismissRequest = { deleteEvent = null },
        title = { Text("删除这条记录？", fontWeight = FontWeight.SemiBold) },
        text = { Text("删除记录 #${event.id} 并清除旧核查结果，无法撤销。") },
        confirmButton = { PojianButton(onClick = { app.monitor.invalidate(); model.deleteEvent(event.id); deleteEvent = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
        dismissButton = { PojianTextButton(onClick = { deleteEvent = null }) { Text("取消") } }) }
    deletePackage?.let { application -> PojianDialog(onDismissRequest = { deletePackage = null },
        title = { Text("删除此应用的记录？", fontWeight = FontWeight.SemiBold) },
        text = { SourceAppText(application.packageName,
            "暂停采集，删除「${application.label}」的历史记录与旧核查结果。无法撤销。", appName = application.label,
            style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current) },
        confirmButton = { PojianButton(onClick = { app.monitor.invalidate(); model.deletePackage(application.packageName); deletePackage = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除历史") } },
        dismissButton = { PojianTextButton(onClick = { deletePackage = null }) { Text("取消") } }) }
}
