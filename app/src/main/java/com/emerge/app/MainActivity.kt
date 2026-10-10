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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.mipmap.ic_launcher_artwork), contentDescription = null,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)))
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleMedium)
            }
        }, actions = {
            Text(if (!enabled || !permission || !diagnostics.connected) "监测已暂停" else "采样监测中",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 16.dp))
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBackground))
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            titles.forEachIndexed { index, title ->
                NavigationBarItem(selected = tab == index, onClick = {
                    tab = index; showRecords = false; showCaptureTools = false; showAudioRecords = false
                }, icon = { Icon(painterResource(icons[index]), contentDescription = null) }, label = { Text(title) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent,
                        selectedTextColor = Accent, indicatorColor = Color.Transparent))
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val contentModifier = Modifier.padding(padding).widthIn(max = 760.dp).fillMaxWidth()
            when {
                showAudioRecords -> AudioRecordsScreen(audioSessions, onSession = { audioDetailId = it.id },
                    onImport = audioActions.onImport, onBack = { showAudioRecords = false }, modifier = contentModifier,
                    canImport = audioState.modelState.status == AudioModelStatus.READY && audioState.task == null)
                showCaptureTools -> Column(contentModifier) {
                    TextButton(onClick = { showCaptureTools = false }) { Text("返回设置") }
                    Text("采集工具只提供材料；观点观察与事实核查结果在发现页。", modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall)
                    RecordingScreen(audioState.copy(cloudLabel = if (audioConfig.enabled) "云端已启用；自动发送由实时核查与画面开关控制" else "云端关闭"), audioActions.onImport, audioActions.onCapture, audioModel::downloadModel,
                        audioModel::stopTask, { showAudioRecords = true }, modifier = Modifier.weight(1f),
                        onCancelDownload = audioModel.runtime::cancelDownload, onPauseResume = pauseAudio, isPaused = audioRuntime.isPaused)
                }
                showRecords -> Column(contentModifier) {
                    TextButton(onClick = { showRecords = false }) { Text("返回发现") }
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
                    } },
                    // Keep the optional slot's old name for source compatibility; it no longer hosts recording controls.
                    audioContent = { MonitorOverviewCard(monitor, monitorConfig, { tab = 1 }, { tab = 2 }) }, semantics = semantics)
                tab == 1 -> MonitorDiscoverScreen(monitor, appNames, contentModifier,
                    onRecords = { showRecords = true }, onClear = { app.monitor.invalidate() })
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
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空本地记录？") },
        text = { Text("将先暂停采集，再删除全部记录、核查结果和当前分析。无法撤销；已导出的文件需自行删除。") },
        confirmButton = { TextButton(onClick = { app.monitor.invalidate(); model.clearHistory(); confirmClear = false }) { Text("暂停并清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    deleteEvent?.let { event -> AlertDialog(onDismissRequest = { deleteEvent = null }, title = { Text("删除这条记录？") },
        text = { Text("事件 #${event.id} 将从本机删除，旧核查结果会清除，观点和报告按剩余记录重新分析。") },
        confirmButton = { TextButton(onClick = { app.monitor.invalidate(); model.deleteEvent(event.id); deleteEvent = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteEvent = null }) { Text("取消") } }) }
    deletePackage?.let { application -> AlertDialog(onDismissRequest = { deletePackage = null }, title = { Text("删除此应用的记录？") },
        text = { Text("将暂停采集并删除「${application.label}」的全部历史记录，清除旧核查结果，并更新分析报告。") },
        confirmButton = { TextButton(onClick = { app.monitor.invalidate(); model.deletePackage(application.packageName); deletePackage = null }) { Text("删除历史") } },
        dismissButton = { TextButton(onClick = { deletePackage = null }) { Text("取消") } }) }
}
