package com.emerge.app.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

private data class PreviewDocument(val file: File, val pageCount: Int, val texts: List<String>? = null)

/** Reads the exported SAF document, including non-seekable providers, without requiring a viewer app. */
@Composable
internal fun ReportFilePreview(report: ExportedReport, onClose: () -> Unit) {
    val context = LocalContext.current
    var document by remember(report) { mutableStateOf<PreviewDocument?>(null) }
    var error by remember(report) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var page by rememberSaveable(report.uri.toString(), report.pdf) { mutableIntStateOf(0) }
    LaunchedEffect(report, retry) {
        document = null; error = null
        val directory = File(context.cacheDir, "report-previews")
        val file = File(directory, "preview-${UUID.randomUUID()}.tmp")
        try {
            document = withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs())
                val cutoff = System.currentTimeMillis() - 86_400_000L
                directory.listFiles()?.filter { it.name.startsWith("preview-") && it.extension == "tmp" && it.lastModified() < cutoff }
                    ?.forEach { it.delete() }
                val task = currentCoroutineContext()
                context.contentResolver.openInputStream(report.uri)?.use { input ->
                    file.outputStream().use { output -> ReportPreviewContent.copy(input, output, checkCancellation = { task.ensureActive() }) }
                } ?: error("Unavailable document")
                check(file.length() > 0)
                if (report.pdf) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        check(renderer.pageCount > 0)
                        PreviewDocument(file, renderer.pageCount)
                    }
                } else ReportPreviewContent.textPages(file.readText(Charsets.UTF_8)).let { PreviewDocument(file, it.size, it) }
            }
            page = page.coerceIn(0, requireNotNull(document).pageCount - 1)
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ReportPreviewTooLarge) { error = "文件过大，无法预览" }
        catch (_: Exception) { error = "无法预览，请重试" }
        finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("report-file-preview"), color = PageBackground) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("报告预览", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    PojianTextButton(onClick = onClose) { Text("关闭") }
                }
                val loaded = document
                when {
                    error != null -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(error!!)
                        PojianTextButton(onClick = { retry++ }) { Text("重试") }
                    }
                    loaded == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        key(loaded.file, page) {
                            if (loaded.texts != null) SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text(loaded.texts[page], Modifier.testTag("report-preview-text"), style = MaterialTheme.typography.bodyMedium)
                            } else PdfPreviewPage(loaded.file, page, Modifier.weight(1f).fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            PojianTextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                            MetadataText("${page + 1} / ${loaded.pageCount}")
                            PojianTextButton(onClick = { page++ }, enabled = page + 1 < loaded.pageCount) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPreviewPage(file: File, page: Int, modifier: Modifier) {
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PojianTextButton(onClick = { zoom-- }, enabled = zoom > 1) { Text("缩小") }
            MetadataText("${zoom * 100}%")
            PojianTextButton(onClick = { zoom++ }, enabled = zoom < 3) { Text("放大") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportWidth = maxWidth
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }
            var bitmap by remember(file, page, width) { mutableStateOf<Bitmap?>(null) }
            var failed by remember(file, page, width) { mutableStateOf(false) }
            LaunchedEffect(file, page, width) {
                try {
                    bitmap = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { renderer -> renderer.openPage(page).use { source ->
                                val scale = minOf((width * 3).coerceIn(1, 1800).toDouble() / source.width,
                                    sqrt(3_000_000.0 / source.width / source.height))
                                Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1),
                                    (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { image ->
                                    image.eraseColor(android.graphics.Color.WHITE)
                                    source.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            } }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
            val image = bitmap
            when {
                failed -> Text("无法显示此页")
                image == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Image(image.asImageBitmap(), "第 ${page + 1} 页", Modifier.width(viewportWidth * zoom)
                        .aspectRatio(image.width.toFloat() / image.height).background(Color.White).testTag("report-preview-pdf-page"))
                }
            }
        }
    }
}
