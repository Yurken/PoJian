package com.emerge.app.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emerge.app.EmergeApplication
import com.emerge.app.analysis.*
import com.emerge.app.data.ObservedWork
import com.emerge.app.data.sourceLabel
import com.emerge.app.data.WorkThumbnail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseWorkCards(items: List<ViewpointItem>, start: Long, end: Long, names: Map<String, String>,
    zone: ZoneId = ZoneId.systemDefault(), onScrolling: (Boolean) -> Unit = {}) {
    val listState = rememberLazyListState()
    val scrollingCallback by rememberUpdatedState(onScrolling)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrollingCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { scrollingCallback(false) } }
    val synthetic = LocalSyntheticDataset.current
    val app = (LocalContext.current.applicationContext as? EmergeApplication).takeIf { synthetic == null }
    val works = (synthetic?.works ?: app?.repository?.works?.collectAsState(initial = emptyList())?.value.orEmpty()).associateBy { it.workKey }
    val thumbnails = app?.workThumbnails?.thumbnails?.collectAsState()?.value.orEmpty()
    val consent = app?.captureSettings?.localThumbnails?.collectAsState()?.value == true
    val packages = app?.captureSettings?.allowedPackages?.collectAsState()?.value.orEmpty()
    val selected = remember(items) { ViewpointTimeline.details(items) }
    var detail by rememberSaveable(start, end) { mutableStateOf<String?>(null) }
    val window = remember(start, end, zone) { ObservationWindow(start, end, zone) }
    // Bounded nested viewport: only visible thumbnails are decoded, at most two on background threads.
    if (selected.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("work-card-list"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(selected, key = { it.workIdentity }) { item ->
            val work = works[item.record.workKey]
            val thumbnail = thumbnails[item.record.workKey]?.takeIf { consent && it.packageName in packages }
            Row(Modifier.fillMaxWidth().testTag("work-card-${item.workIdentity}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkImage(thumbnail, app)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceAppText(item.record.packageName, synthetic?.mediaTypes?.get(item.record.workKey)
                        ?: names[item.record.packageName] ?: item.record.packageName)
                    Text(workTitle(work, item), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, color = Ink)
                    MetadataText(item.durationMillis(window)?.let(::formatDuration) ?: "时长未统计")
                    item.claims.distinctBy { it.key }.take(3).forEach { claim ->
                        Text(ViewpointLabeler.compact(claim.quote), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    PojianTextButton(onClick = { detail = item.workIdentity }, modifier = Modifier.align(Alignment.End)) {
                        Text("查看详情")
                    }
                }
            }
        }
    }
    selected.firstOrNull { it.workIdentity == detail }?.let { item ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            val format = remember(zone) { DateTimeFormatter.ofPattern("M/d HH:mm:ss").withZone(zone) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(workTitle(works[item.record.workKey], item), style = MaterialTheme.typography.titleMedium) }
                items(item.claims.distinctBy { it.key }, key = { "claim:${it.key}" }) { claim ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ViewpointLabeler.compact(claim.quote), style = MaterialTheme.typography.titleSmall)
                        QuoteBlock(claim.quote)
                        listOf(claim.stance, claim.conditions, claim.evidence).filter { it.isNotBlank() && it != claim.quote }
                            .distinct().forEach { QuoteBlock(it) }
                    }
                }
                items(item.records.sortedWith(compareBy({ it.timestamp }, { it.id })), key = { "record:${it.id}" }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider()
                        val source = when (row.sourceType) {
                            "accessibility" -> "观看"
                            "screen_ocr" -> "画面文字"
                            "visual_model" -> "画面分析"
                            else -> "转写"
                        }
                        MetadataText("$source · ${format.format(Instant.ofEpochMilli(row.timestamp))}")
                        QuoteBlock(row.visibleText)
                    }
                }
            }
        }
    }
}

private fun workTitle(work: ObservedWork?, item: ViewpointItem): String = work?.title?.takeIf { it.isNotBlank() }
    ?: item.record.visibleText.lineSequence().map(String::trim).firstOrNull { it.length >= 8 } ?: "作品"

@Composable
private fun WorkImage(row: WorkThumbnail?, app: EmergeApplication?) {
    val bitmap by produceState<Bitmap?>(null, row?.fileName, app) {
        value = null
        if (row != null && app != null) value = app.workThumbnails.load(row)
    }
    Column(Modifier.width(88.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if (loaded != null && row != null) Image(loaded.asImageBitmap(), contentDescription = row.sourceLabel,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("—", color = Muted)
        }
        if (bitmap != null && row != null) Text(row.sourceLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
internal fun LocalThumbnailControl() {
    val app = LocalContext.current.applicationContext as? EmergeApplication ?: return
    val enabled by app.captureSettings.localThumbnails.collectAsState()
    val status by app.workThumbnails.status.collectAsState()
    var confirm by rememberSaveable { mutableStateOf(false) }
    WhiteCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本地缩略图", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            PojianSwitch(checked = enabled, enabled = Build.VERSION.SDK_INT >= 30,
                onCheckedChange = { if (it) confirm = true else app.captureSettings.setLocalThumbnails(false) },
                modifier = Modifier.testTag("local-thumbnail-toggle"))
        }
        if (Build.VERSION.SDK_INT < 30) MetadataText("当前系统仅显示文字")
        if (enabled && status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("保存本地缩略图") },
        text = { Text("将已观察画面压缩保存在本机，用于作品缩略图。关闭后删除已保存缩略图，不会因此上传图片。") },
        confirmButton = { TextButton(onClick = { app.captureSettings.setLocalThumbnails(true); confirm = false }) { Text("允许保存") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
