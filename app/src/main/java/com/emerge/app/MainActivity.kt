package com.emerge.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import com.emerge.app.ui.theme.EmergeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EmergeTheme {
                EmergeScreen { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmergeScreen(onOpenSettings: () -> Unit) {
    val model: BrowseViewModel = viewModel()
    val audioModel: AudioViewModel = viewModel()
    val audioState by audioModel.ui.collectAsStateWithLifecycle()
    val audioSessions by audioModel.sessions.collectAsStateWithLifecycle()
    val audioConfig by audioModel.config.collectAsStateWithLifecycle()
    val audioRuntime by audioModel.runtime.state.collectAsStateWithLifecycle()
    val audioAlert by audioModel.alert.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(audioAlert) { audioAlert?.let { snackbar.showSnackbar(it) } }
    val all by model.allEvents.collectAsStateWithLifecycle()
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
    var showAudioRecords by rememberSaveable { mutableStateOf(false) }
    var audioDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCloud by rememberSaveable { mutableStateOf(false) }
    var deleteEvent by remember { mutableStateOf<BrowseEvent?>(null) }
    var deletePackage by remember { mutableStateOf<CaptureApp?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.refreshPermission()
                now = System.currentTimeMillis()
                model.refreshReportWindow(now)
            }
        }
        owner.lifecycle.addObserver(observer)
        model.refreshPermission()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(period) { model.setPeriod(period) }
    LaunchedEffect(all, period) {
        while (true) {
            now = System.currentTimeMillis()
            model.refreshReportWindow(now)
            delay(60_000)
        }
    }
    val statistics by produceState(calculateStatistics(emptyList(), period, now), all, period, now) {
        val snapshotNow = now
        value = withContext(Dispatchers.Default) { calculateStatistics(all, period, snapshotNow) }
    }
    val appNames = remember(availableApps) { availableApps.associate { it.packageName to it.label } }
    val selectedApps = remember(allowed, appNames) { allowed.sorted().map { CaptureApp(it, appNames[it] ?: it) } }
    val audioActions = rememberAudioActions(audioModel, selectedApps) { tab = 3; showApps = true }
    BackHandler(showAudioRecords) { showAudioRecords = false }
    val titles = listOf("分析", "足迹", "录制", "设置")
    val icons = listOf(R.drawable.ic_overview, R.drawable.ic_records, R.drawable.ic_recording, R.drawable.ic_profile)
    val pauseAudio: (() -> Unit)? = if (audioRuntime.isCapturing) ({
        if (audioRuntime.isPaused) audioModel.runtime.resumePlayback() else audioModel.runtime.pausePlayback()
    }) else null
    val showLocalLabel = LocalConfiguration.current.screenWidthDp >= 360 && LocalDensity.current.fontScale <= 1.2f
    Scaffold(containerColor = PageBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(painterResource(R.mipmap.ic_launcher_artwork), contentDescription = null,
                            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)))
                        Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }, actions = {
                    if (showLocalLabel) Surface(shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 20.dp)) {
                        Text(when {
                            audioRuntime.isCapturing -> if (audioRuntime.isPaused) "已暂停" else "记录中"
                            audioRuntime.isImporting -> "转写中"
                            audioConfig.enabled -> "云端增强"
                            else -> "本地运行"
                        }, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBackground))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; showAudioRecords = false },
                        icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                        label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent,
                            selectedTextColor = Accent, indicatorColor = Color.Transparent))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val contentModifier = Modifier.padding(padding).widthIn(max = 760.dp).fillMaxWidth()
        if (showAudioRecords) AudioRecordsScreen(audioSessions, onSession = { audioDetailId = it.id },
            onImport = audioActions.onImport, onBack = { showAudioRecords = false }, modifier = contentModifier,
            canImport = audioState.modelState.status == AudioModelStatus.READY && audioState.task == null)
        else when (tab) {
            0 -> OverviewScreen(statistics, period, { period = it; model.setPeriod(it) },
                enabled && permission && diagnostics.connected && allowed.isNotEmpty(),
                when {
                    allowed.isEmpty() -> "未选择范围"
                    !enabled -> "已暂停"
                    !permission -> "待授权"
                    !diagnostics.connected -> "待连接"
                    else -> "采集中"
                },
                appNames, onRecords = { tab = 1 }, onSettings = { tab = 3 }, modifier = contentModifier,
                analysis = analysis, rawEvents = all,
                onAnalyze = { purpose -> model.updatePurpose(purpose); model.analyze(period) },
                onPurposeChange = model::updatePurpose, selectedAppCount = allowed.size, now = now,
                onExport = { report, pdf ->
                    if (model.prepareExport(report)) {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA).format(java.util.Date(report.generatedAt))
                        if (pdf) pdfSaver.launch("破茧-Emerge-信息环境回顾-$stamp.pdf")
                        else textSaver.launch("破茧-Emerge-信息环境回顾-$stamp.txt")
                    }
                }, audioContent = {
                    AudioEntryCard(audioState, audioActions.onImport, audioActions.onCapture, audioModel::downloadModel,
                        audioModel::stopTask, { showAudioRecords = true }, onCancelDownload = audioModel.runtime::cancelDownload,
                        compact = true, onPauseResume = pauseAudio, isPaused = audioRuntime.isPaused)
                })
            1 -> RecordsScreen(all, count, appNames, contentModifier,
                onDelete = { deleteEvent = it }, onSettings = { tab = 3 },
                audioContent = { AudioRecordsEntry(audioSessions.size, { showAudioRecords = true }) })
            2 -> RecordingScreen(audioState, audioActions.onImport, audioActions.onCapture, audioModel::downloadModel,
                audioModel::stopTask, { showAudioRecords = true }, modifier = contentModifier,
                onCancelDownload = audioModel.runtime::cancelDownload, onPauseResume = pauseAudio,
                isPaused = audioRuntime.isPaused)
            3 -> SettingsScreen(permission, enabled, count, model::setCapture, onOpenSettings,
                { confirmClear = true }, message, contentModifier, diagnostics,
                selectedApps, onChooseApps = { showApps = true }, audioContent = {
                    AudioSettingsSection(audioState, audioModel::downloadModel, audioModel.runtime::cancelDownload,
                        { showCloud = true })
                })
        }
        }
    }
    audioDetailId?.let { id ->
        audioSessions.firstOrNull { it.id == id }?.let { session ->
            AudioSessionDetailSheet(session, onDismiss = { audioDetailId = null }, onSaveSegment = audioModel::editSegment,
                onDelete = { audioModel.deleteSession(id); audioDetailId = null }, onAnalyze = { audioModel.analyze(id) }, message = audioAlert)
        }
    }
    if (showCloud) AudioCloudDialog(audioConfig, onDismiss = { showCloud = false }, onSave = audioModel::saveConfig)
    if (showApps) AppPickerDialog(availableApps, allowed, appsLoading,
        remember(all) { all.groupingBy { it.packageName }.eachCount() },
        onDismiss = { showApps = false }, onApply = { model.setAllowedPackages(it); showApps = false },
        onDeletePackage = { deletePackage = it })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清空本地记录？") }, text = { Text("将先暂停采集，再删除全部记录和当前分析。此操作无法撤销；已导出的文件不受影响，需自行删除。") },
        confirmButton = { TextButton(onClick = { model.clearHistory(); confirmClear = false }) { Text("暂停并清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    deleteEvent?.let { event ->
        AlertDialog(onDismissRequest = { deleteEvent = null }, title = { Text("删除这条记录？") },
            text = { Text("事件 #${event.id} 将从本机删除，观点和报告会按剩余记录重新分析。此操作无法撤销。") },
            confirmButton = { TextButton(onClick = { model.deleteEvent(event.id); deleteEvent = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteEvent = null }) { Text("取消") } })
    }
    deletePackage?.let { application ->
        AlertDialog(onDismissRequest = { deletePackage = null }, title = { Text("删除此应用的记录？") },
            text = { Text("将先暂停采集，再删除「${application.label}」在本机的全部历史记录，并更新分析报告。此操作无法撤销。") },
            confirmButton = { TextButton(onClick = { model.deletePackage(application.packageName); deletePackage = null }) { Text("删除历史") } },
            dismissButton = { TextButton(onClick = { deletePackage = null }) { Text("取消") } })
    }
}
