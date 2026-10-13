package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.emerge.app.analysis.*
import com.emerge.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val LocalSyntheticDataset = staticCompositionLocalOf<SyntheticDataset?> { null }

@Composable
internal fun analysisSourceLabel(value: String): String =
    if (LocalSyntheticDataset.current != null) value.replace("云端分析", "合成标注").replace("云端采样结果", "合成标注") else value

@Composable
internal fun SyntheticDemoEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    WhiteCard {
        PojianTextButton(onClick = { open = true }, modifier = Modifier.testTag("synthetic-demo-open")) { Text("合成实验数据库") }
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { SyntheticDemoScreen { open = false } }
    }
}

@Composable
private fun SyntheticDemoScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var overview by remember { mutableStateOf<ResearchOverview?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val profile = overview?.profiles?.firstOrNull { it.id == selectedId }
        ?: overview?.profiles?.firstOrNull { it.concentration == "high" && it.anxiety == "high" }
    var data by remember(profile?.id) { mutableStateOf<SyntheticDataset?>(null) }
    var selectProfile by remember { mutableStateOf(false) }
    var showWorks by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var phase by rememberSaveable { mutableIntStateOf(1) }
    var period by rememberSaveable { mutableStateOf(StatisticsPeriod.WEEK) }
    LaunchedEffect(retry) {
        error = false
        try { overview = ResearchDatabaseStore.overview(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    LaunchedEffect(profile?.id, retry) {
        data = null
        profile?.let {
            try { data = ResearchDatabaseStore.load(context, it) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("合成实验数据库", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            PojianTextButton(onClick = onClose) { Text("关闭") }
        }
        overview?.let { info ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                MetadataText("${info.workCount} 个作品 · ${info.versionCount} 个观察版本 · ${info.contactCount} 次观看 · ${info.profiles.size} 名虚拟用户")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PojianTextButton(onClick = { selectProfile = true }) { Text(profile?.label.orEmpty()) }
                    PojianTextButton(onClick = { showWorks = true }) { Text("作品库") }
                }
                profile?.let { MetadataText(it.conditions) }
            }
        }
        val loaded = data
        when {
            error -> PojianTextButton(onClick = { retry++ }) { Text("加载失败，重试") }
            loaded == null -> CircularProgressIndicator(Modifier.padding(24.dp))
            else -> CompositionLocalProvider(LocalSyntheticDataset provides loaded) {
                val zone = ZoneId.of("Asia/Shanghai")
                val now = loaded.now - if (phase == 0) 7 * 86_400_000L else 0L
                val window = remember(now, period) { ObservationWindow.forPeriod(period, now, zone) }
                val state by produceState<Pair<BrowseStatistics, CognitiveReport>?>(null, loaded, now, period) {
                    value = null
                    value = withContext(Dispatchers.Default) {
                        val computation = currentCoroutineContext()
                        calculateStatistics(loaded.events, period, now, zone) to CognitiveAnalyzer().analyze(
                            loaded.events, period, now, zone, semantics = loaded.semantics,
                            checkCancellation = { computation.ensureActive() })
                    }
                }
                PojianSegmentedTabs(listOf("前 7 天", "后 7 天"), phase, { phase = it })
                PojianSegmentedTabs(StatisticsPeriod.entries.map { it.label }, StatisticsPeriod.entries.indexOf(period),
                    { period = StatisticsPeriod.entries[it] })
                val result = state
                if (result == null) CircularProgressIndicator(Modifier.padding(24.dp))
                else {
                    val (stats, report) = result
                    val emotions = remember(loaded, window) { loaded.emotions.filter { it.timestamp in window.start until window.end } }
                    val anxiety = emotions.filter { it.expression == "焦虑" && it.attribution in setOf("affirmed", "reported") }
                        .distinctBy { it.workKey }
                    val format = remember { DateTimeFormatter.ofPattern("yyyy/M/d HH:mm").withZone(zone) }
                    val listState = rememberLazyListState()
                    val timeline = rememberViewpointTimelineState(loaded.events, period, now, report, loaded.semantics, zone)
                    val cocoonVisible by remember { derivedStateOf {
                        listState.layoutInfo.visibleItemsInfo.any { it.key == "synthetic-cocoon" }
                    } }
                    LazyColumn(Modifier.weight(1f).testTag("synthetic-demo-content"), state = listState, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            MetadataText("模拟时点 ${format.format(Instant.ofEpochMilli(now))} · AI 合成标注（未人工复核）")
                            Text("${stats.total} 个作品 · ${stats.visitCount} 次观看 · ${formatDuration(stats.observedDurationMillis)}")
                        }
                        item(key = "synthetic-cocoon") { CocoonCard(AnalysisState(report = report), period, visible = cocoonVisible) }
                        item {
                            WhiteCard {
                                SectionTitle("内容中的焦虑表达")
                                Text("${anxiety.size} / ${stats.total} 个作品", style = MaterialTheme.typography.titleMedium)
                                anxiety.take(4).forEach { emotion ->
                                    MetadataText(emotion.holder)
                                    QuoteBlock(emotion.context)
                                }
                            }
                        }
                        viewpointTimeline(timeline, loaded.appNames, plotHeight = 112.dp)
                    }
                }
            }
        }
    }
    if (selectProfile) AlertDialog(onDismissRequest = { selectProfile = false }, title = { Text("虚拟用户") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(overview?.profiles.orEmpty(), key = { it.id }) { option ->
                    TextButton(onClick = { selectedId = option.id; error = false; selectProfile = false }) {
                        Column(Modifier.fillMaxWidth()) { Text(option.label); MetadataText(option.conditions) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { selectProfile = false }) { Text("关闭") } })
    if (showWorks) ResearchWorksDialog(overview?.workCount ?: 0) { showWorks = false }
}

@Composable
private fun ResearchWorksDialog(total: Int, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var page by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var works by remember(page) { mutableStateOf<List<ResearchWork>?>(null) }
    var error by remember(page) { mutableStateOf(false) }
    var expanded by remember(page) { mutableStateOf<String?>(null) }
    LaunchedEffect(page, retry) {
        error = false
        try { works = ResearchDatabaseStore.works(context, page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("合成作品库 · $total", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                when {
                    error -> TextButton(onClick = { retry++ }) { Text("加载失败，重试") }
                    works == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    else -> key(page) {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(works.orEmpty(), key = { it.id }) { work ->
                                WhiteCard {
                                    MetadataText("${work.media} · ${work.domain} · ${when (work.split) { "train" -> "训练集"; "validation" -> "验证集"; else -> "测试集" }}")
                                    Text(work.text.lineSequence().first(), style = MaterialTheme.typography.titleSmall)
                                    TextButton(onClick = { expanded = if (expanded == work.id) null else work.id }) {
                                        Text(if (expanded == work.id) "收起" else "查看详情")
                                    }
                                    if (expanded == work.id) QuoteBlock(work.text)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                    Text("${page + 1} / ${(total + 39) / 40}")
                    TextButton(onClick = { page++ }, enabled = (page + 1) * 40 < total) { Text("下一页") }
                }
            }
        }
    }
}
