package com.emerge.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.*
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var discoveryRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("monitor_discover", false)) discoveryRequest++
        setContent { EmergeTheme { EmergeScreen(discoveryRequest) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } } }
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
    val app = LocalContext.current.applicationContext as EmergeApplication
    val model: BrowseViewModel = viewModel()
    val audioModel: AudioViewModel = viewModel()
    val monitor by app.monitor.state.collectAsStateWithLifecycle()
    val monitorConfig by app.monitor.config.collectAsStateWithLifecycle()
    val audioState by audioModel.ui.collectAsStateWithLifecycle()
    val audioSessions by audioModel.sessions.collectAsStateWithLifecycle()
    val audioConfig by audioModel.config.collectAsStateWithLifecycle()
    val audioRuntime by audioModel.runtime.state.collectAsStateWithLifecycle()
    val audioAlert by audioModel.alert.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(audioAlert) { audioAlert?.let { snackbar.showSnackbar(it) } }
    val all by model.allEvents.collectAsStateWithLifecycle()
    val semantics by model.semantics.collectAsStateWithLifecycle()
    val count by model.count.collectAsStateWithLifecycle()
    val enabled by model.captureEnabled.collectAsStateWithLifecycle()
    val allowed by model.allowedPackages.collectAsStateWithLifecycle()
    val availableApps by model.availableApps.collectAsStateWithLifecycle()
    val appsLoading by model.appsLoading.collectAsStateWithLifecycle()
    val permission by model.serviceEnabled.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val analysis by model.analysis.collectAsStateWithLifecycle()
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.exportReport(uri, true) else model.cancelExport()
    }
    val textSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.exportReport(uri, false) else model.cancelExport()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(StatisticsPeriod.DAY) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmClear by remember { mutableStateOf(false) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showRecords by rememberSaveable { mutableStateOf(false) }
    var showCaptureTools by rememberSaveable { mutableStateOf(false) }
    var showAudioRecords by rememberSaveable { mutableStateOf(false) }
    var audioDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    // 网关与模型配置统一在服务端管理台维护，App 不再提供设置入口（保留声明以便恢复）。
    // var showCloud by rememberSaveable { mutableStateOf(false) }
    val showCloud = false
    var deleteEvent by remember { mutableStateOf<BrowseEvent?>(null) }
    var deletePackage by remember { mutableStateOf<CaptureApp?>(null) }
    LaunchedEffect(discoveryRequest) {
        if (discoveryRequest > 0) { tab = 1; showRecords = false; showCaptureTools = false; showAudioRecords = false }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.refreshPermission(); now = System.currentTimeMillis(); model.refreshReportWindow(now)
            }
        }
        owner.lifecycle.addObserver(observer)
        model.refreshPermission()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(period) { model.setPeriod(period) }
    LaunchedEffect(all, period) {
        while (true) { now = System.currentTimeMillis(); model.refreshReportWindow(now); delay(60_000) }
    }
    val statistics by produceState(calculateStatistics(emptyList(), period, now), all, period, now) {
        val snapshotNow = now
        value = withContext(Dispatchers.Default) { calculateStatistics(all, period, snapshotNow) }
    }
    val appNames = remember(availableApps) { availableApps.associate { it.packageName to it.label } }
    val selectedApps = remember(allowed, appNames) { allowed.sorted().map { CaptureApp(it, appNames[it] ?: it) } }
    val audioActions = rememberAudioActions(audioModel, selectedApps) { tab = 2; showCaptureTools = false; showApps = true }
    BackHandler(showRecords || showCaptureTools || showAudioRecords) {
        when { showAudioRecords -> showAudioRecords = false; showCaptureTools -> showCaptureTools = false; else -> showRecords = false }
    }
    val pauseAudio: (() -> Unit)? = if (audioRuntime.isCapturing) ({
        if (audioRuntime.isPaused) audioModel.runtime.resumePlayback() else audioModel.runtime.pausePlayback()
    }) else null
    val titles = listOf("概览", "发现", "设置")
    val icons = listOf(R.drawable.ic_overview, R.drawable.ic_records, R.drawable.ic_profile)
    Scaffold(containerColor = PageBackground, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Surface(color = PageBackground) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.mipmap.ic_launcher_artwork), contentDescription = null,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                        .background(colorResource(R.color.ic_launcher_background)))
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleMedium)
            }
        }
    }, bottomBar = {
        PojianNavigationBar(titles, selectedIndex = tab, onSelect = { index ->
            tab = index; showRecords = false; showCaptureTools = false; showAudioRecords = false
        }) { index ->
            Icon(painterResource(icons[index]), contentDescription = null, modifier = Modifier.size(23.dp))
        }
    }) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val contentModifier = Modifier.padding(padding).widthIn(max = 760.dp).fillMaxWidth()
            when {
                showAudioRecords -> AudioRecordsScreen(audioSessions, onSession = { audioDetailId = it.id },
                    onImport = audioActions.onImport, onBack = { showAudioRecords = false }, modifier = contentModifier,
                    canImport = audioState.modelState.status == AudioModelStatus.READY && audioState.task == null)
                showCaptureTools -> Column(contentModifier) {
                    PojianTextButton(onClick = { showCaptureTools = false }) { Text("返回设置") }
                    RecordingScreen(audioState.copy(cloudLabel = if (audioConfig.enabled) "云端已启用" else "云端关闭"), audioActions.onImport, audioActions.onCapture, audioModel::downloadModel,
                        audioModel::stopTask, { showAudioRecords = true }, modifier = Modifier.weight(1f),
                        onCancelDownload = audioModel.runtime::cancelDownload, onPauseResume = pauseAudio, isPaused = audioRuntime.isPaused)
                }
                showRecords -> Column(contentModifier) {
                    PojianTextButton(onClick = { showRecords = false }) { Text("返回发现") }
                    RecordsScreen(all, count, appNames, Modifier.weight(1f), onDelete = { deleteEvent = it },
                        onSettings = { showRecords = false; tab = 2 },
                        audioContent = { AudioRecordsEntry(audioSessions.size, { showAudioRecords = true }) })
                }
                tab == 0 -> OverviewScreen(statistics, period, { period = it; model.setPeriod(it) },
                    enabled && permission && diagnostics.connected && allowed.isNotEmpty(),
                    when { allowed.isEmpty() -> "未选择范围"; !enabled -> "已暂停"; !permission -> "待授权"
                        !diagnostics.connected -> "待连接"; else -> "采集中" },
                    appNames, onRecords = { tab = 1; showRecords = true }, onSettings = { tab = 2 }, modifier = contentModifier,
                    analysis = analysis, rawEvents = all,
                    onAnalyze = { purpose -> model.updatePurpose(purpose); model.analyze(period) },
                    onPurposeChange = model::updatePurpose, selectedAppCount = allowed.size, now = now,
                    onExport = { report, pdf -> if (model.prepareExport(report)) {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA).format(java.util.Date(report.generatedAt))
                        if (pdf) pdfSaver.launch("破茧-信息环境回顾-$stamp.pdf") else textSaver.launch("破茧-信息环境回顾-$stamp.txt")
                    } }, semantics = semantics)
                tab == 1 -> MonitorDiscoverScreen(monitor, appNames, contentModifier,
                    onRecords = { showRecords = true }, onClear = { app.monitor.invalidate() }, events = all)
                else -> SettingsScreen(permission, enabled, count, { value ->
                    if (!value) app.audioCoordinator.stopAll()
                    model.setCapture(value)
                }, onOpenSettings,
                    { confirmClear = true }, message, contentModifier, diagnostics, selectedApps,
                    onChooseApps = { showApps = true }, audioContent = {
                        MonitorSettingsSection(monitorConfig, app.monitor::saveConfig,
                            app.audioCoordinator.settings.requestsToday(), app.monitor.frameCount(), { /* 网关配置见服务端管理台 */ }, { showCaptureTools = true },
                            cloudEnabled = audioConfig.enabled, onCloudEnabledChange = { value ->
                                audioModel.saveConfig(audioConfig.copy(enabled = value), {}, { error -> app.monitor.notice(error) })
                            })
                    })
            }
        }
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
    if (confirmClear) PojianDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空本地记录？") },
        text = { Text("暂停采集并删除全部记录与分析，无法撤销。已导出文件保留。") },
        confirmButton = { PojianButton(onClick = { app.monitor.invalidate(); model.clearHistory(); confirmClear = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("清空全部") } },
        dismissButton = { PojianTextButton(onClick = { confirmClear = false }) { Text("取消") } })
    deleteEvent?.let { event -> PojianDialog(onDismissRequest = { deleteEvent = null }, title = { Text("删除这条记录？") },
        text = { Text("删除记录 #${event.id} 并清除旧核查结果，无法撤销。") },
        confirmButton = { PojianButton(onClick = { app.monitor.invalidate(); model.deleteEvent(event.id); deleteEvent = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
        dismissButton = { PojianTextButton(onClick = { deleteEvent = null }) { Text("取消") } }) }
    deletePackage?.let { application -> PojianDialog(onDismissRequest = { deletePackage = null }, title = { Text("删除此应用的记录？") },
        text = { SourceAppText(application.packageName,
            "暂停采集，删除「${application.label}」的历史记录与旧核查结果。无法撤销。", appName = application.label,
            style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current) },
        confirmButton = { PojianButton(onClick = { app.monitor.invalidate(); model.deletePackage(application.packageName); deletePackage = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除历史") } },
        dismissButton = { PojianTextButton(onClick = { deletePackage = null }) { Text("取消") } }) }
}
