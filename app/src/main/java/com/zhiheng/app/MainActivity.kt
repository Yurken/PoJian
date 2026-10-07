package com.zhiheng.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhiheng.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = PageBackground,
                surface = Color.White, onSurface = Ink, onBackground = Ink)) {
                ZhiHengScreen { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZhiHengScreen(onOpenSettings: () -> Unit) {
    val model: BrowseViewModel = viewModel()
    val recent by model.events.collectAsStateWithLifecycle()
    val all by model.allEvents.collectAsStateWithLifecycle()
    val count by model.count.collectAsStateWithLifecycle()
    val enabled by model.captureEnabled.collectAsStateWithLifecycle()
    val permission by model.serviceEnabled.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val analysis by model.analysis.collectAsStateWithLifecycle()
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.exportSnapshot?.let { model.exportReport(uri, it, true) }
    }
    val textSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.exportSnapshot?.let { model.exportReport(uri, it, false) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(StatisticsPeriod.DAY) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmClear by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.refreshPermission()
                now = System.currentTimeMillis()
            }
        }
        owner.lifecycle.addObserver(observer)
        model.refreshPermission()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tab) {
        while (true) { now = System.currentTimeMillis(); delay(60_000) }
    }
    val statistics by produceState(calculateStatistics(emptyList(), period, now), all, period, now) {
        value = withContext(Dispatchers.Default) { calculateStatistics(all, period, now) }
    }
    val context = LocalContext.current
    val packageNames = remember(all) { all.map { it.packageName }.toSet() }
    val appNames by produceState<Map<String, String>>(emptyMap(), packageNames) {
        value = withContext(Dispatchers.IO) {
            packageNames.associateWith { name ->
                runCatching { context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
            }
        }
    }
    val titles = listOf("概览", "记录", "我的")
    val icons = listOf(R.drawable.ic_overview, R.drawable.ic_records, R.drawable.ic_profile)
    Scaffold(containerColor = PageBackground,
        topBar = { TopAppBar(title = { Text(if (tab == 0) "知衡" else titles[tab]) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBackground)) },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                        label = { Text(title) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent,
                            selectedTextColor = Accent, indicatorColor = Color(0xFFE6F3F0)))
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> OverviewScreen(statistics, period, { period = it }, enabled && diagnostics.connected,
                if (enabled && diagnostics.connected) "采集中" else if (enabled && permission) "待连接" else if (enabled) "待授权" else "已暂停",
                appNames, onRecords = { tab = 1 }, onSettings = { tab = 2 }, modifier = Modifier.padding(padding),
                analysis = analysis, rawEvents = all,
                onAnalyze = { purpose -> model.analyze(all, period, purpose) },
                onExport = { report, pdf ->
                    model.exportSnapshot = report
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA).format(java.util.Date(report.generatedAt))
                    if (pdf) pdfSaver.launch("知衡-信息环境报告-$stamp.pdf")
                    else textSaver.launch("知衡-信息环境报告-$stamp.txt")
                })
            1 -> RecordsScreen(recent, count, appNames, Modifier.padding(padding))
            2 -> SettingsScreen(permission, enabled, count, model::setCapture, onOpenSettings,
                { confirmClear = true }, message, Modifier.padding(padding), diagnostics)
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清空本地记录？") }, text = { Text("会先暂停采集，再删除全部历史记录，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = { model.clearHistory(); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
